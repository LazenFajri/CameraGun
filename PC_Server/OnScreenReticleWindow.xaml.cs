using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace CameraGun.Server
{
    public partial class OnScreenReticleWindow : Window
    {
        private const int WS_EX_TRANSPARENT = 0x00000020;
        private const int WS_EX_TOOLWINDOW = 0x00000080;
        private const int WS_EX_NOACTIVATE = 0x08000000;
        private const int GWL_EXSTYLE = -20;

        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hwnd, int index);

        [DllImport("user32.dll")]
        private static extern int SetWindowLong(IntPtr hwnd, int index, int newStyle);

        public OnScreenReticleWindow()
        {
            InitializeComponent();
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            var helper = new WindowInteropHelper(this);
            int currentStyle = GetWindowLong(helper.Handle, GWL_EXSTYLE);
            SetWindowLong(helper.Handle, GWL_EXSTYLE, currentStyle | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE);
        }

        public void UpdatePosition(int screenX, int screenY, bool isLocked, bool isFiring)
        {
            Dispatcher.InvokeAsync(() =>
            {
                if (!isLocked)
                {
                    if (Visibility != Visibility.Hidden) Visibility = Visibility.Hidden;
                    return;
                }

                if (Visibility != Visibility.Visible) Visibility = Visibility.Visible;
                Left = screenX - (Width / 2.0);
                Top = screenY - (Height / 2.0);

                if (isFiring)
                {
                    outerRing.Stroke = System.Windows.Media.Brushes.OrangeRed;
                    centerDot.Fill = System.Windows.Media.Brushes.Yellow;
                }
                else
                {
                    outerRing.Stroke = (System.Windows.Media.Brush)FindResource("AccentCyan");
                    centerDot.Fill = (System.Windows.Media.Brush)FindResource("AccentGreen");
                }
            });
        }
    }
}
