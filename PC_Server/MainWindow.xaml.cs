using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using DrawingColor = System.Drawing.Color;
using WinFormsApp = System.Windows.Forms.Application;
using NotifyIcon = System.Windows.Forms.NotifyIcon;
using WinScreen = System.Windows.Forms.Screen;

namespace CameraGun.Server
{
    public partial class MainWindow : Window
    {
        private BorderOverlay? _overlay;
        private Thread? _overlayThread;
        private BluetoothReceiver? _btReceiver;
        private InputInjection? _inputInjection;
        private NotifyIcon? _trayIcon;
        private DispatcherTimer? _uiTimer;

        private DrawingColor _currentBorderColor = DrawingColor.FromArgb(0, 229, 255);
        private int _borderThickness = 12;
        private bool _borderVisible = true;

        private int _packetCount = 0;
        private DateTime _lastFpsTime = DateTime.UtcNow;
        private LightgunInputPacket _lastPacket;
        private double _streamHz = 0;

        public MainWindow()
        {
            InitializeComponent();
            Loaded += MainWindow_Loaded;
            Closing += MainWindow_Closing;
            pointerCanvas.SizeChanged += PointerCanvas_SizeChanged;
        }

        private void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            InitializeInputInjection();
            StartBorderOverlay();
            InitializeBluetooth();
            InitializeSystemTray();
            StartUiTimer();
            txtStatusMsg.Text = "Ready — waiting for phone connection...";
        }

        // ===========================================================
        //  INITIALIZATION
        // ===========================================================

        private void InitializeInputInjection()
        {
            try
            {
                _inputInjection = new InputInjection();
                if (_inputInjection.IsViGEmConnected)
                {
                    txtVigemStatus.Text = "ViGEm: ✓ OK";
                    txtVigemStatus.Foreground = FindResource("AccentGreen") as System.Windows.Media.Brush;
                    badgeVigem.BorderBrush = FindResource("AccentGreen") as System.Windows.Media.Brush;
                    btnVigemDownload.Visibility = Visibility.Collapsed;
                }
                else
                {
                    txtVigemStatus.Text = "ViGEm: ✗ Missing";
                    txtVigemStatus.Foreground = FindResource("AccentOrange") as System.Windows.Media.Brush;
                    badgeVigem.BorderBrush = FindResource("AccentOrange") as System.Windows.Media.Brush;
                }
            }
            catch (Exception ex)
            {
                txtStatusMsg.Text = $"InputInjection Error: {ex.Message}";
            }
        }

        private void StartBorderOverlay()
        {
            _overlayThread = new Thread(() =>
            {
                _overlay = new BorderOverlay(_currentBorderColor, _borderThickness);
                WinFormsApp.Run(_overlay);
            });
            _overlayThread.SetApartmentState(ApartmentState.STA);
            _overlayThread.IsBackground = true;
            _overlayThread.Start();
        }

        private void InitializeBluetooth()
        {
            try
            {
                _btReceiver = new BluetoothReceiver();

                _btReceiver.StatusChanged += status =>
                {
                    Dispatcher.BeginInvoke(() =>
                    {
                        if (txtStatusMsg != null) txtStatusMsg.Text = status;

                        if (_btReceiver != null && _btReceiver.IsConnected)
                        {
                            txtBtStatus.Text = "CONNECTED";
                            txtBtStatus.Foreground = FindResource("AccentGreen") as System.Windows.Media.Brush;
                            dotBt.Fill = FindResource("AccentGreen") as System.Windows.Media.Brush;
                            badgeBt.BorderBrush = FindResource("AccentGreen") as System.Windows.Media.Brush;
                            SyncCalibrationToAndroid();
                        }
                        else
                        {
                            txtBtStatus.Text = "SEARCHING...";
                            txtBtStatus.Foreground = FindResource("AccentOrange") as System.Windows.Media.Brush;
                            dotBt.Fill = FindResource("AccentOrange") as System.Windows.Media.Brush;
                            badgeBt.BorderBrush = FindResource("AccentOrange") as System.Windows.Media.Brush;
                        }
                    });
                };

                _btReceiver.PacketReceived += OnPacketReceived;
                _btReceiver.StartBleListening();
            }
            catch (Exception ex)
            {
                if (txtStatusMsg != null) txtStatusMsg.Text = $"BT Error: {ex.Message}";
            }
        }

        private void InitializeSystemTray()
        {
            _trayIcon = new NotifyIcon
            {
                Text = "CameraGun AI Server",
                Visible = false
            };

            string iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Resources", "app.ico");
            if (File.Exists(iconPath))
            {
                try { _trayIcon.Icon = new System.Drawing.Icon(iconPath); } catch { }
            }
            else
            {
                _trayIcon.Icon = System.Drawing.SystemIcons.Application;
            }

            var menu = new System.Windows.Forms.ContextMenuStrip();
            menu.Items.Add("Show Window", null, (_, _) => RestoreFromTray());
            menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
            menu.Items.Add("Exit", null, (_, _) => { _trayIcon.Visible = false; Close(); });
            _trayIcon.ContextMenuStrip = menu;
            _trayIcon.DoubleClick += (_, _) => RestoreFromTray();
        }

        private void StartUiTimer()
        {
            _uiTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
            _uiTimer.Tick += (_, _) =>
            {
                txtClock.Text = DateTime.Now.ToString("HH:mm:ss");
                UpdatePointerVisualization();
            };
            _uiTimer.Start();
        }

        // ===========================================================
        //  PACKET PROCESSING
        // ===========================================================

        private void OnPacketReceived(LightgunInputPacket pkt)
        {
            _lastPacket = pkt;
            _inputInjection?.ProcessInputPacket(pkt);

            _packetCount++;
            var now = DateTime.UtcNow;
            double elapsed = (now - _lastFpsTime).TotalSeconds;
            if (elapsed >= 0.5)
            {
                _streamHz = _packetCount / elapsed;
                _packetCount = 0;
                _lastFpsTime = now;
            }

            Dispatcher.BeginInvoke(DispatcherPriority.Render, () => UpdateDashboard(pkt));
        }

        private void UpdateDashboard(LightgunInputPacket pkt)
        {
            float normX = pkt.PointerX / 65535.0f;
            float normY = pkt.PointerY / 65535.0f;
            bool locked = (pkt.Flags & (byte)LightgunFlags.TrackingLocked) != 0;

            txtCoordX.Text = $"X: {normX:F4}";
            txtCoordY.Text = $"Y: {normY:F4}";
            txtConfidence.Text = $"CONF: {pkt.TrackingConfidence}%";
            txtStreamRate.Text = $"{_streamHz:F0} Hz";
            txtPitch.Text = $"P: {pkt.GyroPitch / 100.0:F1}°";
            txtRoll.Text = $"R: {pkt.GyroRoll / 100.0:F1}°";

            if (locked)
            {
                txtTrackingBadge.Text = "● TARGET LOCKED";
                txtTrackingBadge.Foreground = FindResource("AccentGreen") as System.Windows.Media.Brush;
            }
            else
            {
                txtTrackingBadge.Text = "● SEARCHING";
                txtTrackingBadge.Foreground = FindResource("AccentOrange") as System.Windows.Media.Brush;
            }

            UpdateButtonIndicator(indL2, (pkt.ButtonMask & (ushort)LightgunButtons.TriggerL2) != 0);
            UpdateButtonIndicator(indR2, (pkt.ButtonMask & (ushort)LightgunButtons.TriggerR2) != 0);
            UpdateButtonIndicator(indCross, (pkt.ButtonMask & (ushort)LightgunButtons.Cross) != 0);
            UpdateButtonIndicator(indCircle, (pkt.ButtonMask & (ushort)LightgunButtons.Circle) != 0);
            UpdateButtonIndicator(indReload, (pkt.ButtonMask & (ushort)LightgunButtons.Reload) != 0);
        }

        private void UpdateButtonIndicator(System.Windows.Controls.Border indicator, bool active)
        {
            indicator.Background = active
                ? (FindResource("AccentCyan") as System.Windows.Media.Brush)
                : (FindResource("BorderDim") as System.Windows.Media.Brush);
        }

        private void UpdatePointerVisualization()
        {
            if (pointerCanvas == null || pointerDot == null || pointerRing == null || crossH == null || crossV == null) return;

            double cw = pointerCanvas.ActualWidth;
            double ch = pointerCanvas.ActualHeight;
            if (cw < 10 || ch < 10) return;

            float normX = _lastPacket.PointerX / 65535.0f;
            float normY = _lastPacket.PointerY / 65535.0f;

            double px = normX * cw;
            double py = normY * ch;

            Canvas.SetLeft(pointerDot, px - 7);
            Canvas.SetTop(pointerDot, py - 7);
            Canvas.SetLeft(pointerRing, px - 15);
            Canvas.SetTop(pointerRing, py - 15);

            crossH.X1 = 0; crossH.X2 = cw;
            crossH.Y1 = py; crossH.Y2 = py;
            crossV.X1 = px; crossV.X2 = px;
            crossV.Y1 = 0; crossV.Y2 = ch;
        }

        private void PointerCanvas_SizeChanged(object sender, SizeChangedEventArgs e)
        {
            UpdatePointerVisualization();
        }

        // ===========================================================
        //  BORDER CONTROL HANDLERS
        // ===========================================================

        private void PresetColor_Click(object sender, RoutedEventArgs e)
        {
            if (sender is System.Windows.Controls.Button btn && btn.Tag is string hexStr)
            {
                ApplyBorderColor(hexStr);
            }
        }

        private void BtnApplyHex_Click(object sender, RoutedEventArgs e)
        {
            string hex = txtHexColor.Text.Trim();
            if (hex.Length == 6)
            {
                ApplyBorderColor("#" + hex);
            }
        }

        private void ApplyBorderColor(string hexColor)
        {
            try
            {
                var mediaColor = (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(hexColor);
                _currentBorderColor = DrawingColor.FromArgb(mediaColor.R, mediaColor.G, mediaColor.B);
                previewBrush.Color = mediaColor;
                txtHexColor.Text = $"{mediaColor.R:X2}{mediaColor.G:X2}{mediaColor.B:X2}";

                if (_overlay != null && !_overlay.IsDisposed)
                {
                    _overlay.Invoke((Action)(() => _overlay.SetBorderColor(_currentBorderColor)));
                }

                SyncCalibrationToAndroid();
                txtStatusMsg.Text = $"Border color updated to {hexColor}";
            }
            catch (Exception ex)
            {
                txtStatusMsg.Text = $"Invalid color: {ex.Message}";
            }
        }

        private void SliderThickness_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            int thick = (int)e.NewValue;
            _borderThickness = thick;
            if (txtThicknessVal != null) txtThicknessVal.Text = thick.ToString();

            if (_overlay != null && !_overlay.IsDisposed)
            {
                _overlay.Invoke((Action)(() => _overlay.SetBorderThickness(thick)));
            }
        }

        private void BtnToggleBorder_Click(object sender, RoutedEventArgs e)
        {
            _borderVisible = !_borderVisible;
            btnToggleBorder.Content = _borderVisible ? "⊘  HIDE BORDER" : "⊕  SHOW BORDER";

            if (_overlay != null && !_overlay.IsDisposed)
            {
                _overlay.Invoke((Action)(() =>
                {
                    if (_borderVisible) _overlay.Show();
                    else _overlay.Hide();
                }));
            }
        }

        // ===========================================================
        //  SETTINGS HANDLERS
        // ===========================================================

        private void SliderCutoff_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (txtCutoffVal != null) txtCutoffVal.Text = e.NewValue.ToString("F2");
        }

        private void SliderBeta_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (txtBetaVal != null) txtBetaVal.Text = e.NewValue.ToString("F3");
        }

        private void EmulatorProfile_Changed(object sender, RoutedEventArgs e)
        {
            if (sender is System.Windows.Controls.RadioButton rb)
            {
                if (txtStatusMsg != null)
                {
                    txtStatusMsg.Text = $"Emulator profile: {rb.Content}";
                }
            }
        }

        private void BtnVigemDownload_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "https://github.com/nefarius/ViGEmBus/releases",
                    UseShellExecute = true
                });
            }
            catch { }
        }

        private void BtnSyncCalibration_Click(object sender, RoutedEventArgs e)
        {
            SyncCalibrationToAndroid();
        }

        private void SyncCalibrationToAndroid()
        {
            if (_btReceiver == null || _overlay == null) return;

            var (hMin, sMin, vMin, hMax, sMax, vMax) = _overlay.GetHsvThresholds();
            var bounds = WinScreen.PrimaryScreen?.Bounds ?? new System.Drawing.Rectangle(0, 0, 1920, 1080);

            ushort scaledCutoff = (ushort)(sliderCutoff.Value * 100);
            byte scaledBeta = (byte)(sliderBeta.Value * 100);

            var config = new LightgunConfigPacket
            {
                Header = 0xBB,
                HMin = hMin, SMin = sMin, VMin = vMin,
                HMax = hMax, SMax = sMax, VMax = vMax,
                TargetWidth = (ushort)bounds.Width,
                TargetHeight = (ushort)bounds.Height,
                BorderThicknessPct = 2,
                OneEuroMinCutoff = scaledCutoff,
                OneEuroBeta = scaledBeta,
                Checksum = 0
            };

            Task.Run(async () =>
            {
                bool ok = await _btReceiver.SendConfigAsync(config);
                await Dispatcher.InvokeAsync(() =>
                {
                    txtStatusMsg.Text = ok
                        ? $"Calibration synced: HSV [{hMin}..{hMax}]"
                        : "Sync failed — phone not connected";
                });
            });
        }

        // ===========================================================
        //  WINDOW CHROME & TRAY
        // ===========================================================

        private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ClickCount == 2) BtnMaximize_Click(sender, e);
            else DragMove();
        }

        private void BtnMinimize_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState.Minimized;
        }

        private void BtnMaximize_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState == WindowState.Maximized
                ? WindowState.Normal
                : WindowState.Maximized;
        }

        private void BtnClose_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private void BtnTutorial_Click(object sender, RoutedEventArgs e)
        {
            if (modalTutorial != null) modalTutorial.Visibility = Visibility.Visible;
        }

        private void BtnCloseTutorial_Click(object sender, RoutedEventArgs e)
        {
            if (modalTutorial != null) modalTutorial.Visibility = Visibility.Collapsed;
        }

        private void BtnMinToTray_Click(object sender, RoutedEventArgs e)
        {
            if (_trayIcon != null)
            {
                _trayIcon.Visible = true;
                Hide();
                _trayIcon.ShowBalloonTip(2000, "CameraGun AI",
                    "Server minimized to tray. Double-click to restore.",
                    System.Windows.Forms.ToolTipIcon.Info);
            }
        }

        private void RestoreFromTray()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
            if (_trayIcon != null) _trayIcon.Visible = false;
        }

        // ===========================================================
        //  CLEANUP
        // ===========================================================

        private void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
        {
            _uiTimer?.Stop();
            _btReceiver?.Dispose();
            _inputInjection?.Dispose();

            if (_trayIcon != null)
            {
                _trayIcon.Visible = false;
                _trayIcon.Dispose();
            }

            if (_overlay != null && !_overlay.IsDisposed)
            {
                try { _overlay.Invoke((Action)(() => _overlay.Close())); } catch { }
            }
        }
    }
}
