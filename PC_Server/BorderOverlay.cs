using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace CameraGun.Server
{
    public class BorderOverlay : Form
    {
        private Color _borderColor = Color.FromArgb(0, 255, 68); // Neon Green Default
        private int _borderThickness = 12; // Pixels
        private readonly Pen _borderPen;

        #region Win32 API Constants & Imports
        private const int WS_EX_TOPMOST = 0x00000008;
        private const int WS_EX_TRANSPARENT = 0x00000020;
        private const int WS_EX_LAYERED = 0x00080000;
        private const int WS_EX_TOOLWINDOW = 0x00000080;
        private const int WS_EX_NOACTIVATE = 0x08000000;

        private const int GWL_EXSTYLE = -20;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);

        [DllImport("user32.dll")]
        private static extern bool SetLayeredWindowAttributes(IntPtr hwnd, uint crKey, byte bAlpha, uint dwFlags);

        private const uint LWA_COLORKEY = 0x00000001;
        #endregion

        public BorderOverlay(Color initialColor, int thickness = 12)
        {
            _borderColor = initialColor;
            _borderThickness = thickness;
            _borderPen = new Pen(_borderColor, _borderThickness);

            // Configure Form for transparency and no borders
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.Manual;
            ShowInTaskbar = false;
            TopMost = true;

            // Fullscreen dimensions across primary screen
            Rectangle screenBounds = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1920, 1080);
            Location = screenBounds.Location;
            Size = screenBounds.Size;

            // Black background used as transparency color key
            BackColor = Color.Black;
            TransparencyKey = Color.Black;

            DoubleBuffered = true;
            SetStyle(ControlStyles.OptimizedDoubleBuffer |
                     ControlStyles.AllPaintingInWmPaint |
                     ControlStyles.UserPaint, true);

            string iconPath = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Resources", "app.ico");
            if (System.IO.File.Exists(iconPath))
            {
                try { Icon = new Icon(iconPath); } catch { }
            }
        }

        protected override CreateParams CreateParams
        {
            get
            {
                CreateParams cp = base.CreateParams;
                // Layered, click-through, topmost, tool window (hidden from Alt+Tab)
                cp.ExStyle |= WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE;
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            // Enforce transparent click-through at Win32 level
            int exStyle = GetWindowLong(Handle, GWL_EXSTYLE);
            SetWindowLong(Handle, GWL_EXSTYLE, exStyle | WS_EX_TRANSPARENT | WS_EX_LAYERED);
            SetLayeredWindowAttributes(Handle, 0x000000, 0, LWA_COLORKEY);
        }

        public void SetBorderColor(Color newColor)
        {
            _borderColor = newColor;
            _borderPen.Color = newColor;
            Invalidate();
        }

        public void SetBorderThickness(int thickness)
        {
            _borderThickness = thickness;
            _borderPen.Width = thickness;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            Graphics g = e.Graphics;

            // Gambar border persegi di seluruh tepi monitor
            float halfThick = _borderThickness / 2.0f;
            g.DrawRectangle(
                _borderPen,
                halfThick,
                halfThick,
                Width - _borderThickness,
                Height - _borderThickness
            );
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _borderPen?.Dispose();
            }
            base.Dispose(disposing);
        }

        /// <summary>
        /// Menghitung estimasi HSV range (OpenCV standard: H: 0-180, S: 0-255, V: 0-255)
        /// untuk disinkronkan ke HP Android via Bluetooth.
        /// </summary>
        public (byte hMin, byte sMin, byte vMin, byte hMax, byte sMax, byte vMax) GetHsvThresholds()
        {
            float hue = _borderColor.GetHue();        // 0 - 360
            float sat = _borderColor.GetSaturation(); // 0 - 1
            float val = _borderColor.GetBrightness(); // 0 - 1

            // Skala ke OpenCV: H: 0-180
            int openCvHue = (int)(hue / 2.0f);
            
            // Toleransi toleran untuk variasi kamera ponsel
            int hMin = Math.Max(0, openCvHue - 18);
            int hMax = Math.Min(180, openCvHue + 18);
            
            int sMin = Math.Max(60, (int)(sat * 255.0f * 0.5f));
            int sMax = 255;
            
            int vMin = Math.Max(70, (int)(val * 255.0f * 0.5f));
            int vMax = 255;

            // Khusus warna putih murni
            if (sat < 0.15f && val > 0.8f)
            {
                return (0, 0, 180, 180, 50, 255);
            }

            return ((byte)hMin, (byte)sMin, (byte)vMin, (byte)hMax, (byte)sMax, (byte)vMax);
        }
    }
}
