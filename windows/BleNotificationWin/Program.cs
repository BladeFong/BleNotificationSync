using System;
using System.Text;
using BleNotificationWin.Crypto;

namespace BleNotificationWin;

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();

        // Check for /autostart flag - if not present, show main window
        bool autoStart = args.Contains("--autostart", StringComparer.OrdinalIgnoreCase);

        Application.Run(new MainForm(!autoStart));
    }
}
