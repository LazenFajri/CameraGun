using System;
using System.Windows;
using WpfApplication = System.Windows.Application;

namespace CameraGun.Server
{
    public partial class App : WpfApplication
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            AppDomain.CurrentDomain.UnhandledException += (s, args) =>
            {
                var ex = args.ExceptionObject as Exception;
                System.Windows.MessageBox.Show(
                    $"Fatal Error: {ex?.Message}\n\n{ex?.StackTrace}",
                    "CameraGun AI - Critical Error",
                    MessageBoxButton.OK,
                    MessageBoxImage.Error);
            };

            DispatcherUnhandledException += (s, args) =>
            {
                System.Windows.MessageBox.Show(
                    $"UI Error: {args.Exception.Message}\n\n{args.Exception.StackTrace}",
                    "CameraGun AI - UI Error",
                    MessageBoxButton.OK,
                    MessageBoxImage.Error);
                args.Handled = true;
            };
        }
    }
}
