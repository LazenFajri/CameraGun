using System;
using System.Runtime.InteropServices;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace CameraGun.Server
{
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

        [StructLayout(LayoutKind.Explicit)]
        private struct INPUT_UNION
        {
            [FieldOffset(0)]
            public MOUSEINPUT mi;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public uint type;
            public INPUT_UNION u;
        }

        private const uint INPUT_MOUSE = 0;
        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
        #endregion

        private ViGEmClient? _vigemClient;
        private IXbox360Controller? _x360Controller;
        private bool _isVigemAvailable = false;

        private ushort _lastButtons = 0;
        private bool _lastTrackingLocked = false;

        public bool IsViGEmConnected => _isVigemAvailable;

        public InputInjection()
        {
            InitializeViGEm();
        }

        private void InitializeViGEm()
        {
            try
            {
                _vigemClient = new ViGEmClient();
                _x360Controller = _vigemClient.CreateXbox360Controller();
                _x360Controller.Connect();
                _isVigemAvailable = true;
                Console.WriteLine("[ViGEmBus] Virtual Xbox 360 Controller berhasil dihubungkan!");
            }
            catch (Exception ex)
            {
                _isVigemAvailable = false;
                Console.WriteLine($"[ViGEmBus] Driver ViGEmBus tidak terdeteksi ({ex.Message}).");
                Console.WriteLine("[ViGEmBus] Berjalan dalam mode Absolute Mouse Pointer saja.");
            }
        }

        public void ProcessInputPacket(LightgunInputPacket pkt)
        {
            bool isLocked = (pkt.Flags & (byte)LightgunFlags.TrackingLocked) != 0;
            bool isOffscreenReload = (pkt.Flags & (byte)LightgunFlags.OffscreenReload) != 0 
                                     || ((pkt.ButtonMask & (ushort)LightgunButtons.Reload) != 0);

            // 1. Injeksi Kursor Mouse Absolut (Teknoparrot, Dolphin, MAME)
            if (isLocked)
            {
                InjectAbsoluteMouse(pkt.PointerX, pkt.PointerY, pkt.ButtonMask, isOffscreenReload);
            }
            else if (isOffscreenReload)
            {
                // Off-screen reload click
                InjectOffscreenReloadClick();
            }

            // 2. Injeksi ViGEm Gamepad (Teknoparrot, PCSX2, RPCS3)
            if (_isVigemAvailable && _x360Controller != null)
            {
                InjectGamepad(pkt, isLocked, isOffscreenReload);
            }

            _lastButtons = pkt.ButtonMask;
            _lastTrackingLocked = isLocked;
        }

        private void InjectAbsoluteMouse(ushort normX, ushort normY, ushort buttons, bool isReload)
        {
            uint flags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE;

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

            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].u.mi.dx = normX;
            inputs[0].u.mi.dy = normY;
            inputs[0].u.mi.dwFlags = flags;
            inputs[0].u.mi.mouseData = 0;
            inputs[0].u.mi.time = 0;

            SendInput(1, inputs, Marshal.SizeOf<INPUT>());
        }

        private void InjectOffscreenReloadClick()
        {
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

            // Tombol Aksi
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

            // Analog Stick Mapping (Untuk emulator yang membutuhkan input joystick thumbstick)
            if (isLocked)
            {
                // Konversi 0..65535 ke rentang stick Xbox (-32768 s/d 32767)
                short stickX = (short)(pkt.PointerX - 32768);
                // Sumbu Y dibalik pada joystick standar
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
