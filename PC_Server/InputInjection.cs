using System;
using System.Runtime.InteropServices;
using WinScreen = System.Windows.Forms.Screen;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace CameraGun.Server
{
    public enum EmulatorProfile
    {
        Teknoparrot,
        Dolphin,
        Pcsx2,
        Mame,
        Rpcs3,
        AaaPcGame
    }

    public class InputInjection : IDisposable
    {
        #region Win32 SendInput Structures & API
        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct INPUT_UNION
        {
            [FieldOffset(0)]
            public MOUSEINPUT mi;
            [FieldOffset(0)]
            public KEYBDINPUT ki;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public uint type;
            public INPUT_UNION u;
        }

        private const uint INPUT_MOUSE = 0;
        private const uint INPUT_KEYBOARD = 1;

        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
        private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;

        private const uint KEYEVENTF_KEYUP = 0x0002;

        private const ushort VK_1 = 0x31; // '1' = Player 1 Start in Arcade/MAME/Teknoparrot
        private const ushort VK_2 = 0x32; // '2' = Player 2 Start in Arcade/MAME/Teknoparrot
        private const ushort VK_5 = 0x35; // '5' = Insert Coin 1P
        private const ushort VK_6 = 0x36; // '6' = Insert Coin 2P
        private const ushort VK_R = 0x52; // 'R' = Player 1 Reload
        private const ushort VK_K = 0x4B; // 'K' = Player 2 Reload
        private const ushort VK_SPACE = 0x20;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern bool SetCursorPos(int X, int Y);
        #endregion

        private ViGEmClient? _vigemClient;
        private IXbox360Controller? _x360ControllerP1;
        private IXbox360Controller? _x360ControllerP2;
        private bool _isVigemAvailable = false;

        private WinScreen _targetScreen = WinScreen.PrimaryScreen ?? WinScreen.AllScreens[0];
        private ushort _lastButtonsP1 = 0;
        private ushort _lastButtonsP2 = 0;
        private bool _lastAnyReloadP1 = false;
        private bool _lastAnyReloadP2 = false;
        private bool _lastGyroOffscreenP1 = false;

        public bool IsEnabled { get; set; } = true;
        public bool IsMouseEnabled { get; set; } = true;
        public EmulatorProfile CurrentProfile { get; set; } = EmulatorProfile.Teknoparrot;
        public bool IsViGEmConnected => _isVigemAvailable;

        public double GyroSensitivity { get; set; } = 0.60;

        // Player 1 Coordinates & State
        public int LastPixelX_P1 { get; private set; } = 0;
        public int LastPixelY_P1 { get; private set; } = 0;
        public bool IsLastFiring_P1 { get; private set; } = false;

        // Player 2 Coordinates & State
        public int LastPixelX_P2 { get; private set; } = 0;
        public int LastPixelY_P2 { get; private set; } = 0;
        public bool IsLastFiring_P2 { get; private set; } = false;

        // Backwards compatibility aliases (P1 default)
        public int LastPixelX => LastPixelX_P1;
        public int LastPixelY => LastPixelY_P1;
        public bool IsLastFiring => IsLastFiring_P1;

        // AAA PC Game Mode (Relative Mouse)
        private int _prevAaaX = -1;
        private int _prevAaaY = -1;
        public double AaaSensitivity { get; set; } = 2.5;

        public InputInjection()
        {
            InitializeViGEm();
        }

        public void SetTargetScreen(WinScreen screen)
        {
            _targetScreen = screen;
            _prevAaaX = -1;
            _prevAaaY = -1;
        }

        private void InitializeViGEm()
        {
            try
            {
                _vigemClient = new ViGEmClient();

                // Player 1 Virtual Controller
                _x360ControllerP1 = _vigemClient.CreateXbox360Controller();
                _x360ControllerP1.Connect();

                // Player 2 Virtual Controller
                try
                {
                    _x360ControllerP2 = _vigemClient.CreateXbox360Controller();
                    _x360ControllerP2.Connect();
                    Console.WriteLine("[ViGEmBus] Dual Virtual Xbox 360 Controllers (1P & 2P) connected successfully.");
                }
                catch (Exception exP2)
                {
                    Console.WriteLine($"[ViGEmBus] P2 Controller warning: {exP2.Message}");
                }

                _isVigemAvailable = true;
            }
            catch (Exception ex)
            {
                _isVigemAvailable = false;
                Console.WriteLine($"[ViGEmBus] ViGEmBus driver not found: {ex.Message}. Running mouse-only mode.");
            }
        }

        public void ProcessInputPacket(LightgunInputPacket pkt)
        {
            if (!IsEnabled) return;

            bool isP2 = (pkt.Flags & (byte)LightgunFlags.Player2) != 0;
            bool isLocked = (pkt.Flags & (byte)LightgunFlags.TrackingLocked) != 0;

            // PENTING: Pisahkan gyro off-screen (HP miring ke bawah) dari tombol RELOAD di APK
            bool isGyroOffscreen = (pkt.Flags & (byte)LightgunFlags.OffscreenReload) != 0;
            bool isButtonReload = (pkt.ButtonMask & (ushort)LightgunButtons.Reload) != 0;
            bool isAnyReload = isGyroOffscreen || isButtonReload;

            // Compute exact pixel coordinate on the selected monitor scaled by GyroSensitivity around center
            double rawX = pkt.PointerX / 65535.0;
            double rawY = pkt.PointerY / 65535.0;

            double centeredX = (rawX - 0.5) * GyroSensitivity;
            double centeredY = (rawY - 0.5) * GyroSensitivity;

            double normX = Math.Clamp(0.5 + centeredX, 0.0, 1.0);
            double normY = Math.Clamp(0.5 + centeredY, 0.0, 1.0);

            int pixelX = _targetScreen.Bounds.Left + (int)(normX * _targetScreen.Bounds.Width);
            int pixelY = _targetScreen.Bounds.Top + (int)(normY * _targetScreen.Bounds.Height);
            bool isFiring = (pkt.ButtonMask & (ushort)LightgunButtons.TriggerL2) != 0;

            if (isP2)
            {
                LastPixelX_P2 = pixelX;
                LastPixelY_P2 = pixelY;
                IsLastFiring_P2 = isFiring;

                // P2 Keyboard Hotkeys ('2' = Start, '6' = Coin)
                InjectKeyboardActionsP2(pkt.ButtonMask, isAnyReload);

                // P2 Gamepad injection
                if (_isVigemAvailable && _x360ControllerP2 != null)
                {
                    InjectGamepad(_x360ControllerP2, pkt, isLocked, isAnyReload);
                }

                _lastButtonsP2 = pkt.ButtonMask;
                _lastAnyReloadP2 = isAnyReload;
            }
            else
            {
                LastPixelX_P1 = pixelX;
                LastPixelY_P1 = pixelY;
                IsLastFiring_P1 = isFiring;

                // === 1. MOUSE BUTTON CLICKS (SELALU AKTIF, tidak tergantung IsMouseEnabled) ===
                // Ini agar tombol Trigger, Reload, Alt-Fire di APK HP selalu berfungsi
                // bahkan saat mouse injection OFF (user sedang setting di TeknoParrot)
                InjectMouseButtons(pkt.ButtonMask, isAnyReload);

                // === 2. MOUSE CURSOR POSITION (hanya aktif saat IsMouseEnabled ON) ===
                if (IsMouseEnabled)
                {
                    if (isLocked)
                    {
                        // HP mengarah ke layar → pindahkan kursor ke titik bidik
                        if (CurrentProfile == EmulatorProfile.AaaPcGame)
                        {
                            InjectAaaRelativeMouse(pixelX, pixelY);
                        }
                        else
                        {
                            InjectAbsoluteMouse(pixelX, pixelY);
                        }
                    }
                    else if (isGyroOffscreen && !_lastGyroOffscreenP1)
                    {
                        // HP miring ke bawah (GYRO off-screen) → geser kursor ke pojok layar
                        // HANYA gyro, bukan tombol RELOAD (agar tombol RELOAD tidak menggeser kursor)
                        // Klik kanan sudah ditangani oleh InjectMouseButtons di atas
                        int offX = _targetScreen.Bounds.Right - 10;
                        int offY = _targetScreen.Bounds.Bottom - 10;
                        InjectAbsoluteMouse(offX, offY);
                    }
                }

                // === 3. KEYBOARD HOTKEYS ('1' = Start, '5' = Coin) ===
                InjectKeyboardActionsP1(pkt.ButtonMask, isAnyReload);

                // === 4. GAMEPAD INJECTION (ViGEmBus Xbox 360 virtual controller) ===
                if (_isVigemAvailable && _x360ControllerP1 != null)
                {
                    InjectGamepad(_x360ControllerP1, pkt, isLocked, isAnyReload);
                }

                _lastButtonsP1 = pkt.ButtonMask;
                _lastAnyReloadP1 = isAnyReload;
                _lastGyroOffscreenP1 = isGyroOffscreen;
            }
        }

        /// <summary>
        /// Kirim klik mouse ke Windows. SELALU aktif agar tombol di APK HP bisa terdeteksi
        /// oleh TeknoParrot/emulator bahkan saat mouse injection OFF (sedang setting).
        /// 
        /// Mapping:
        ///   TRIGGER L2 (di HP) → Left Mouse Click   (Tembak/Shoot)
        ///   TRIGGER R2 (di HP) → Middle Mouse Click  (Grenade/Alt-Fire)
        ///   RELOAD     (di HP) → Right Mouse Click   (Reload/Off-screen)
        ///   Gyro off-screen    → Right Mouse Click   (Reload otomatis dari sensor HP)
        /// </summary>
        private void InjectMouseButtons(ushort buttons, bool isAnyReload)
        {
            uint flags = 0;

            // Trigger L2 → Left Mouse Button (Shoot / Tembak)
            bool currentTrigger = (buttons & (ushort)LightgunButtons.TriggerL2) != 0;
            bool lastTrigger = (_lastButtonsP1 & (ushort)LightgunButtons.TriggerL2) != 0;
            if (currentTrigger && !lastTrigger) flags |= MOUSEEVENTF_LEFTDOWN;
            else if (!currentTrigger && lastTrigger) flags |= MOUSEEVENTF_LEFTUP;

            // Trigger R2 → Middle Mouse Button (Grenade / Alt-Fire)
            bool currentR2 = (buttons & (ushort)LightgunButtons.TriggerR2) != 0;
            bool lastR2 = (_lastButtonsP1 & (ushort)LightgunButtons.TriggerR2) != 0;
            if (currentR2 && !lastR2) flags |= MOUSEEVENTF_MIDDLEDOWN;
            else if (!currentR2 && lastR2) flags |= MOUSEEVENTF_MIDDLEUP;

            // Reload (tombol RELOAD di HP ATAU gyro off-screen) → Right Mouse Button
            if (isAnyReload && !_lastAnyReloadP1) flags |= MOUSEEVENTF_RIGHTDOWN;
            else if (!isAnyReload && _lastAnyReloadP1) flags |= MOUSEEVENTF_RIGHTUP;

            if (flags != 0)
            {
                INPUT[] inputs = new INPUT[1];
                inputs[0].type = INPUT_MOUSE;
                inputs[0].u.mi.dwFlags = flags;
                SendInput(1, inputs, Marshal.SizeOf<INPUT>());
            }
        }

        /// <summary>
        /// Mode AAA PC Game: gerakkan kursor secara relatif (seperti FPS mouse-look).
        /// Hanya menggerakkan posisi, tidak mengirim klik (sudah ditangani InjectMouseButtons).
        /// </summary>
        private void InjectAaaRelativeMouse(int pixelX, int pixelY)
        {
            if (_prevAaaX >= 0 && _prevAaaY >= 0)
            {
                int dx = (int)((pixelX - _prevAaaX) * AaaSensitivity);
                int dy = (int)((pixelY - _prevAaaY) * AaaSensitivity);

                if (dx != 0 || dy != 0)
                {
                    INPUT[] moveInputs = new INPUT[1];
                    moveInputs[0].type = INPUT_MOUSE;
                    moveInputs[0].u.mi.dx = dx;
                    moveInputs[0].u.mi.dy = dy;
                    moveInputs[0].u.mi.dwFlags = MOUSEEVENTF_MOVE;
                    SendInput(1, moveInputs, Marshal.SizeOf<INPUT>());
                }
            }

            _prevAaaX = pixelX;
            _prevAaaY = pixelY;
        }

        /// <summary>
        /// Injeksi mouse absolut yang menggerakkan kursor OS sekaligus mengirim stream event MOUSEEVENTF_MOVE.
        /// Ini memastikan game (DirectX, SDL3, TeknoParrot) mendeteksi pergerakan kursor secara terus-menerus
        /// bahkan saat pemain tidak sedang menekan tombol tembak (tidak flicker).
        /// </summary>
        private void InjectAbsoluteMouse(int pixelX, int pixelY)
        {
            // 1. Pixel-perfect cursor placement via Win32 API
            SetCursorPos(pixelX, pixelY);

            // 2. Dispatch real Windows input stream event (MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE)
            int vLeft = _targetScreen.Bounds.Left;
            int vTop = _targetScreen.Bounds.Top;
            int vWidth = Math.Max(1, _targetScreen.Bounds.Width);
            int vHeight = Math.Max(1, _targetScreen.Bounds.Height);

            int absX = (int)Math.Round(((double)(pixelX - vLeft) * 65535.0) / vWidth);
            int absY = (int)Math.Round(((double)(pixelY - vTop) * 65535.0) / vHeight);

            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].u.mi.dx = Math.Clamp(absX, 0, 65535);
            inputs[0].u.mi.dy = Math.Clamp(absY, 0, 65535);
            inputs[0].u.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
            SendInput(1, inputs, Marshal.SizeOf<INPUT>());
        }

        /// <summary>
        /// Keyboard hotkeys Player 1:
        ///   OPTIONS (di HP) → Keyboard '1' (Player 1 Start)
        ///   SHARE   (di HP) → Keyboard '5' (Insert Coin 1P)
        ///   RELOAD  (di HP) → Keyboard 'R' (HANYA di mode AAA PC Game, tidak di Teknoparrot)
        /// </summary>
        private void InjectKeyboardActionsP1(ushort buttons, bool isAnyReload)
        {
            // OPTIONS / Start → Key '1' (Player 1 Start / Teknoparrot P1 Start)
            bool curStart = (buttons & (ushort)LightgunButtons.OptionsStart) != 0;
            bool lastStart = (_lastButtonsP1 & (ushort)LightgunButtons.OptionsStart) != 0;
            if (curStart && !lastStart) SendKey(VK_1, down: true);
            else if (!curStart && lastStart) SendKey(VK_1, down: false);

            // SHARE / Select → Key '5' (Insert Coin 1P)
            bool curSelect = (buttons & (ushort)LightgunButtons.SelectShare) != 0;
            bool lastSelect = (_lastButtonsP1 & (ushort)LightgunButtons.SelectShare) != 0;
            if (curSelect && !lastSelect) SendKey(VK_5, down: true);
            else if (!curSelect && lastSelect) SendKey(VK_5, down: false);

            // RELOAD → Key 'R' HANYA di mode AAA PC Game (FPS)
            // Di Teknoparrot/Arcade, reload menggunakan Right Mouse Click, bukan huruf R
            // Ini yang sebelumnya menyebabkan huruf R muncul terus saat HP digerakkan
            if (CurrentProfile == EmulatorProfile.AaaPcGame)
            {
                bool curReload = isAnyReload;
                if (curReload && !_lastAnyReloadP1) SendKey(VK_R, down: true);
                else if (!curReload && _lastAnyReloadP1) SendKey(VK_R, down: false);
            }
        }

        /// <summary>
        /// Keyboard hotkeys Player 2:
        ///   OPTIONS → Keyboard '2' (Player 2 Start)
        ///   SHARE   → Keyboard '6' (Insert Coin 2P)
        ///   RELOAD  → Keyboard 'K' (HANYA di mode AAA PC Game)
        /// </summary>
        private void InjectKeyboardActionsP2(ushort buttons, bool isAnyReload)
        {
            bool curStart = (buttons & (ushort)LightgunButtons.OptionsStart) != 0;
            bool lastStart = (_lastButtonsP2 & (ushort)LightgunButtons.OptionsStart) != 0;
            if (curStart && !lastStart) SendKey(VK_2, down: true);
            else if (!curStart && lastStart) SendKey(VK_2, down: false);

            bool curSelect = (buttons & (ushort)LightgunButtons.SelectShare) != 0;
            bool lastSelect = (_lastButtonsP2 & (ushort)LightgunButtons.SelectShare) != 0;
            if (curSelect && !lastSelect) SendKey(VK_6, down: true);
            else if (!curSelect && lastSelect) SendKey(VK_6, down: false);

            if (CurrentProfile == EmulatorProfile.AaaPcGame)
            {
                bool curReload = isAnyReload;
                if (curReload && !_lastAnyReloadP2) SendKey(VK_K, down: true);
                else if (!curReload && _lastAnyReloadP2) SendKey(VK_K, down: false);
            }
        }

        private void SendKey(ushort vkCode, bool down)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].u.ki.wVk = vkCode;
            inputs[0].u.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
            SendInput(1, inputs, Marshal.SizeOf<INPUT>());
        }

        private void InjectGamepad(IXbox360Controller controller, LightgunInputPacket pkt, bool isLocked, bool isReload)
        {
            ushort b = pkt.ButtonMask;

            // Action Buttons
            controller.SetButtonState(Xbox360Button.A, (b & (ushort)LightgunButtons.Cross) != 0);
            controller.SetButtonState(Xbox360Button.B, (b & (ushort)LightgunButtons.Circle) != 0 || isReload);
            controller.SetButtonState(Xbox360Button.X, (b & (ushort)LightgunButtons.Square) != 0);
            controller.SetButtonState(Xbox360Button.Y, (b & (ushort)LightgunButtons.Triangle) != 0);

            // D-Pad
            controller.SetButtonState(Xbox360Button.Up, (b & (ushort)LightgunButtons.DpadUp) != 0);
            controller.SetButtonState(Xbox360Button.Down, (b & (ushort)LightgunButtons.DpadDown) != 0);
            controller.SetButtonState(Xbox360Button.Left, (b & (ushort)LightgunButtons.DpadLeft) != 0);
            controller.SetButtonState(Xbox360Button.Right, (b & (ushort)LightgunButtons.DpadRight) != 0);

            // Menu Buttons
            controller.SetButtonState(Xbox360Button.Start, (b & (ushort)LightgunButtons.OptionsStart) != 0);
            controller.SetButtonState(Xbox360Button.Back, (b & (ushort)LightgunButtons.SelectShare) != 0);

            // Triggers (Analog 0 - 255)
            byte l2Value = (b & (ushort)LightgunButtons.TriggerL2) != 0 ? (byte)255 : (byte)0;
            byte r2Value = (b & (ushort)LightgunButtons.TriggerR2) != 0 ? (byte)255 : (byte)0;
            controller.SetSliderValue(Xbox360Slider.LeftTrigger, l2Value);
            controller.SetSliderValue(Xbox360Slider.RightTrigger, r2Value);

            // Analog Stick Mapping (GunCon 2 / GunCon 3)
            if (isLocked)
            {
                short stickX = (short)Math.Clamp((pkt.PointerX - 32768) * GyroSensitivity, -32768, 32767);
                short stickY = (short)Math.Clamp((32768 - pkt.PointerY) * GyroSensitivity, -32768, 32767);

                controller.SetAxisValue(Xbox360Axis.LeftThumbX, stickX);
                controller.SetAxisValue(Xbox360Axis.LeftThumbY, stickY);
            }

            controller.SubmitReport();
        }

        /// <summary>
        /// Lepaskan semua tombol yang sedang ditekan. Dipanggil saat mouse injection di-OFF-kan (F8)
        /// agar tidak ada tombol yang nyangkut/stuck.
        /// </summary>
        public void ReleaseAllInputs()
        {
            try
            {
                INPUT[] mouseInputs = new INPUT[3];
                mouseInputs[0].type = INPUT_MOUSE;
                mouseInputs[0].u.mi.dwFlags = MOUSEEVENTF_LEFTUP;
                mouseInputs[1].type = INPUT_MOUSE;
                mouseInputs[1].u.mi.dwFlags = MOUSEEVENTF_RIGHTUP;
                mouseInputs[2].type = INPUT_MOUSE;
                mouseInputs[2].u.mi.dwFlags = MOUSEEVENTF_MIDDLEUP;
                SendInput(3, mouseInputs, Marshal.SizeOf<INPUT>());

                SendKey(VK_1, down: false);
                SendKey(VK_5, down: false);
                SendKey(VK_R, down: false);
                SendKey(VK_2, down: false);
                SendKey(VK_6, down: false);
                SendKey(VK_K, down: false);
            }
            catch { }

            _lastButtonsP1 = 0;
            _lastButtonsP2 = 0;
            _lastAnyReloadP1 = false;
            _lastAnyReloadP2 = false;
            _lastGyroOffscreenP1 = false;
        }

        public void Dispose()
        {
            try
            {
                _x360ControllerP1?.Disconnect();
                _x360ControllerP2?.Disconnect();
                _vigemClient?.Dispose();
            }
            catch { }
        }
    }
}
