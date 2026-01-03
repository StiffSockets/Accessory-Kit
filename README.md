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
  accessory_kit: ^1.0.0
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
"""Example script for Android USB accessory (AOA) communication.

Provides helper classes to manage AOA accessory setup and framed message
exchange (SOH+Length+Data+EOT) between a host and an Android device.
"""

import array
import logging
import queue
import struct
import sys
import threading
import time

import libusb1
import usb1

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('AndroidUSB')

# Protocol constants
SOH = 0x01  # Start of Header
EOT = 0x04  # End of Transmission
MAX_MSG_LENGTH = 65535  # 2-byte length field
PROTOCOL_OVERHEAD = 4  # SOH(1) + Length(2) + EOT(1)
TIMEOUT_MS = 2000  # Increased timeout

# AOA v2 protocol constants
AOA_GET_PROTOCOL = 51
AOA_SEND_IDENTITY = 52
AOA_START_ACCESSORY = 53

# Manufacturer, model, etc. for accessory identification
# Must match what's defined in the Android app's accessory_filter.xml
MANUFACTURER = "StiffSockets"
MODEL = "USBDataExchange"
DESCRIPTION = "USB Data Exchange Accessory"
VERSION = "1.0"
URI = "https://github.com/StiffSockets"
SERIAL = "0000000012345678"


class MessageBuffer:
    """Buffer and state machine for assembling framed messages.

    Implements a simple state machine for messages framed as:
    SOH(1) + Length(2, big-endian) + Data + EOT(1).

    Methods:
        reset(): Reset internal state to await a new message.
        process_byte(byte): Feed a single incoming byte and return the
            completed payload bytes (without framing) when a full message
            is assembled, otherwise returns None.
        calculate_checksum(data): Return an 8-bit checksum of `data`.

    Attributes:
        state (str): Current state of the state machine (e.g. 'WAIT_SOH').
        buffer (bytearray): Accumulated raw bytes for the current message.
        length (int): Expected payload length extracted from the length field.
    """

    def __init__(self):
        self.reset()

    def reset(self):
        """Reset the buffer state"""
        self.state = 'WAIT_SOH'
        self.buffer = bytearray()
        self.length = 0

    def process_byte(self, byte):
        """Process a single byte, returning complete messages when found"""
        if self.state == 'WAIT_SOH':
            if byte == SOH:
                self.buffer = bytearray([SOH])
                self.state = 'READ_LENGTH_1'

        elif self.state == 'READ_LENGTH_1':
            self.buffer.append(byte)
            self.state = 'READ_LENGTH_2'

        elif self.state == 'READ_LENGTH_2':
            self.buffer.append(byte)
            self.length = (self.buffer[1] << 8) | self.buffer[2]
            self.state = 'READ_DATA'

        elif self.state == 'READ_DATA':
            self.buffer.append(byte)
            if len(self.buffer) == self.length + 3:  # SOH + 2 length bytes + data
                self.state = 'WAIT_EOT'

        elif self.state == 'WAIT_EOT':
            self.buffer.append(byte)
            if byte == EOT:
                # Message complete
                # Extract data without SOH, length, EOT
                result = self.buffer[3:-1]
                self.reset()
                return result
            else:
                # Invalid EOT
                logger.warning(f"Invalid EOT byte: {byte}")
                self.reset()

        return None

    def calculate_checksum(self, data):
        """Calculate a simple checksum for data validation"""
        return sum(data) & 0xFF


class AndroidUSBComm:
    """Manage USB connection and communication with an Android accessory.

    Responsibilities:
        - Discover Android devices and transition them into AOA accessory mode.
        - Discover accessory endpoints and claim interfaces.
        - Send and receive framed messages using MessageBuffer for parsing.
        - Run background sender/receiver/reconnect threads.

    Attributes:
        context (usb1.USBContext): USB context object.
        handle: Open device handle or None.
        ep_in (int): Address of IN (device->host) bulk endpoint.
        ep_out (int): Address of OUT (host->device) bulk endpoint.
        connected (bool): True when accessory endpoints are configured.
        interface_num (int): Interface number claimed on the device.
        send_queue (queue.Queue): Queue for outgoing messages.
        recv_buffer (MessageBuffer): Message parsing buffer.
        receiver_running (bool): Receiver thread control flag.
        sender_running (bool): Sender thread control flag.
        reconnect_event (threading.Event): Event to request reconnect.
    """

    def __init__(self):
        self.context = usb1.USBContext()
        self.handle = None
        self.ep_in = None
        self.ep_out = None
        self.connected = False
        self.interface_num = 0
        self.send_queue = queue.Queue()
        self.recv_buffer = MessageBuffer()
        self.receiver_running = False
        self.sender_running = False
        self.reconnect_event = threading.Event()

    def find_device(self):
        """Find an Android device"""
        # Look for Android device
        common_android_vendors = [0x18d1, 0x2d01, 0x04e8,
                                  0x22b8, 0x0fce]  # Common Android vendor IDs

        for device in self.context.getDeviceList():
            try:
                vendor_id = device.getVendorID()
                product_id = device.getProductID()

                if vendor_id in common_android_vendors:
                    logger.info(
                        f"Found potential Android device: {vendor_id:04x}:{product_id:04x}")

                    try:
                        self.handle = device.open()
                        # Check if the device is in use by another driver
                        for i in range(256):  # Try all possible interface numbers
                            try:
                                if self.handle.kernelDriverActive(i):
                                    try:
                                        self.handle.detachKernelDriver(i)
                                        logger.info(
                                            f"Detached kernel driver for interface {i}")
                                        self.interface_num = i
                                    except libusb1.USBError as e:
                                        logger.warning(
                                            f"Could not detach kernel driver for interface {i}: {e}")
                            except libusb1.USBError:
                                # This interface doesn't exist or has no driver
                                pass
                        return True
                    except libusb1.USBError as e:
                        if e.value == libusb1.LIBUSB_ERROR_ACCESS:
                            logger.error(
                                f"Permission denied. You might need elevated privileges.")
                        elif e.value == libusb1.LIBUSB_ERROR_BUSY:
                            logger.error(
                                f"Device busy. It might be used by another application.")
                        else:
                            logger.error(
                                f"Could not open device: {e} (code: {e.value})")
                        continue
            except Exception as e:
                logger.error(f"Error processing device: {e}")
                continue

        logger.info("No Android device found")
        return False

    def setup_accessory_mode(self):
        """Set up AOA mode on the connected Android device"""
        try:
            # Detach kernel driver if active
            try:
                self.handle.detachKernelDriver(self.interface_num)
            except libusb1.USBError:
                # This is often expected if the kernel driver is not attached
                pass

            # Try to claim the interface
            try:
                self.handle.claimInterface(self.interface_num)
            except libusb1.USBError as e:
                logger.warning(f"Warning: Could not claim interface: {e}")

            # Check AOA protocol version
            try:
                # Using controlRead instead of controlTransfer
                transferred = self.handle.controlRead(
                    libusb1.LIBUSB_ENDPOINT_IN | libusb1.LIBUSB_REQUEST_TYPE_VENDOR,
                    AOA_GET_PROTOCOL, 0, 0, 2, TIMEOUT_MS)

                if len(transferred) == 2:
                    protocol_version = transferred[0]
                    logger.info(
                        f"Device supports AOA protocol version: {protocol_version}")

                    if protocol_version == 0:
                        logger.error("Device does not support AOA")
                        return False
                else:
                    logger.error("Invalid response for protocol version")
                    return False
            except libusb1.USBError as e:
                logger.error(f"Error getting protocol version: {e}")
                return False

            # Send accessory information using controlWrite
            self.handle.controlWrite(
                libusb1.LIBUSB_ENDPOINT_OUT | libusb1.LIBUSB_REQUEST_TYPE_VENDOR,
                AOA_SEND_IDENTITY, 0, 0, MANUFACTURER.encode('utf-8'), TIMEOUT_MS)
            self.handle.controlWrite(
                libusb1.LIBUSB_ENDPOINT_OUT | libusb1.LIBUSB_REQUEST_TYPE_VENDOR,
                AOA_SEND_IDENTITY, 0, 1, MODEL.encode('utf-8'), TIMEOUT_MS)
            self.handle.controlWrite(
                libusb1.LIBUSB_ENDPOINT_OUT | libusb1.LIBUSB_REQUEST_TYPE_VENDOR,
                AOA_SEND_IDENTITY, 0, 2, DESCRIPTION.encode('utf-8'), TIMEOUT_MS)
            self.handle.controlWrite(
                libusb1.LIBUSB_ENDPOINT_OUT | libusb1.LIBUSB_REQUEST_TYPE_VENDOR,
                AOA_SEND_IDENTITY, 0, 3, VERSION.encode('utf-8'), TIMEOUT_MS)
            self.handle.controlWrite(
                libusb1.LIBUSB_ENDPOINT_OUT | libusb1.LIBUSB_REQUEST_TYPE_VENDOR,
                AOA_SEND_IDENTITY, 0, 4, URI.encode('utf-8'), TIMEOUT_MS)
            self.handle.controlWrite(
                libusb1.LIBUSB_ENDPOINT_OUT | libusb1.LIBUSB_REQUEST_TYPE_VENDOR,
                AOA_SEND_IDENTITY, 0, 5, SERIAL.encode('utf-8'), TIMEOUT_MS)

            # Start accessory mode
            self.handle.controlWrite(
                libusb1.LIBUSB_ENDPOINT_OUT | libusb1.LIBUSB_REQUEST_TYPE_VENDOR,
                AOA_START_ACCESSORY, 0, 0, b'', TIMEOUT_MS)

            # Close the handle as the device will disconnect and reconnect
            self.handle.close()
            self.handle = None

            logger.info(
                "Accessory mode started, waiting for device to reconnect...")
            return True

        except libusb1.USBError as e:
            logger.error(f"Error setting up accessory mode: {e}")
            return False

    def find_accessory_device(self):
        """Find device after it switched to accessory mode"""
        # AOA device has standard VID/PID
        AOA_VID = 0x18d1
        AOA_PID = [0x2D00, 0x2D01, 0x2D04, 0x2D05]

        # Wait a moment for the device to reconnect
        for attempt in range(10):  # Increased attempts
            logger.info(
                f"Looking for accessory device (attempt {attempt+1}/10)...")

            for device in self.context.getDeviceList():
                vendor_id = device.getVendorID()
                product_id = device.getProductID()

                if vendor_id == AOA_VID and product_id in AOA_PID:
                    logger.info(
                        f"Found accessory device: {vendor_id:04x}:{product_id:04x}")

                    try:
                        # Open the device
                        self.handle = device.open()

                        # Detach kernel driver if needed
                        try:
                            self.handle.detachKernelDriver(self.interface_num)
                        except libusb1.USBError:
                            # Expected if driver not attached
                            pass

                        # Set configuration (usually configuration 1)
                        try:
                            self.handle.setConfiguration(1)
                        except libusb1.USBError as e:
                            logger.warning(
                                f"Warning: Could not set configuration: {e}")

                        # Get number of configurations
                        num_configurations = device.getNumConfigurations()
                        logger.info(
                            f"Device has {num_configurations} configuration(s)")

                        # Find endpoints manually by checking all interfaces
                        found_endpoints = False

                        for config_num in range(num_configurations):
                            # Get configuration descriptor
                            config = device[config_num]

                            logger.info(f"Checking configuration {config_num}")

                            # Check all interfaces in this configuration
                            for interface_num in range(config.getNumInterfaces()):
                                logger.info(
                                    f"Checking interface {interface_num}")

                                # Try to claim this interface
                                try:
                                    self.handle.claimInterface(interface_num)
                                    self.interface_num = interface_num
                                    logger.info(
                                        f"Claimed interface {interface_num}")
                                except libusb1.USBError as e:
                                    logger.warning(
                                        f"Could not claim interface {interface_num}: {e}")
                                    continue

                                # Get the interface descriptor
                                interface = config[interface_num]
                                # Get the first alt setting
                                # Access first alternate setting
                                alt_setting = interface[0]

                                # Check all endpoints in this interface
                                for ep_idx in range(alt_setting.getNumEndpoints()):
                                    # Get endpoint directly from alt setting
                                    endpoint = alt_setting[ep_idx]
                                    address = endpoint.getAddress()  # Use getAddress() method

                                    logger.info(
                                        f"Found endpoint: 0x{address:02x}, type: {endpoint.getAttributes() & 0x03}")

                                    # Check if this is a bulk endpoint
                                    if (endpoint.getAttributes() & 0x03) == libusb1.LIBUSB_TRANSFER_TYPE_BULK:
                                        if address & libusb1.LIBUSB_ENDPOINT_IN:  # IN endpoint
                                            self.ep_in = address
                                            logger.info(
                                                f"Using as IN endpoint: 0x{address:02x}")
                                        else:  # OUT endpoint
                                            self.ep_out = address
                                            logger.info(
                                                f"Using as OUT endpoint: 0x{address:02x}")

                                # If we found both endpoints, we're done
                                if self.ep_in is not None and self.ep_out is not None:
                                    logger.info("Found both endpoints")
                                    found_endpoints = True
                                    break
                                else:
                                    # Release this interface and try another one
                                    try:
                                        self.handle.releaseInterface(
                                            interface_num)
                                        logger.info(
                                            f"Released interface {interface_num}")
                                    except libusb1.USBError as e:
                                        logger.warning(
                                            f"Could not release interface {interface_num}: {e}")

                            if found_endpoints:
                                break

                        if self.ep_in is not None and self.ep_out is not None:
                            logger.info("Endpoints configured successfully")
                            self.connected = True
                            return True
                        else:
                            logger.error("Could not find suitable endpoints")
                            if self.handle:
                                self.handle.close()
                                self.handle = None

                    except libusb1.USBError as e:
                        logger.error(f"Error configuring device: {e}")
                        if self.handle:
                            self.handle.close()
                            self.handle = None

            time.sleep(1)  # Wait and try again

        logger.error("Could not find accessory device")
        return False

    def connect(self):
        """Connect to an Android device and set up AOA mode"""
        if self.find_device():
            if self.setup_accessory_mode():
                # Wait for device to reconnect in accessory mode
                time.sleep(2)
                return self.find_accessory_device()
        return False

    def _format_message(self, data):
        """Format a message with proper framing"""
        data_bytes = data.encode('utf-8')
        if len(data_bytes) > MAX_MSG_LENGTH:
            logger.warning(
                f"Message too long ({len(data_bytes)} bytes), truncating to {MAX_MSG_LENGTH} bytes")
            data_bytes = data_bytes[:MAX_MSG_LENGTH]

        # Format: SOH + Length (2 bytes) + Data + EOT
        # 2 bytes in network byte order
        length_bytes = struct.pack('>H', len(data_bytes))
        message = bytes([SOH]) + length_bytes + data_bytes + bytes([EOT])
        return message

    def _read_exact(self, length, timeout=TIMEOUT_MS):
        """Read exactly the specified number of bytes"""
        if not self.connected or not self.handle or not self.ep_in:
            return None

        result = bytearray()
        remaining = length
        end_time = time.time() + (timeout / 1000.0)

        while remaining > 0 and time.time() < end_time:
            try:
                # Read in smaller chunks to avoid blocking too long
                chunk_size = min(remaining, 64)
                chunk = self.handle.bulkRead(self.ep_in, chunk_size, 100)
                if chunk:
                    result.extend(chunk)
                    remaining -= len(chunk)
                else:
                    # Allow a short delay for more data to arrive
                    time.sleep(0.01)
            except libusb1.USBError as e:
                if e.value == libusb1.LIBUSB_ERROR_TIMEOUT:
                    # Timeout is normal, just keep trying
                    continue
                else:
                    # Other errors are a problem
                    logger.error(f"USB error during read: {e}")
                    return None

        return result if len(result) == length else None

    def send_data(self, data):
        """Send data to the Android device"""
        if not self.connected or not self.handle or not self.ep_out:
            logger.error("Not connected")
            return False

        try:
            # Format the message with proper framing
            message = self._format_message(data)

            # Send in chunks to handle large messages
            total_sent = 0
            message_len = len(message)

            while total_sent < message_len:
                # Send up to 64 bytes at a time (common USB packet size)
                chunk_size = min(64, message_len - total_sent)
                chunk = message[total_sent:total_sent + chunk_size]

                transferred = self.handle.bulkWrite(
                    self.ep_out, chunk, TIMEOUT_MS)

                if transferred > 0:
                    total_sent += transferred
                else:
                    logger.error(
                        f"Failed to send chunk, transferred: {transferred}")
                    return False

            logger.info(f"Sent: {data} ({total_sent} bytes)")
            return True

        except libusb1.USBError as e:
            logger.error(f"Error sending data: {e}")
            self.connected = False
            self.reconnect_event.set()
            return False

    def receive_data(self):
        """Receive data from the Android device"""
        if not self.connected or not self.handle or not self.ep_in:
            return None

        try:
            # Read one byte at a time to process through the state machine
            # This is less efficient but more robust for message framing
            chunk = self.handle.bulkRead(
                self.ep_in, 64, 100)  # Read up to 64 bytes

            if not chunk:
                return None

            # Process each byte through the message buffer
            for byte in chunk:
                result = self.recv_buffer.process_byte(byte)
                if result:
                    try:
                        return result.decode('utf-8')
                    except UnicodeDecodeError:
                        logger.warning("Received invalid UTF-8 data")
                        return None

        except libusb1.USBError as e:
            if e.value == libusb1.LIBUSB_ERROR_TIMEOUT:
                # Timeout is normal, just return None
                pass
            else:
                logger.error(f"Error receiving data: {e}")
                self.connected = False
                self.reconnect_event.set()

        return None

    def _sender_loop(self):
        """Background thread to send queued messages"""
        self.sender_running = True

        while self.sender_running:
            try:
                # Get message from queue with timeout
                try:
                    message = self.send_queue.get(timeout=0.5)
                except queue.Empty:
                    continue

                # Try to send the message
                if self.connected:
                    success = self.send_data(message)
                    if not success and self.connected:
                        # If send failed but we're still marked as connected, try again
                        logger.info(
                            f"Send failed, requeueing message: {message}")
                        self.send_queue.put(message)
                else:
                    # Not connected, requeue the message
                    self.send_queue.put(message)
                    time.sleep(1)  # Don't hammer the queue

            except Exception as e:
                logger.error(f"Error in sender loop: {e}")
                time.sleep(1)  # Avoid busy loop on error

        logger.info("Sender thread stopped")

    def _receiver_loop(self):
        """Background thread to receive data"""
        self.receiver_running = True

        while self.receiver_running:
            try:
                if self.connected:
                    data = self.receive_data()
                    if data:
                        logger.info(f"Received: {data}")
                        print(f"Received: {data}")
                else:
                    time.sleep(0.5)  # Not connected, don't busy-wait
            except Exception as e:
                logger.error(f"Error in receiver loop: {e}")
                time.sleep(1)  # Avoid busy loop on error

        logger.info("Receiver thread stopped")

    def _reconnect_loop(self):
        """Background thread to handle reconnection"""
        while self.connected or self.reconnect_event.is_set():
            if self.reconnect_event.is_set():
                logger.info("Reconnection triggered")
                self.reconnect_event.clear()

                # Close current connection if any
                self.cleanup()

                # Wait a bit before reconnecting
                time.sleep(3)

                # Try to reconnect
                if self.connect():
                    logger.info("Successfully reconnected")
                else:
                    logger.error("Reconnection failed")
                    # Set event to try again later
                    time.sleep(5)
                    self.reconnect_event.set()

            time.sleep(1)

        logger.info("Reconnect thread stopped")

    def queue_message(self, message):
        """Queue a message to be sent in the background"""
        self.send_queue.put(message)

    def start_threads(self):
        """Start all background threads"""
        # Start sender thread
        self.sender_thread = threading.Thread(target=self._sender_loop)
        self.sender_thread.daemon = True
        self.sender_thread.start()

        # Start receiver thread
        self.receiver_thread = threading.Thread(target=self._receiver_loop)
        self.receiver_thread.daemon = True
        self.receiver_thread.start()

        # Start reconnect thread
        self.reconnect_thread = threading.Thread(target=self._reconnect_loop)
        self.reconnect_thread.daemon = True
        self.reconnect_thread.start()

    def cleanup(self):
        """Clean up resources"""
        if self.handle:
            try:
                self.handle.releaseInterface(self.interface_num)
                self.handle.close()
            except libusb1.USBError:
                pass

        self.handle = None
        self.ep_in = None
        self.ep_out = None
        self.connected = False
        self.recv_buffer.reset()


def main():
    logger.info("Android USB Data Exchange")
    logger.info("------------------------")

    # Check OS and provide specific guidance
    import platform
    system = platform.system()
    if system == "Linux":
        logger.info(
            "NOTE: On Linux, you may need to run this script with sudo or set up udev rules.")
        logger.info(
            "To create a udev rule, create a file in /etc/udev/rules.d/51-android.rules with:")
        logger.info(
            "SUBSYSTEM==\"usb\", ATTR{idVendor}==\"[VENDOR_ID]\", MODE=\"0666\"")
    elif system == "Windows":
        logger.info(
            "NOTE: On Windows, ensure you have the correct USB drivers installed.")
        logger.info(
            "Try using Zadig (https://zadig.akeo.ie/) to install libusb drivers.")

    android_comm = AndroidUSBComm()

    logger.info("Searching for Android device...")

    # Try connecting multiple times
    max_attempts = 3
    for attempt in range(max_attempts):
        if attempt > 0:
            logger.info(f"\nRetry attempt {attempt}/{max_attempts-1}...")

        if android_comm.connect():
            logger.info("Connected to Android device!")

            # Start background threads
            android_comm.start_threads()

            logger.info("\nEnter messages to send (Ctrl+C to exit):")
            try:
                while android_comm.connected:
                    message = input("> ")
                    if message:
                        # Queue the message for sending in background
                        android_comm.queue_message(message)

                        if not android_comm.connected:
                            logger.info(
                                "Connection lost! Attempting to reconnect...")
                            android_comm.reconnect_event.set()
            except KeyboardInterrupt:
                logger.info("\nExiting...")
            finally:
                # Stop all threads
                android_comm.sender_running = False
                android_comm.receiver_running = False
                android_comm.reconnect_event.clear()

                # Join threads with timeout
                if hasattr(android_comm, 'sender_thread'):
                    android_comm.sender_thread.join(1.0)
                if hasattr(android_comm, 'receiver_thread'):
                    android_comm.receiver_thread.join(1.0)
                if hasattr(android_comm, 'reconnect_thread'):
                    android_comm.reconnect_thread.join(1.0)

                android_comm.cleanup()
                logger.info("Resources cleaned up")
            return

        # Wait before retrying
        if attempt < max_attempts - 1:
            logger.info("Waiting 2 seconds before retrying...")
            time.sleep(2)

    logger.error(
        "\nFailed to connect to Android device after multiple attempts")
    logger.info("\nTroubleshooting tips:")
    logger.info("1. Make sure USB debugging is enabled on your Android device")
    logger.info("2. Check the USB connection")
    logger.info("3. Try running the script with administrator/root privileges")
    logger.info("4. On Linux, you may need to set up udev rules (see above)")
    logger.info(
        "5. On Windows, you may need to install WinUSB drivers using Zadig")


if __name__ == "__main__":
    main()
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
