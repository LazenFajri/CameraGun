using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace CameraGun.Server
{
    internal static class Program
    {
        private static BorderOverlay? _overlay;
        private static BluetoothReceiver? _btReceiver;
        private static InputInjection? _inputInjection;

        private static int _packetCount = 0;
        private static DateTime _lastFpsTime = DateTime.UtcNow;

        [STAThread]
        private static void Main(string[] args)
        {
            Console.Title = "CameraGun PC Server - Sinden Clone Driver";
            Console.ForegroundColor = ConsoleColor.Cyan;
            Console.WriteLine("===============================================================");
            Console.WriteLine("    CAMERAGUN PC DRIVER & BORDER OVERLAY UTILITY (v1.0.0)      ");
            Console.WriteLine("===============================================================");
            Console.ResetColor();

            // 1. Inisialisasi Input Injection (Mouse + ViGEm)
            _inputInjection = new InputInjection();

            // 2. Inisialisasi Bluetooth Receiver
            _btReceiver = new BluetoothReceiver();
            _btReceiver.StatusChanged += status =>
            {
                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine($"[BT Status] {status}");
                Console.ResetColor();
            };

            _btReceiver.PacketReceived += OnPacketReceived;

            // 3. Jalankan Transparent Border Overlay pada Thread UI STA tersendiri
            Thread overlayThread = new Thread(() =>
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                _overlay = new BorderOverlay(Color.FromArgb(0, 255, 68), thickness: 14);
                Application.Run(_overlay);
            });
            overlayThread.SetApartmentState(ApartmentState.STA);
            overlayThread.IsBackground = true;
            overlayThread.Start();

            // Beri waktu sejenak agar window overlay selesai di-render
            Thread.Sleep(500);

            // 4. Mulai BLE Scan
            _btReceiver.StartBleListening();

            PrintHelpMenu();

            // 5. Konsol Loop untuk mengubah warna border secara langsung
            bool running = true;
            while (running)
            {
                if (Console.KeyAvailable)
                {
                    var key = Console.ReadKey(intercept: true).Key;
                    switch (key)
                    {
                        case ConsoleKey.D1:
                        case ConsoleKey.NumPad1:
                            ChangeBorderColor(Color.FromArgb(0, 255, 68), "Neon Green (#00FF44)");
                            break;

                        case ConsoleKey.D2:
                        case ConsoleKey.NumPad2:
                            ChangeBorderColor(Color.FromArgb(0, 229, 255), "Electric Cyan (#00E5FF)");
                            break;

                        case ConsoleKey.D3:
                        case ConsoleKey.NumPad3:
                            ChangeBorderColor(Color.FromArgb(255, 0, 102), "Vivid Magenta (#FF0066)");
                            break;

                        case ConsoleKey.D4:
                        case ConsoleKey.NumPad4:
                            ChangeBorderColor(Color.FromArgb(255, 255, 255), "Pure White (#FFFFFF)");
                            break;

                        case ConsoleKey.OemPlus:
                        case ConsoleKey.Add:
                            _overlay?.Invoke((Action)(() => _overlay.SetBorderThickness(18)));
                            Console.WriteLine("[Overlay] Ketebalan border dinaikkan (18px).");
                            SyncCalibrationToAndroid();
                            break;

                        case ConsoleKey.OemMinus:
                        case ConsoleKey.Subtract:
                            _overlay?.Invoke((Action)(() => _overlay.SetBorderThickness(8)));
                            Console.WriteLine("[Overlay] Ketebalan border diturunkan (8px).");
                            SyncCalibrationToAndroid();
                            break;

                        case ConsoleKey.C:
                            Console.Write("\nMasukkan nama Port COM Bluetooth (misal COM3): ");
                            string? port = Console.ReadLine()?.Trim();
                            if (!string.IsNullOrEmpty(port))
                            {
                                _btReceiver.StartComPortListening(port);
                            }
                            break;

                        case ConsoleKey.S:
                            SyncCalibrationToAndroid();
                            break;

                        case ConsoleKey.Q:
                        case ConsoleKey.Escape:
                            running = false;
                            break;
                    }
                }

                Thread.Sleep(50);
            }

            // Cleanup
            _btReceiver.Dispose();
            _inputInjection.Dispose();
            if (_overlay != null && !_overlay.IsDisposed)
            {
                _overlay.Invoke((Action)(() => _overlay.Close()));
            }
        }

        private static void ChangeBorderColor(Color color, string name)
        {
            if (_overlay == null) return;
            _overlay.Invoke((Action)(() => _overlay.SetBorderColor(color)));
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"[Overlay] Warna border diubah ke: {name}");
            Console.ResetColor();
            SyncCalibrationToAndroid();
        }

        private static void SyncCalibrationToAndroid()
        {
            if (_overlay == null || _btReceiver == null) return;

            var (hMin, sMin, vMin, hMax, sMax, vMax) = _overlay.GetHsvThresholds();

            Rectangle bounds = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1920, 1080);
            var config = new LightgunConfigPacket
            {
                Header = 0xBB,
                HMin = hMin,
                SMin = sMin,
                VMin = vMin,
                HMax = hMax,
                SMax = sMax,
                VMax = vMax,
                TargetWidth = (ushort)bounds.Width,
                TargetHeight = (ushort)bounds.Height,
                BorderThicknessPct = 2,
                OneEuroMinCutoff = 120, // 1.2 Hz
                OneEuroBeta = 50,       // 5.0
                Checksum = 0
            };

            Task.Run(async () =>
            {
                bool success = await _btReceiver.SendConfigAsync(config);
                if (success)
                {
                    Console.WriteLine($"[Sync] Kalibrasi HSV [{hMin}..{hMax}] berhasil dikirim ke Android.");
                }
            });
        }

        private static void OnPacketReceived(LightgunInputPacket pkt)
        {
            _inputInjection?.ProcessInputPacket(pkt);

            _packetCount++;
            var now = DateTime.UtcNow;
            if ((now - _lastFpsTime).TotalSeconds >= 1.0)
            {
                double hz = _packetCount / (now - _lastFpsTime).TotalSeconds;
                _packetCount = 0;
                _lastFpsTime = now;

                bool locked = (pkt.Flags & (byte)LightgunFlags.TrackingLocked) != 0;
                Console.Write($"\r[Stream] Rate: {hz:F0} Hz | X: {pkt.PointerX:D5} Y: {pkt.PointerY:D5} | Locked: {(locked ? "YES" : "NO ")} | Btn: 0x{pkt.ButtonMask:X4}   ");
            }
        }

        private static void PrintHelpMenu()
        {
            Console.WriteLine("\nKontrol Cepat Border Layar:");
            Console.WriteLine("  [1] Neon Green Border (#00FF44)");
            Console.WriteLine("  [2] Electric Cyan Border (#00E5FF)");
            Console.WriteLine("  [3] Vivid Magenta Border (#FF0066)");
            Console.WriteLine("  [4] Pure White Border (#FFFFFF)");
            Console.WriteLine("  [+] Tambah ketebalan border");
            Console.WriteLine("  [-] Kurangi ketebalan border");
            Console.WriteLine("  [S] Sync ulang konfigurasi ke HP");
            Console.WriteLine("  [C] Buka koneksi Bluetooth RFCOMM COM Port manual");
            Console.WriteLine("  [Q] Keluar aplikasi\n");
        }
    }
}
