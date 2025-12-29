/**
 * @file: AccessoryKitPlugin.java
 * @package: accessory_kit
 * @created Date: Mon Dec 2025
 * @author: Nicholas J. Caruso
 * 
 * @last Modified: Mon Dec 29 2025
 * @modified By: Nicholas J. Caruso
 * 
 * @version: 1.1.31
 * 
 */





package com.stiffsockets.accessory_kit;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;


/** AccessoryKitPlugin */
public class AccessoryKitPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
  private static final String TAG = "AccessoryKitUsb";
  private static final String ACTION_USB_PERMISSION = "com.stiffsockets.accessory_kit.USB_PERMISSION";
  
  // Protocol constants
  private static final byte SOH = 0x01;  // Start of Header
  private static final byte EOT = 0x04;  // End of Transmission
  private static final int MAX_BUFFER_SIZE = 16384;  // 16KB buffer
  
  // Method channel constants
  private static final String CHANNEL_NAME = "accessory_kit";
  private static final String MESSAGE_CHANNEL_NAME = "accessory_kit/messages";
  private static final String STATE_CHANNEL_NAME = "accessory_kit/state";
  
  // Connection states
  private static final String STATE_CONNECTED = "connected";
  private static final String STATE_DISCONNECTED = "disconnected";
  private static final String STATE_ERROR = "error";
  private static final String STATE_PERMISSION_REQUESTED = "permissionRequested";
  private static final String STATE_SEARCHING = "searching";

  // Plugin fields
  private MethodChannel channel;
  private EventChannel messageChannel;
  private EventChannel stateChannel;
  private EventChannel.EventSink messageSink;
  private EventChannel.EventSink stateSink;
  private Context applicationContext;
  private Activity activity;
  
  // USB fields
  private UsbManager usbManager;
  private UsbAccessory accessory;
  private ParcelFileDescriptor fileDescriptor;
  private FileInputStream inputStream;
  private FileOutputStream outputStream;
  private boolean permissionRequested = false;
  
  // Threading
  private final ExecutorService executorService = Executors.newFixedThreadPool(2);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  
  // Configuration
  private String manufacturer = "StiffSockets";
  private String model = "USBDataExchange";
  private String description = "USB Data Exchange Accessory";
  private String version = "1.0";
  private String uri = "https://github.com/StiffSockets";
  private String serial = "0000000012345678";
  
  // Message parsing state
  private enum ReadState {
    WAIT_SOH, READ_LENGTH, READ_DATA, WAIT_EOT
  }
  
  private ReadState readState = ReadState.WAIT_SOH;
  private final ByteBuffer lengthBuffer = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
  private byte[] dataBuffer = new byte[MAX_BUFFER_SIZE];
  private int expectedLength = 0;
  private int dataRead = 0;

  // BroadcastReceiver for USB events
  private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (ACTION_USB_PERMISSION.equals(action)) {
        synchronized (this) {
          UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            if (accessory != null) {
              openAccessory(accessory);
            }
          } else {
            Log.d(TAG, "Permission denied for accessory " + accessory);
            updateState(STATE_ERROR);
          }
        }
      } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
        UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if (accessory != null && accessory.equals(AccessoryKitPlugin.this.accessory)) {
          updateState(STATE_DISCONNECTED);
          closeAccessory();
        }
      }
    }
  };

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    applicationContext = flutterPluginBinding.getApplicationContext();
    
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), CHANNEL_NAME);
    channel.setMethodCallHandler(this);
    
    messageChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), MESSAGE_CHANNEL_NAME);
    messageChannel.setStreamHandler(new EventChannel.StreamHandler() {
      @Override
      public void onListen(Object arguments, EventChannel.EventSink events) {
        messageSink = events;
      }

      @Override
      public void onCancel(Object arguments) {
        messageSink = null;
      }
    });
    
    stateChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), STATE_CHANNEL_NAME);
    stateChannel.setStreamHandler(new EventChannel.StreamHandler() {
      @Override
      public void onListen(Object arguments, EventChannel.EventSink events) {
        stateSink = events;
        // Send initial state
        updateState(STATE_DISCONNECTED);
      }

      @Override
      public void onCancel(Object arguments) {
        stateSink = null;
      }
    });
    
    // Get USB manager
    usbManager = (UsbManager) applicationContext.getSystemService(Context.USB_SERVICE);
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    switch (call.method) {
      case "initialize":
        handleInitialize(result);
        break;
      case "setDeviceInfo":
        handleSetDeviceInfo(call, result);
        break;
      case "startScan":
        handleStartScan(result);
        break;
      case "stopScan":
        handleStopScan(result);
        break;
      case "connect":
        handleConnect(result);
        break;
      case "disconnect":
        handleDisconnect(result);
        break;
      case "sendMessage":
        handleSendMessage(call, result);
        break;
      case "dispose":
        handleDispose(result);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  private void handleInitialize(Result result) {
    // Register for USB events
    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION_USB_PERMISSION);
    filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
    
    // Register receiver based on Android version
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      applicationContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      applicationContext.registerReceiver(usbReceiver, filter);
    }
    
    result.success(null);
  }

  private void handleSetDeviceInfo(MethodCall call, Result result) {
    manufacturer = call.argument("manufacturer");
    model = call.argument("model");
    description = call.argument("description");
    version = call.argument("version");
    uri = call.argument("uri");
    serial = call.argument("serial");
    
    result.success(null);
  }

  private void handleStartScan(Result result) {
    updateState(STATE_SEARCHING);
    
    // Look for USB accessories
    UsbAccessory[] accessories = usbManager.getAccessoryList();
    UsbAccessory accessory = (accessories == null ? null : accessories[0]);
    
    if (accessory != null) {
      if (usbManager.hasPermission(accessory)) {
        openAccessory(accessory);
        result.success(true);
      } else {
        requestPermission(accessory);
        result.success(false);
      }
    } else {
      updateState(STATE_DISCONNECTED);
      result.success(false);
    }
  }

  private void handleStopScan(Result result) {
    // Stop scanning (cancel any pending operations)
    updateState(STATE_DISCONNECTED);
    result.success(null);
  }

  private void handleConnect(Result result) {
    // Already checked in startScan, just return connection status
    boolean isConnected = (inputStream != null && outputStream != null);
    result.success(isConnected);
  }

  private void handleDisconnect(Result result) {
    closeAccessory();
    result.success(null);
  }

  private void handleSendMessage(MethodCall call, Result result) {
    final String message = call.argument("message");
    
    if (message == null || message.isEmpty()) {
      result.success(false);
      return;
    }
    
    executorService.execute(() -> {
      boolean success = sendData(message);
      mainHandler.post(() -> result.success(success));
    });
  }

  private void handleDispose(Result result) {
    // Clean up resources
    closeAccessory();
    
    try {
      applicationContext.unregisterReceiver(usbReceiver);
    } catch (Exception e) {
      Log.e(TAG, "Error unregistering receiver", e);
    }
    
    executorService.shutdown();
    
    result.success(null);
  }

  private void requestPermission(UsbAccessory accessory) {
    if (!permissionRequested) {
      permissionRequested = true;
      updateState(STATE_PERMISSION_REQUESTED);
      
      PendingIntent pendingIntent;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        pendingIntent = PendingIntent.getBroadcast(applicationContext, 0, 
            new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
      } else {
        pendingIntent = PendingIntent.getBroadcast(applicationContext, 0, 
            new Intent(ACTION_USB_PERMISSION), 0);
      }
      
      usbManager.requestPermission(accessory, pendingIntent);
    }
  }

  private void openAccessory(UsbAccessory accessory) {
    fileDescriptor = usbManager.openAccessory(accessory);
    if (fileDescriptor != null) {
      this.accessory = accessory;
      FileDescriptor fd = fileDescriptor.getFileDescriptor();
      inputStream = new FileInputStream(fd);
      outputStream = new FileOutputStream(fd);
      
      // Reset the read state
      resetReadState();
      
      // Start the reader thread
      running.set(true);
      executorService.execute(this::receiveData);
      
      updateState(STATE_CONNECTED);
    } else {
      Log.d(TAG, "Failed to open accessory");
      updateState(STATE_ERROR);
    }
  }

  private void closeAccessory() {
    running.set(false);
    
    try {
      if (fileDescriptor != null) {
        fileDescriptor.close();
      }
    } catch (IOException e) {
      Log.e(TAG, "Error closing accessory", e);
    } finally {
      fileDescriptor = null;
      accessory = null;
      inputStream = null;
      outputStream = null;
      updateState(STATE_DISCONNECTED);
    }
  }

  private boolean sendData(String message) {
    if (outputStream == null) {
      Log.e(TAG, "Output stream is null");
      return false;
    }
    
    try {
      // Format: SOH + Length (2 bytes) + Data + EOT
      byte[] data = message.getBytes("UTF-8");
      
      if (data.length > 65535) {
        Log.e(TAG, "Message too long: " + data.length + " bytes");
        return false;
      }
      
      // Create a buffer with enough space for the message and framing
      ByteBuffer buffer = ByteBuffer.allocate(data.length + 4);
      buffer.order(ByteOrder.BIG_ENDIAN);
      
      // Start of Header
      buffer.put(SOH);
      
      // Length (2 bytes, big-endian)
      buffer.putShort((short) data.length);
      
      // Data
      buffer.put(data);
      
      // End of Transmission
      buffer.put(EOT);
      
      // Send the message
      synchronized (outputStream) {
        outputStream.write(buffer.array());
        outputStream.flush();
      }
      
      Log.d(TAG, "Sent message: " + message + " (" + data.length + " bytes)");
      return true;
    } catch (IOException e) {
      Log.e(TAG, "Error sending data", e);
      return false;
    }
  }

  private void resetReadState() {
    readState = ReadState.WAIT_SOH;
    lengthBuffer.clear();
    expectedLength = 0;
    dataRead = 0;
  }

  private void receiveData() {
    byte[] buffer = new byte[64]; // Read in chunks
    
    while (running.get()) {
      try {
        int bytesRead = inputStream.read(buffer);
        
        if (bytesRead <= 0) {
          // No data available or error occurred
          Thread.sleep(10); // Short sleep to avoid busy waiting
          continue;
        }
        
        // Process each byte according to the current state
        for (int i = 0; i < bytesRead; i++) {
          byte b = buffer[i];
          
          switch (readState) {
            case WAIT_SOH:
              if (b == SOH) {
                readState = ReadState.READ_LENGTH;
                lengthBuffer.clear();
              }
              break;
              
            case READ_LENGTH:
              lengthBuffer.put(b);
              if (lengthBuffer.position() == 2) {
                lengthBuffer.flip();
                expectedLength = lengthBuffer.getShort() & 0xFFFF;
                
                if (expectedLength > 0 && expectedLength <= MAX_BUFFER_SIZE) {
                  readState = ReadState.READ_DATA;
                  dataRead = 0;
                } else {
                  // Invalid length
                  Log.e(TAG, "Invalid message length: " + expectedLength);
                  resetReadState();
                }
              }
              break;
              
            case READ_DATA:
              if (dataRead < expectedLength) {
                dataBuffer[dataRead++] = b;
                if (dataRead == expectedLength) {
                  readState = ReadState.WAIT_EOT;
                }
              }
              break;
              
            case WAIT_EOT:
              if (b == EOT) {
                // Complete message received
                final String message = new String(dataBuffer, 0, expectedLength, "UTF-8");
                
                // Send to Flutter
                if (messageSink != null) {
                  mainHandler.post(() -> messageSink.success(message));
                }
                
                Log.d(TAG, "Received message: " + message);
              } else {
                Log.e(TAG, "Invalid EOT byte: " + b);
              }
              
              // Reset for next message
              resetReadState();
              break;
          }
        }
      } catch (IOException e) {
        if (running.get()) {
          Log.e(TAG, "Error reading data", e);
          updateState(STATE_ERROR);
          
          // Allow time for error recovery
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    
    Log.d(TAG, "Receive thread stopped");
  }

  private void updateState(String state) {
    if (stateSink != null) {
      mainHandler.post(() -> stateSink.success(state));
    }
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    
    // Clean up
    closeAccessory();
    
    try {
      applicationContext.unregisterReceiver(usbReceiver);
    } catch (Exception e) {
      Log.e(TAG, "Error unregistering receiver", e);
    }
    
    executorService.shutdown();
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activity = binding.getActivity();
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    activity = null;
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    activity = binding.getActivity();
  }

  @Override
  public void onDetachedFromActivity() {
    activity = null;
  }
}