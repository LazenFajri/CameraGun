using System;
using System.IO.Ports;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace CameraGun.Server
{
    public class BluetoothReceiver : IDisposable
    {
        public static readonly Guid ServiceUuid = new Guid("e0a10001-1234-4a5b-9b8c-123456789abc");
        public static readonly Guid TelemetryCharUuid = new Guid("e0a10002-1234-4a5b-9b8c-123456789abc");
        public static readonly Guid ConfigCharUuid = new Guid("e0a10003-1234-4a5b-9b8c-123456789abc");

        private BluetoothLEAdvertisementWatcher? _bleWatcher;
        private BluetoothLEDevice? _connectedDevice;
        private GattCharacteristic? _telemetryChar;
        private GattCharacteristic? _configChar;

        private SerialPort? _comPortFallback;
        private Thread? _comThread;
        private volatile bool _isRunning = false;

        public event Action<LightgunInputPacket>? PacketReceived;
        public event Action<string>? StatusChanged;

        public bool IsConnected => (_connectedDevice != null && _connectedDevice.ConnectionStatus == BluetoothConnectionStatus.Connected) 
                                   || (_comPortFallback != null && _comPortFallback.IsOpen);

        public void StartBleListening()
        {
            StatusChanged?.Invoke("Memulai pencarian BLE Peripheral Android Lightgun...");

            _bleWatcher = new BluetoothLEAdvertisementWatcher
            {
                ScanningMode = BluetoothLEScanningMode.Active
            };

            _bleWatcher.Received += OnAdvertisementReceived;
            _bleWatcher.Start();
        }

        private async void OnAdvertisementReceived(BluetoothLEAdvertisementWatcher watcher, BluetoothLEAdvertisementReceivedEventArgs args)
        {
            // Cek apakah iklan mencantumkan Service UUID CameraGun kita
            bool hasMatchingService = false;
            foreach (var uuid in args.Advertisement.ServiceUuids)
            {
                if (uuid == ServiceUuid)
                {
                    hasMatchingService = true;
                    break;
                }
            }

            if (!hasMatchingService && !args.Advertisement.LocalName.Contains("CameraGun", StringComparison.OrdinalIgnoreCase))
            {
                return;
            }

            watcher.Stop();
            StatusChanged?.Invoke($"Ditemukan Device: {args.Advertisement.LocalName} ({args.BluetoothAddress:X}). Menghubungkan...");

            try
            {
                _connectedDevice = await BluetoothLEDevice.FromBluetoothAddressAsync(args.BluetoothAddress);
                if (_connectedDevice == null)
                {
                    StatusChanged?.Invoke("Gagal menghubungkan ke BLE Device.");
                    watcher.Start();
                    return;
                }

                _connectedDevice.ConnectionStatusChanged += (dev, e) =>
                {
                    StatusChanged?.Invoke($"Status BLE: {dev.ConnectionStatus}");
                    if (dev.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
                    {
                        watcher.Start();
                    }
                };

                var servicesResult = await _connectedDevice.GetGattServicesForUuidAsync(ServiceUuid);
                if (servicesResult.Status != GattCommunicationStatus.Success || servicesResult.Services.Count == 0)
                {
                    StatusChanged?.Invoke("Gatt Service tidak ditemukan.");
                    return;
                }

                var service = servicesResult.Services[0];

                // Dapatkan Karakteristik Telemetry
                var telemResult = await service.GetCharacteristicsForUuidAsync(TelemetryCharUuid);
                if (telemResult.Status == GattCommunicationStatus.Success && telemResult.Characteristics.Count > 0)
                {
                    _telemetryChar = telemResult.Characteristics[0];
                    _telemetryChar.ValueChanged += OnTelemetryValueChanged;
                    await _telemetryChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                        GattClientCharacteristicConfigurationDescriptorValue.Notify);
                    StatusChanged?.Invoke("Stream Telemetry aktif (Notifikasi 60-120Hz).");
                }

                // Dapatkan Karakteristik Config
                var cfgResult = await service.GetCharacteristicsForUuidAsync(ConfigCharUuid);
                if (cfgResult.Status == GattCommunicationStatus.Success && cfgResult.Characteristics.Count > 0)
                {
                    _configChar = cfgResult.Characteristics[0];
                    StatusChanged?.Invoke("Saluran konfigurasi border siap.");
                }
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Error koneksi BLE: {ex.Message}");
                watcher.Start();
            }
        }

        private void OnTelemetryValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
        {
            var reader = DataReader.FromBuffer(args.CharacteristicValue);
            byte[] bytes = new byte[reader.UnconsumedBufferLength];
            reader.ReadBytes(bytes);

            ProcessRawBytes(bytes);
        }

        public async Task<bool> SendConfigAsync(LightgunConfigPacket config)
        {
            if (_configChar == null) return false;

            byte[] packetBytes = PacketUtils.SerializeConfig(config);
            var writer = new DataWriter();
            writer.WriteBytes(packetBytes);

            var result = await _configChar.WriteValueAsync(writer.DetachBuffer(), GattWriteOption.WriteWithoutResponse);
            return result == GattCommunicationStatus.Success;
        }

        /// <summary>
        /// Mode cadangan: Membaca melalui COM Port Virtual Bluetooth (RFCOMM SPP)
        /// </summary>
        public void StartComPortListening(string portName, int baudRate = 115200)
        {
            try
            {
                _comPortFallback = new SerialPort(portName, baudRate)
                {
                    ReadTimeout = 1000
                };
                _comPortFallback.Open();
                _isRunning = true;

                _comThread = new Thread(ComReadLoop)
                {
                    IsBackground = true,
                    Priority = ThreadPriority.Highest
                };
                _comThread.Start();
                StatusChanged?.Invoke($"Mendengarkan RFCOMM pada {portName}");
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Gagal membuka COM port: {ex.Message}");
            }
        }

        private void ComReadLoop()
        {
            byte[] buffer = new byte[16];
            while (_isRunning && _comPortFallback != null && _comPortFallback.IsOpen)
            {
                try
                {
                    // Sinkronisasi byte header 0xAA
                    int b = _comPortFallback.ReadByte();
                    if (b != 0xAA) continue;

                    buffer[0] = 0xAA;
                    int read = 1;
                    while (read < 16)
                    {
                        read += _comPortFallback.Read(buffer, read, 16 - read);
                    }

                    ProcessRawBytes(buffer);
                }
                catch (TimeoutException) { }
                catch (Exception ex)
                {
                    StatusChanged?.Invoke($"COM Read Error: {ex.Message}");
                    break;
                }
            }
        }

        private void ProcessRawBytes(byte[] bytes)
        {
            if (bytes.Length != 16) return;

            // Validasi Header dan CRC8
            if (bytes[0] != 0xAA) return;

            byte expectedCrc = PacketUtils.ComputeCrc8(bytes.AsSpan(0, 15), 15);
            if (bytes[15] != expectedCrc) return;

            GCHandle handle = GCHandle.Alloc(bytes, GCHandleType.Pinned);
            try
            {
                var packet = Marshal.PtrToStructure<LightgunInputPacket>(handle.AddrOfPinnedObject());
                PacketReceived?.Invoke(packet);
            }
            finally
            {
                handle.Free();
            }
        }

        public void Dispose()
        {
            _isRunning = false;
            _bleWatcher?.Stop();
            _connectedDevice?.Dispose();
            _comPortFallback?.Close();
            _comPortFallback?.Dispose();
        }
    }
}
