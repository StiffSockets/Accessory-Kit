# Accessory Kit

A Flutter plugin that implements Android Open Accessory (AOA) USB communication between Flutter Android applications and Windows hosts, enabling direct USB communication using the Android Open Accessory protocol, bypassing traditional transport layers such as Wi-Fi, Bluetooth, or ADB.

The plugin is explicitly designed for Android -> Windows communication and does not support iOS or macOS platforms.

It should be noted that this is not a trivial implementation and assumes a solid understanding of USB communication concepts and host/device roles. On the Windows side, a compatible USB driver is required. In most cases, WinUSB (installed via Zadig) is sufficient, as it exposes arbitrary USB interfaces directly to user-space applications without requiring a custom kernel driver. Writing a bespoke kernel-mode driver is also possible, but is generally unnecessary and significantly more complex. I have included a basic Python client example using `libusb1` to demonstrate communication with the example Flutter application.

## Features

- Connect Windows <-> Android devices via USB
- Send and receive messages
- Automatic message framing and error detection
- Connection state management
- Background thread handling

## Installation

Add this to your package's `pubspec.yaml` file:

```yaml
dependencies:
  accessory_kit: ^0.1.0
```

### Android Setup

Add the following permissions to your Android app's `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="your.package.name">

    <!-- USB Permissions -->
    <uses-feature android:name="android.hardware.usb.host" android:required="true" />
    <uses-feature android:name="android.hardware.usb.accessory" android:required="true" />
    <uses-permission android:name="android.permission.USB_ACCESSORY" />

    <application>
        <!-- Your application configuration -->

        <!-- This enables automatic USB device detection -->
        <activity
            android:name=".MainActivity"
            android:launchMode="singleTop"
            android:exported="true">
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED" />
            </intent-filter>

            <meta-data
                android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED"
                android:resource="@xml/accessory_filter" />
        </activity>
    </application>
</manifest>
```

Create a file at `android/app/src/main/res/xml/accessory_filter.xml` with your accessory identification:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-accessory
        manufacturer="StiffSockets"
        model="USBDataExchange"
        version="1.0" />
</resources>
```

## Usage

### Initialization

Initialize the plugin at the start of your app:

```dart
import 'package:accessory_kit/accessory_kit.dart';

// In your initialization code (e.g., initState)
await AccessoryKitUsb.initialize();

// Set your device information
// This must match the Python side and your accessory_filter.xml
await AccessoryKitUsb.setDeviceInfo(
  manufacturer: "StiffSockets",
  model: "USBDataExchange",
  description: "USB Data Exchange Accessory Thingy",
  version: "1.0",
  uri: "https://github.com/StiffSockets",
  serial: "0000000012345678",
);
```

### Listening for Messages

Set up listeners for incoming messages and connection state changes:

```dart
// Listen for incoming messages
AccessoryKitUsb.messageStream.listen((message) {
  print('Received message: $message');
  // Process the message
});

// Listen for connection state changes
AccessoryKitUsb.connectionStateStream.listen((state) {
  print('Connection state: $state');

  // Update UI based on connection state
  if (state == UsbConnectionState.connected) {
    // Device connected
  } else if (state == UsbConnectionState.disconnected) {
    // Device disconnected
  }
});
```

### Scanning and Connecting

Start scanning for USB devices and connect:

```dart
// Start scanning for USB devices
bool devicesFound = await AccessoryKitUsb.startScan();

// If devices found, connect
if (devicesFound) {
  bool connected = await AccessoryKitUsb.connect();
  if (connected) {
    print('Connected to USB device!');
  }
}
```

### Sending Messages

Send messages to the connected USB device:

```dart
// Send a message
bool sent = await AccessoryKitUsb.sendMessage('Hello from Flutter!');
if (sent) {
  print('Message sent successfully');
} else {
  print('Failed to send message');
}
```

### Cleaning Up

Dispose of resources when your app is closing:

```dart
// In your dispose method
@override
void dispose() {
  AccessoryKitUsb.dispose();
  super.dispose();
}
```

## Python Client Example

Here's a basic example of a Python client that can communicate with this plugin. This uses the improved protocol with proper message framing:

```python
import array
import sys
import threading
import time
import struct
import queue
import logging

import libusb1
import usb1

# Protocol constants
SOH = 0x01  # Start of Header
EOT = 0x04  # End of Transmission

# Your accessory details
MANUFACTURER = "StiffSockets"
MODEL = "USBDataExchange"
DESCRIPTION = "USB Data Exchange Accessory"
VERSION = "1.0"
URI = "https://github.com/StiffSockets"
SERIAL = "0000000012345678"

# Create your AndroidUSBComm class (from the improved main.py)
# ...

# Then in your main:
android_comm = AndroidUSBComm()
if android_comm.connect():
    print("Connected to Android device!")
    android_comm.start_threads()

    # Send and receive messages
    android_comm.queue_message("Hello from Python!")
```

## Complete Example

Here's a complete Flutter example:

```dart
import 'package:flutter/material.dart';
import 'package:accessory_kit/accessory_kit.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Accessory Kit Demo',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      home: UsbScreen(),
    );
  }
}

class UsbScreen extends StatefulWidget {
  @override
  _UsbScreenState createState() => _UsbScreenState();
}

class _UsbScreenState extends State<UsbScreen> {
  String _connectionStatus = 'Disconnected';
  final List<String> _messages = [];
  final TextEditingController _messageController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _initializeUsbCommunication();
  }

  Future<void> _initializeUsbCommunication() async {
    // Initialize the plugin
    await AccessoryKitUsb.initialize();

    // Set device information
    await AccessoryKitUsb.setDeviceInfo(
      manufacturer: "StiffSockets",
      model: "USBDataExchange",
      description: "USB Data Exchange Accessory",
      version: "1.0",
      uri: "https://github.com/StiffSockets",
      serial: "0000000012345678",
    );

    // Listen for incoming messages
    AccessoryKitUsb.messageStream.listen((message) {
      setState(() {
        _messages.add('Received: $message');
      });
    });

    // Listen for connection state changes
    AccessoryKitUsb.connectionStateStream.listen((state) {
      setState(() {
        switch (state) {
          case UsbConnectionState.connected:
            _connectionStatus = 'Connected';
            break;
          case UsbConnectionState.disconnected:
            _connectionStatus = 'Disconnected';
            break;
          case UsbConnectionState.error:
            _connectionStatus = 'Error';
            break;
          case UsbConnectionState.permissionRequested:
            _connectionStatus = 'Permission Requested';
            break;
          case UsbConnectionState.searching:
            _connectionStatus = 'Searching';
            break;
        }
      });
    });
  }

  Future<void> _startScan() async {
    bool devicesFound = await AccessoryKitUsb.startScan();
    if (devicesFound) {
      bool connected = await AccessoryKitUsb.connect();
      if (connected) {
        setState(() {
          _messages.add('System: Connected to USB device!');
        });
      } else {
        setState(() {
          _messages.add('System: Failed to connect.');
        });
      }
    } else {
      setState(() {
        _messages.add('System: No USB devices found.');
      });
    }
  }

  Future<void> _sendMessage() async {
    String message = _messageController.text;
    if (message.isNotEmpty) {
      bool sent = await AccessoryKitUsb.sendMessage(message);
      if (sent) {
        setState(() {
          _messages.add('Sent: $message');
          _messageController.clear();
        });
      } else {
        setState(() {
          _messages.add('System: Failed to send message.');
        });
      }
    }
  }

  @override
  void dispose() {
    AccessoryKitUsb.dispose();
    _messageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('USB Communication Demo'),
      ),
      body: Column(
        children: [
          Container(
            padding: EdgeInsets.all(8),
            color: Colors.grey[200],
            child: Row(
              children: [
                Text('Status: $_connectionStatus'),
                Spacer(),
                ElevatedButton(
                  onPressed: _startScan,
                  child: Text('Scan & Connect'),
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView.builder(
              itemCount: _messages.length,
              itemBuilder: (context, index) {
                return ListTile(
                  title: Text(_messages[index]),
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _messageController,
                    decoration: InputDecoration(
                      hintText: 'Enter message',
                      border: OutlineInputBorder(),
                    ),
                  ),
                ),
                SizedBox(width: 8),
                ElevatedButton(
                  onPressed: _sendMessage,
                  child: Text('Send'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
```

## Troubleshooting

### Common Issues

1. **Permission Denied**: Make sure your Android application has the necessary permissions.
2. **Device Not Found**: Check your USB cable and ensure USB debugging is enabled on the Android device.
3. **Connection Errors**: Verify that the accessory information matches between your Flutter app, Android manifest, and Python client.

### Debugging

Enable verbose logging in your application to see detailed USB communication:

```dart
// Add this to your application's initialization
AccessoryKitUsb.enableLogging(true);
```

## Notes

This project was developed privately before being released publicly. The public repository starts from the current stable implementation.
