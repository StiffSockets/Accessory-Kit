///
/// @file: accessory_kit.dart
/// @package: accessory_kit
/// @created Date: Mon Dec 2025
/// @author: Nicholas J. Caruso
///
/// @last Modified: Mon Dec 29 2025
/// @modified By: Nicholas J. Caruso
///
/// @version: 1.1.31
///
///

import 'dart:async';

import 'package:flutter/services.dart';

/// Connection state of USB device
enum UsbConnectionState {
  /// Not connected to any device
  disconnected,

  /// Connected to a USB device
  connected,

  /// Error occurred during connection
  error,

  /// Permission requested from user
  permissionRequested,

  /// Searching for USB devices
  searching,
}

/// USB device information
class UsbDevice {
  /// Manufacturer name
  final String manufacturer;

  /// Model name
  final String model;

  /// Description
  final String description;

  /// Version
  final String version;

  /// URI
  final String uri;

  /// Serial number
  final String serial;

  /// Creates a new USB device information object
  UsbDevice({
    required this.manufacturer,
    required this.model,
    required this.description,
    required this.version,
    required this.uri,
    required this.serial,
  });
}

/// Main API for Android Open Accessory (AOA) USB communication
class AccessoryKitUsb {
  /// Method channel to communicate with platform code
  static const MethodChannel _channel = MethodChannel('accessory_kit');

  /// Event channel for receiving messages from USB device
  static const EventChannel _messageChannel = EventChannel('accessory_kit/messages');

  /// Event channel for connection state changes
  static const EventChannel _stateChannel = EventChannel('accessory_kit/state');

  /// Stream controller for message events
  static final _messageStreamController = StreamController<String>.broadcast();

  /// Stream controller for connection state events
  static final _stateStreamController = StreamController<UsbConnectionState>.broadcast();

  /// Stream of messages received from USB device
  static Stream<String> get messageStream => _messageStreamController.stream;

  /// Stream of connection state changes
  static Stream<UsbConnectionState> get connectionStateStream => _stateStreamController.stream;

  /// Current connection state
  static UsbConnectionState _connectionState = UsbConnectionState.disconnected;

  /// Returns current connection state
  static UsbConnectionState get connectionState => _connectionState;

  /// Initializes the plugin and starts listening for events
  static Future<void> initialize() async {
    // Set up message listener
    _messageChannel.receiveBroadcastStream().listen(
      (dynamic event) {
        if (event is String) {
          _messageStreamController.add(event);
        }
      },
      onError: (dynamic error) {
        print('Error receiving message: $error');
      },
    );

    // Set up state listener
    _stateChannel.receiveBroadcastStream().listen(
      (dynamic event) {
        if (event is String) {
          final state = _parseConnectionState(event);
          _connectionState = state;
          _stateStreamController.add(state);
        }
      },
      onError: (dynamic error) {
        print('Error receiving state update: $error');
        _connectionState = UsbConnectionState.error;
        _stateStreamController.add(UsbConnectionState.error);
      },
    );

    // Initialize platform side
    await _channel.invokeMethod('initialize');
  }

  /// Sets the device information to use for accessory mode
  static Future<void> setDeviceInfo({
    required String manufacturer,
    required String model,
    required String description,
    required String version,
    required String uri,
    required String serial,
  }) async {
    await _channel.invokeMethod('setDeviceInfo', {
      'manufacturer': manufacturer,
      'model': model,
      'description': description,
      'version': version,
      'uri': uri,
      'serial': serial,
    });
  }

  /// Starts scanning for USB devices
  static Future<bool> startScan() async {
    final result = await _channel.invokeMethod<bool>('startScan');
    return result ?? false;
  }

  /// Stops scanning for USB devices
  static Future<void> stopScan() async {
    await _channel.invokeMethod('stopScan');
  }

  /// Connects to a USB device
  static Future<bool> connect() async {
    final result = await _channel.invokeMethod<bool>('connect');
    return result ?? false;
  }

  /// Disconnects from a USB device
  static Future<void> disconnect() async {
    await _channel.invokeMethod('disconnect');
  }

  /// Sends a message to the connected USB device
  static Future<bool> sendMessage(String message) async {
    final result = await _channel.invokeMethod<bool>('sendMessage', {'message': message});
    return result ?? false;
  }

  /// Releases all resources used by the plugin
  static Future<void> dispose() async {
    await _channel.invokeMethod('dispose');

    // Close stream controllers
    await _messageStreamController.close();
    await _stateStreamController.close();
  }

  /// Parses connection state from string
  static UsbConnectionState _parseConnectionState(String state) {
    switch (state) {
      case 'connected':
        return UsbConnectionState.connected;
      case 'disconnected':
        return UsbConnectionState.disconnected;
      case 'error':
        return UsbConnectionState.error;
      case 'permissionRequested':
        return UsbConnectionState.permissionRequested;
      case 'searching':
        return UsbConnectionState.searching;
      default:
        return UsbConnectionState.disconnected;
    }
  }
}
