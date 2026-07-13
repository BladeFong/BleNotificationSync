using System;
using System.Windows.Forms;

namespace BleNotificationWin;

public class MainForm : Form
{
    public MainForm()
    {
        Text = "BLE Notification Sync - Windows";
        Size = new System.Drawing.Size(800, 600);
        StartPosition = FormStartPosition.CenterScreen;

        var titleLabel = new Label
        {
            Text = "BLE Notification Sync",
            Font = new System.Drawing.Font("Segoe UI", 24, System.Drawing.FontStyle.Bold),
            AutoSize = true,
            Location = new System.Drawing.Point(50, 30)
        };

        var testButton = new Button
        {
            Text = "Test Encryption",
            Size = new System.Drawing.Size(150, 40),
            Location = new System.Drawing.Point(50, 100)
        };
        testButton.Click += (s, e) => Program.TestEncryption();

        Controls.Add(titleLabel);
        Controls.Add(testButton);
    }
}
