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
        private const ushort VK_5 = 0x35; // '5' = Insert Coin in Arcade/MAME/Teknoparrot
        private const ushort VK_R = 0x52; // 'R' = Reload in PC Shooters
        private const ushort VK_SPACE = 0x20;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern bool SetCursorPos(int X, int Y);
        #endregion

        private ViGEmClient? _vigemClient;
        private IXbox360Controller? _x360Controller;
        private bool _isVigemAvailable = false;

        private WinScreen _targetScreen = WinScreen.PrimaryScreen ?? WinScreen.AllScreens[0];
        private ushort _lastButtons = 0;
        private bool _lastTrackingLocked = false;

        public bool IsEnabled { get; set; } = true;
        public EmulatorProfile CurrentProfile { get; set; } = EmulatorProfile.Teknoparrot;
        public bool IsViGEmConnected => _isVigemAvailable;

        public int LastPixelX { get; private set; } = 0;
        public int LastPixelY { get; private set; } = 0;
        public bool IsLastFiring { get; private set; } = false;

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
                _x360Controller = _vigemClient.CreateXbox360Controller();
                _x360Controller.Connect();
                _isVigemAvailable = true;
                Console.WriteLine("[ViGEmBus] Virtual Xbox 360 Controller connected successfully.");
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

            bool isLocked = (pkt.Flags & (byte)LightgunFlags.TrackingLocked) != 0;
            bool isOffscreenReload = (pkt.Flags & (byte)LightgunFlags.OffscreenReload) != 0 
                                     || ((pkt.ButtonMask & (ushort)LightgunButtons.Reload) != 0);

            // Compute exact pixel coordinate on the selected monitor
            double normX = pkt.PointerX / 65535.0;
            double normY = pkt.PointerY / 65535.0;

            int pixelX = _targetScreen.Bounds.Left + (int)(normX * _targetScreen.Bounds.Width);
            int pixelY = _targetScreen.Bounds.Top + (int)(normY * _targetScreen.Bounds.Height);

            LastPixelX = pixelX;
            LastPixelY = pixelY;
            IsLastFiring = (pkt.ButtonMask & (ushort)LightgunButtons.TriggerL2) != 0;

            // 1. Mouse Injection
            if (isLocked)
            {
                if (CurrentProfile == EmulatorProfile.AaaPcGame)
                {
                    InjectAaaRelativeMouse(pixelX, pixelY, pkt.ButtonMask);
                }
                else
                {
                    InjectAbsoluteMouse(pixelX, pixelY, pkt.ButtonMask, isOffscreenReload);
                }
            }
            else if (isOffscreenReload)
            {
                InjectOffscreenReloadClick();
            }

            // 2. Keyboard Hotkey Emulation (Coin, Start, Reload)
            InjectKeyboardActions(pkt.ButtonMask, isOffscreenReload);

            // 3. ViGEm Gamepad Output
            if (_isVigemAvailable && _x360Controller != null)
            {
                InjectGamepad(pkt, isLocked, isOffscreenReload);
            }

            _lastButtons = pkt.ButtonMask;
            _lastTrackingLocked = isLocked;
        }

        private void InjectAbsoluteMouse(int pixelX, int pixelY, ushort buttons, bool isReload)
        {
            // Set cursor position directly to the exact target monitor pixel
            SetCursorPos(pixelX, pixelY);

            uint flags = 0;
            bool currentTrigger = (buttons & (ushort)LightgunButtons.TriggerL2) != 0;
            bool lastTrigger = (_lastButtons & (ushort)LightgunButtons.TriggerL2) != 0;

            if (currentTrigger && !lastTrigger) flags |= MOUSEEVENTF_LEFTDOWN;
            else if (!currentTrigger && lastTrigger) flags |= MOUSEEVENTF_LEFTUP;

            bool currentR2 = (buttons & (ushort)LightgunButtons.TriggerR2) != 0;
            bool lastR2 = (_lastButtons & (ushort)LightgunButtons.TriggerR2) != 0;

            if (currentR2 && !lastR2) flags |= MOUSEEVENTF_MIDDLEDOWN;
            else if (!currentR2 && lastR2) flags |= MOUSEEVENTF_MIDDLEUP;

            if (isReload)
            {
                flags |= MOUSEEVENTF_RIGHTDOWN;
            }

            if (flags != 0)
            {
                INPUT[] inputs = new INPUT[1];
                inputs[0].type = INPUT_MOUSE;
                inputs[0].u.mi.dwFlags = flags;
                SendInput(1, inputs, Marshal.SizeOf<INPUT>());
            }
        }

        private void InjectAaaRelativeMouse(int pixelX, int pixelY, ushort buttons)
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

            // Handle Buttons
            uint flags = 0;
            bool currentTrigger = (buttons & (ushort)LightgunButtons.TriggerL2) != 0;
            bool lastTrigger = (_lastButtons & (ushort)LightgunButtons.TriggerL2) != 0;

            if (currentTrigger && !lastTrigger) flags |= MOUSEEVENTF_LEFTDOWN;
            else if (!currentTrigger && lastTrigger) flags |= MOUSEEVENTF_LEFTUP;

            bool currentR2 = (buttons & (ushort)LightgunButtons.TriggerR2) != 0;
            bool lastR2 = (_lastButtons & (ushort)LightgunButtons.TriggerR2) != 0;

            if (currentR2 && !lastR2) flags |= MOUSEEVENTF_RIGHTDOWN;
            else if (!currentR2 && lastR2) flags |= MOUSEEVENTF_RIGHTUP;

            if (flags != 0)
            {
                INPUT[] inputs = new INPUT[1];
                inputs[0].type = INPUT_MOUSE;
                inputs[0].u.mi.dwFlags = flags;
                SendInput(1, inputs, Marshal.SizeOf<INPUT>());
            }
        }

        private void InjectKeyboardActions(ushort buttons, bool isReload)
        {
            // Start Button -> Key '1' (Player 1 Start)
            bool curStart = (buttons & (ushort)LightgunButtons.OptionsStart) != 0;
            bool lastStart = (_lastButtons & (ushort)LightgunButtons.OptionsStart) != 0;
            if (curStart && !lastStart) SendKey(VK_1, down: true);
            else if (!curStart && lastStart) SendKey(VK_1, down: false);

            // Select/Share Button -> Key '5' (Coin Insert)
            bool curSelect = (buttons & (ushort)LightgunButtons.SelectShare) != 0;
            bool lastSelect = (_lastButtons & (ushort)LightgunButtons.SelectShare) != 0;
            if (curSelect && !lastSelect) SendKey(VK_5, down: true);
            else if (!curSelect && lastSelect) SendKey(VK_5, down: false);

            // Reload Button -> Key 'R' (FPS / Arcade Reload)
            bool curReload = isReload || ((buttons & (ushort)LightgunButtons.Reload) != 0);
            bool lastReload = (_lastButtons & (ushort)LightgunButtons.Reload) != 0;
            if (curReload && !lastReload) SendKey(VK_R, down: true);
            else if (!curReload && lastReload) SendKey(VK_R, down: false);
        }

        private void SendKey(ushort vkCode, bool down)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].u.ki.wVk = vkCode;
            inputs[0].u.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
            SendInput(1, inputs, Marshal.SizeOf<INPUT>());
        }

        private void InjectOffscreenReloadClick()
        {
            // Authentic arcade off-screen shot: move to bottom edge of target monitor and click right
            int offX = _targetScreen.Bounds.Right - 10;
            int offY = _targetScreen.Bounds.Bottom - 10;
            SetCursorPos(offX, offY);

            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].u.mi.dwFlags = MOUSEEVENTF_RIGHTDOWN;

            inputs[1].type = INPUT_MOUSE;
            inputs[1].u.mi.dwFlags = MOUSEEVENTF_RIGHTUP;

            SendInput(2, inputs, Marshal.SizeOf<INPUT>());
        }

        private void InjectGamepad(LightgunInputPacket pkt, bool isLocked, bool isReload)
        {
            if (_x360Controller == null) return;

            ushort b = pkt.ButtonMask;

            // Action Buttons
            _x360Controller.SetButtonState(Xbox360Button.A, (b & (ushort)LightgunButtons.Cross) != 0);
            _x360Controller.SetButtonState(Xbox360Button.B, (b & (ushort)LightgunButtons.Circle) != 0 || isReload);
            _x360Controller.SetButtonState(Xbox360Button.X, (b & (ushort)LightgunButtons.Square) != 0);
            _x360Controller.SetButtonState(Xbox360Button.Y, (b & (ushort)LightgunButtons.Triangle) != 0);

            // D-Pad
            _x360Controller.SetButtonState(Xbox360Button.Up, (b & (ushort)LightgunButtons.DpadUp) != 0);
            _x360Controller.SetButtonState(Xbox360Button.Down, (b & (ushort)LightgunButtons.DpadDown) != 0);
            _x360Controller.SetButtonState(Xbox360Button.Left, (b & (ushort)LightgunButtons.DpadLeft) != 0);
            _x360Controller.SetButtonState(Xbox360Button.Right, (b & (ushort)LightgunButtons.DpadRight) != 0);

            // Menu Buttons
            _x360Controller.SetButtonState(Xbox360Button.Start, (b & (ushort)LightgunButtons.OptionsStart) != 0);
            _x360Controller.SetButtonState(Xbox360Button.Back, (b & (ushort)LightgunButtons.SelectShare) != 0);

            // Triggers (Analog 0 - 255)
            byte l2Value = (b & (ushort)LightgunButtons.TriggerL2) != 0 ? (byte)255 : (byte)0;
            byte r2Value = (b & (ushort)LightgunButtons.TriggerR2) != 0 ? (byte)255 : (byte)0;
            _x360Controller.SetSliderValue(Xbox360Slider.LeftTrigger, l2Value);
            _x360Controller.SetSliderValue(Xbox360Slider.RightTrigger, r2Value);

            // Analog Stick Mapping (GunCon 2 / GunCon 3)
            if (isLocked)
            {
                short stickX = (short)(pkt.PointerX - 32768);
                short stickY = (short)(32768 - pkt.PointerY);

                _x360Controller.SetAxisValue(Xbox360Axis.LeftThumbX, stickX);
                _x360Controller.SetAxisValue(Xbox360Axis.LeftThumbY, stickY);
            }

            _x360Controller.SubmitReport();
        }

        public void Dispose()
        {
            try
            {
                _x360Controller?.Disconnect();
                _vigemClient?.Dispose();
            }
            catch { }
        }
    }
}
