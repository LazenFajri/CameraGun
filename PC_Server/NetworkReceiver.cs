using System;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace CameraGun.Server
{
    public class NetworkReceiver : IDisposable
    {
        public const int DefaultPort = 8765;
        private UdpClient? _udpListener;
        private CancellationTokenSource? _cts;
        private readonly System.Collections.Concurrent.ConcurrentDictionary<IPEndPoint, DateTime> _connectedClients = new();

        public event Action<LightgunInputPacket>? PacketReceived;
        public event Action<string>? StatusChanged;
        public event Action? ConfigChannelReady;

        public bool IsConnected => System.Linq.Enumerable.Any(_connectedClients.Values, t => (DateTime.UtcNow - t).TotalSeconds < 3.0);
        public int ConnectedClientCount => System.Linq.Enumerable.Count(_connectedClients.Values, t => (DateTime.UtcNow - t).TotalSeconds < 3.0);
        public bool IsConfigReady => !_connectedClients.IsEmpty;
        public string LocalIpAddress { get; private set; } = "127.0.0.1";
        public int Port => DefaultPort;

        public void StartListening(int port = DefaultPort)
        {
            try
            {
                LocalIpAddress = GetLocalIPv4Address();
                _cts = new CancellationTokenSource();
                _udpListener = new UdpClient(new IPEndPoint(IPAddress.Any, port));
                _udpListener.EnableBroadcast = true;

                try
                {
                    const int SIO_UDP_CONNRESET = -1744830452;
                    _udpListener.Client.IOControl((IOControlCode)SIO_UDP_CONNRESET, new byte[] { 0, 0, 0, 0 }, null);
                }
                catch { }

                StatusChanged?.Invoke($"Wi-Fi Server: {LocalIpAddress}:{port} (Listening)");

                Task.Run(() => ReceiveLoop(_cts.Token), _cts.Token);
                Task.Run(() => AnnouncementLoop(_cts.Token), _cts.Token);
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Wi-Fi Error: {ex.Message}");
            }
        }

        private async Task AnnouncementLoop(CancellationToken token)
        {
            if (_udpListener == null) return;
            var bcastEp = new IPEndPoint(IPAddress.Broadcast, DefaultPort);

            while (!token.IsCancellationRequested)
            {
                try
                {
                    byte[] announceBytes = Encoding.ASCII.GetBytes($"CAMERAGUN_SERVER_ANNOUNCE:{LocalIpAddress}:{DefaultPort}");
                    await _udpListener.SendAsync(announceBytes, announceBytes.Length, bcastEp);
                    await Task.Delay(2500, token);
                }
                catch (OperationCanceledException) { break; }
                catch
                {
                    try { await Task.Delay(2500, token); } catch { break; }
                }
            }
        }

        private async Task ReceiveLoop(CancellationToken token)
        {
            if (_udpListener == null) return;

            while (!token.IsCancellationRequested)
            {
                try
                {
                    var result = await _udpListener.ReceiveAsync(token);
                    byte[] data = result.Buffer;
                    var remoteEp = result.RemoteEndPoint;

                    if (data == null || data.Length == 0) continue;

                    // 1. Auto-Discovery Broadcast
                    if (data.Length >= 18 && Encoding.ASCII.GetString(data, 0, 18) == "CAMERAGUN_DISCOVER")
                    {
                        byte[] response = Encoding.ASCII.GetBytes($"CAMERAGUN_SERVER_ACK:{LocalIpAddress}:{DefaultPort}");
                        await _udpListener.SendAsync(response, response.Length, remoteEp);
                        continue;
                    }

                    // 2. 16-byte Telemetry Input Packet
                    if (data.Length == 16 && data[0] == 0xAA)
                    {
                        byte crc = ComputeCrc8(data, 15);
                        if (data[15] == crc)
                        {
                            var pkt = ParsePacket(data);
                            bool isNewClient = !_connectedClients.ContainsKey(remoteEp);
                            _connectedClients[remoteEp] = DateTime.UtcNow;

                            if (isNewClient)
                            {
                                int clientNum = (pkt.Flags & (byte)LightgunFlags.Player2) != 0 ? 2 : 1;
                                StatusChanged?.Invoke($"Wi-Fi Client Terhubung: {remoteEp.Address} (P{clientNum})");
                                ConfigChannelReady?.Invoke();
                            }

                            PacketReceived?.Invoke(pkt);
                        }
                    }
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    // Ignore transient network errors
                    await Task.Delay(10, token);
                }
            }
        }

        public async Task<bool> SendConfigAsync(LightgunConfigPacket config)
        {
            if (_udpListener == null || _connectedClients.IsEmpty) return false;

            try
            {
                byte[] bytes = new byte[16];
                bytes[0] = 0xBB;
                bytes[1] = config.HMin;
                bytes[2] = config.SMin;
                bytes[3] = config.VMin;
                bytes[4] = config.HMax;
                bytes[5] = config.SMax;
                bytes[6] = config.VMax;

                BitConverter.GetBytes(config.TargetWidth).CopyTo(bytes, 7);
                BitConverter.GetBytes(config.TargetHeight).CopyTo(bytes, 9);
                bytes[11] = config.BorderThicknessPct;
                BitConverter.GetBytes(config.OneEuroMinCutoff).CopyTo(bytes, 12);
                bytes[14] = config.OneEuroBeta;
                bytes[15] = ComputeCrc8(bytes, 15);

                bool allSent = true;
                foreach (var clientEp in _connectedClients.Keys)
                {
                    try
                    {
                        int sent = await _udpListener.SendAsync(bytes, bytes.Length, clientEp);
                        if (sent != bytes.Length) allSent = false;
                    }
                    catch
                    {
                        allSent = false;
                    }
                }
                return allSent;
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"SendConfig Error: {ex.Message}");
                return false;
            }
        }

        private static LightgunInputPacket ParsePacket(byte[] data)
        {
            var pkt = new LightgunInputPacket
            {
                Header = data[0],
                Flags = data[1],
                PointerX = BitConverter.ToUInt16(data, 2),
                PointerY = BitConverter.ToUInt16(data, 4),
                ButtonMask = BitConverter.ToUInt16(data, 6),
                GyroPitch = BitConverter.ToInt16(data, 8),
                GyroRoll = BitConverter.ToInt16(data, 10),
                TimestampMs = BitConverter.ToUInt16(data, 12),
                TrackingConfidence = data[14],
                Checksum = data[15]
            };
            return pkt;
        }

        private static byte ComputeCrc8(byte[] data, int length)
        {
            byte crc = 0x00;
            for (int i = 0; i < length; i++)
            {
                byte extract = data[i];
                for (byte tempI = 8; tempI > 0; tempI--)
                {
                    byte sum = (byte)((crc ^ extract) & 0x01);
                    crc = (byte)(crc >> 1);
                    if (sum != 0) crc = (byte)(crc ^ 0x8C);
                    extract = (byte)(extract >> 1);
                }
            }
            return crc;
        }

        public static string GetLocalIPv4Address()
        {
            try
            {
                foreach (var netInterface in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (netInterface.OperationalStatus != OperationalStatus.Up ||
                        netInterface.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                        continue;

                    var ipProps = netInterface.GetIPProperties();
                    foreach (var addr in ipProps.UnicastAddresses)
                    {
                        if (addr.Address.AddressFamily == AddressFamily.InterNetwork)
                        {
                            string ip = addr.Address.ToString();
                            if (ip.StartsWith("192.168.") || ip.StartsWith("10.") || ip.StartsWith("172."))
                            {
                                return ip;
                            }
                        }
                    }
                }
            }
            catch { }
            return "127.0.0.1";
        }

        public void Dispose()
        {
            _cts?.Cancel();
            _udpListener?.Close();
            _udpListener?.Dispose();
        }
    }
}
