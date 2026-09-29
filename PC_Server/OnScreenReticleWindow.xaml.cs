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

        public int PlayerId { get; set; } = 1;

        public OnScreenReticleWindow(int playerId = 1)
        {
            PlayerId = playerId;
            InitializeComponent();
            Loaded += (_, _) => ApplyPlayerTheme();
        }

        private void ApplyPlayerTheme()
        {
            if (PlayerId == 2)
            {
                var p2Brush = (System.Windows.Media.Brush)FindResource("AccentP2");
                outerRing.Stroke = p2Brush;
                armTop.Stroke = p2Brush;
                armBottom.Stroke = p2Brush;
                armLeft.Stroke = p2Brush;
                armRight.Stroke = p2Brush;
                centerDot.Fill = (System.Windows.Media.Brush)FindResource("AccentYellow");
                centerDot.Stroke = p2Brush;
                txtPlayerTag.Text = "2P";
                txtPlayerTag.Foreground = p2Brush;
                badgeBorder.BorderBrush = p2Brush;
            }
            else
            {
                var cyanBrush = (System.Windows.Media.Brush)FindResource("AccentCyan");
                var greenBrush = (System.Windows.Media.Brush)FindResource("AccentGreen");
                outerRing.Stroke = cyanBrush;
                armTop.Stroke = cyanBrush;
                armBottom.Stroke = cyanBrush;
                armLeft.Stroke = cyanBrush;
                armRight.Stroke = cyanBrush;
                centerDot.Fill = greenBrush;
                centerDot.Stroke = cyanBrush;
                txtPlayerTag.Text = "1P";
                txtPlayerTag.Foreground = cyanBrush;
                badgeBorder.BorderBrush = cyanBrush;
            }
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
                    outerRing.Stroke = System.Windows.Media.Brushes.Yellow;
                    centerDot.Fill = System.Windows.Media.Brushes.White;
                }
                else
                {
                    ApplyPlayerTheme();
                }
            });
        }
    }
}
