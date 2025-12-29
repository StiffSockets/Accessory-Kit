///
/// @file: main.dart
/// @package: example
/// @created Date: Sat Mar 2025
/// @author: Nicholas J. Caruso
///
/// @last Modified: Sat Mar 29 2025
/// @modified By: Nicholas J. Caruso
///
/// @version: 1.1.31
///
///

import 'package:accessory_kit/accessory_kit.dart';
import 'package:flutter/material.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Accessory Kit Demo',
      theme: ThemeData(primarySwatch: Colors.blue, useMaterial3: true),
      home: const UsbScreen(),
    );
  }
}

class UsbScreen extends StatefulWidget {
  const UsbScreen({super.key});

  @override
  State<UsbScreen> createState() => _UsbScreenState();
}

class _UsbScreenState extends State<UsbScreen> {
  String _connectionStatus = 'Disconnected';
  final List<MessageItem> _messages = [];
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

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
        _messages.add(MessageItem(message: message, isReceived: true, timestamp: DateTime.now()));
        _scrollToBottom();
      });
    });

    // Listen for connection state changes
    AccessoryKitUsb.connectionStateStream.listen((state) {
      setState(() {
        switch (state) {
          case UsbConnectionState.connected:
            _connectionStatus = 'Connected';
            _addSystemMessage('Connected to USB device');
            break;
          case UsbConnectionState.disconnected:
            _connectionStatus = 'Disconnected';
            _addSystemMessage('Disconnected from USB device');
            break;
          case UsbConnectionState.error:
            _connectionStatus = 'Error';
            _addSystemMessage('Error in USB connection');
            break;
          case UsbConnectionState.permissionRequested:
            _connectionStatus = 'Permission Requested';
            _addSystemMessage('USB permission requested');
            break;
          case UsbConnectionState.searching:
            _connectionStatus = 'Searching';
            _addSystemMessage('Searching for USB devices...');
            break;
        }
      });
    });
  }

  void _addSystemMessage(String message) {
    setState(() {
      _messages.add(MessageItem(message: message, isSystem: true, timestamp: DateTime.now()));
      _scrollToBottom();
    });
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _startScan() async {
    bool devicesFound = await AccessoryKitUsb.startScan();
    if (!devicesFound) {
      _addSystemMessage('No USB devices found');
    }
  }

  Future<void> _sendMessage() async {
    String message = _messageController.text;
    if (message.isNotEmpty) {
      bool sent = await AccessoryKitUsb.sendMessage(message);
      if (sent) {
        setState(() {
          _messages.add(MessageItem(message: message, timestamp: DateTime.now()));
          _messageController.clear();
          _scrollToBottom();
        });
      } else {
        _addSystemMessage('Failed to send message');
      }
    }
  }

  Future<void> _disconnect() async {
    await AccessoryKitUsb.disconnect();
  }

  @override
  void dispose() {
    AccessoryKitUsb.dispose();
    _messageController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('USB Communication Demo'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Column(children: [_buildStatusBar(), Expanded(child: _buildMessagesList()), _buildMessageInput()]),
    );
  }

  Widget _buildStatusBar() {
    Color statusColor;

    switch (_connectionStatus) {
      case 'Connected':
        statusColor = Colors.green;
        break;
      case 'Disconnected':
        statusColor = Colors.red;
        break;
      case 'Searching':
        statusColor = Colors.blue;
        break;
      case 'Permission Requested':
        statusColor = Colors.orange;
        break;
      default:
        statusColor = Colors.grey;
    }

    return Container(
      padding: const EdgeInsets.all(12),
      color: Colors.grey[200],
      child: Row(
        children: [
          Container(width: 12, height: 12, decoration: BoxDecoration(color: statusColor, shape: BoxShape.circle)),
          const SizedBox(width: 8),
          Text('Status: $_connectionStatus', style: const TextStyle(fontWeight: FontWeight.bold)),
          const Spacer(),
          ElevatedButton(onPressed: _startScan, child: const Text('Connect')),
          const SizedBox(width: 8),
          OutlinedButton(onPressed: _disconnect, child: const Text('Disconnect')),
        ],
      ),
    );
  }

  Widget _buildMessagesList() {
    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.all(8),
      itemCount: _messages.length,
      itemBuilder: (context, index) {
        return _buildMessageItem(_messages[index]);
      },
    );
  }

  Widget _buildMessageItem(MessageItem item) {
    if (item.isSystem) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Center(
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(color: Colors.grey[300], borderRadius: BorderRadius.circular(12)),
            child: Text(
              item.message,
              style: const TextStyle(color: Colors.black54, fontSize: 12, fontStyle: FontStyle.italic),
            ),
          ),
        ),
      );
    }

    return Align(
      alignment: item.isReceived ? Alignment.centerLeft : Alignment.centerRight,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: item.isReceived ? Colors.grey[300] : Colors.blue[100],
          borderRadius: BorderRadius.circular(16),
        ),
        constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.7),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(item.message, style: const TextStyle(fontSize: 16)),
            const SizedBox(height: 4),
            Text(_formatTime(item.timestamp), style: TextStyle(fontSize: 12, color: Colors.grey[600])),
          ],
        ),
      ),
    );
  }

  String _formatTime(DateTime time) {
    return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
  }

  Widget _buildMessageInput() {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white,
        boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.1), blurRadius: 4, offset: const Offset(0, -1))],
      ),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _messageController,
              decoration: InputDecoration(
                hintText: 'Enter message',
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(24), borderSide: BorderSide.none),
                filled: true,
                fillColor: Colors.grey[200],
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              ),
            ),
          ),
          const SizedBox(width: 8),
          FloatingActionButton(onPressed: _sendMessage, mini: true, child: const Icon(Icons.send)),
        ],
      ),
    );
  }
}

class MessageItem {
  final String message;
  final bool isReceived;
  final bool isSystem;
  final DateTime timestamp;

  MessageItem({required this.message, this.isReceived = false, this.isSystem = false, required this.timestamp});
}
