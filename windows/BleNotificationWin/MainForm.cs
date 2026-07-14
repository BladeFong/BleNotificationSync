using System;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;
using BleNotificationWin.Gatt;
using BleNotificationWin.Notification;
using BleNotificationWin.Storage;
using Microsoft.Win32;

namespace BleNotificationWin;

public class MainForm : Form
{
    private NotifyIcon _trayIcon = null!;
    private ContextMenuStrip _trayMenu = null!;
    private GattServerService _gattServer = null!;
    private NotificationManager _notificationManager = null!;
    private PairingStorage _pairingStorage = null!;
    private KeyStorage _keyStorage = null!;

    // UI controls
    private Label _statusLabel = null!;
    private Label _connectionLabel = null!;
    private Button _startButton = null!;
    private Button _stopButton = null!;
    private Button _pairButton = null!;
    private TextBox _logBox = null!;

    // Timer for periodic status refresh
    private System.Windows.Forms.Timer _statusTimer = null!;

    // Icon buffer for multi-frame icon transfers
    private readonly Dictionary<string, MemoryStream> _iconBuffers = new();
    private readonly Dictionary<string, int> _iconExpectedFrames = new();

    // Language support
    private static bool IsChinese => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "zh";

    // Exit flag
    private bool _isExiting;

    // Auto-start setting
    private const string AutoStartRegPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string AppName = "BleNotificationSync";

    public MainForm(bool showWindow = true)
    {
        InitializeComponents();
        InitializeTray();
        InitializeServices();

        if (!showWindow)
        {
            WindowState = FormWindowState.Minimized;
            Hide();
        }
    }

    private void InitializeComponents()
    {
        Text = IsChinese ? "BLE 通知同步" : "BLE Notification Sync";
        Size = new Size(600, 500);
        MinimumSize = new Size(500, 400);
        StartPosition = FormStartPosition.CenterScreen;
        ShowInTaskbar = false;
        WindowState = FormWindowState.Minimized;
        Icon = CreateBellIcon();

        // Title
        var titleLabel = new Label
        {
            Text = IsChinese ? "BLE 通知同步" : "BLE Notification Sync",
            Font = new Font("Segoe UI", 14, FontStyle.Bold),
            AutoSize = true,
            Dock = DockStyle.Top,
            Padding = new Padding(10, 10, 10, 5)
        };

        // Status panel
        var statusPanel = new Panel
        {
            Dock = DockStyle.Top,
            Height = 60,
            Padding = new Padding(10, 5, 10, 5)
        };

        _statusLabel = new Label
        {
            Text = IsChinese ? "状态: 初始化中..." : "Status: Initializing...",
            Font = new Font("Segoe UI", 10),
            AutoSize = true,
            Location = new Point(10, 5)
        };

        _connectionLabel = new Label
        {
            Text = IsChinese ? "连接数: 0" : "Connections: 0",
            Font = new Font("Segoe UI", 10),
            AutoSize = true,
            Location = new Point(10, 30)
        };

        statusPanel.Controls.Add(_statusLabel);
        statusPanel.Controls.Add(_connectionLabel);

        // Button panel
        var buttonPanel = new Panel
        {
            Dock = DockStyle.Top,
            Height = 50,
            Padding = new Padding(10, 5, 10, 5)
        };

        _startButton = new Button
        {
            Text = IsChinese ? "启动服务" : "Start",
            Size = new Size(120, 35),
            Location = new Point(10, 5),
            Enabled = true
        };
        _startButton.Click += (s, e) => StartServer();

        _stopButton = new Button
        {
            Text = IsChinese ? "停止服务" : "Stop",
            Size = new Size(120, 35),
            Location = new Point(140, 5),
            Enabled = false
        };
        _stopButton.Click += (s, e) => StopServer();

        _pairButton = new Button
        {
            Text = IsChinese ? "扫码绑定" : "Pair Device",
            Size = new Size(120, 35),
            Location = new Point(270, 5),
            Enabled = true
        };
        _pairButton.Click += (s, e) => ShowPairDialog();

        buttonPanel.Controls.Add(_startButton);
        buttonPanel.Controls.Add(_stopButton);
        buttonPanel.Controls.Add(_pairButton);

        // Log panel with header
        var logPanel = new Panel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(10, 5, 10, 10)
        };

        var logHeader = new Label
        {
            Text = IsChinese ? "日志:" : "Log:",
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            AutoSize = true,
            Dock = DockStyle.Top,
            Height = 25
        };

        _logBox = new TextBox
        {
            Dock = DockStyle.Fill,
            Font = new Font("Consolas", 9),
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            WordWrap = false
        };

        logPanel.Controls.Add(_logBox);
        logPanel.Controls.Add(logHeader);

        // Add controls in reverse order (Dock fills from bottom)
        Controls.Add(logPanel);
        Controls.Add(buttonPanel);
        Controls.Add(statusPanel);
        Controls.Add(titleLabel);

        // Minimize to tray instead of closing
        FormClosing += (s, e) =>
        {
            if (!_isExiting)
            {
                e.Cancel = true;
                Hide();
                if (_trayIcon != null)
                    _trayIcon.Visible = true;
            }
        };
    }

    private void InitializeTray()
    {
        _trayMenu = new ContextMenuStrip();

        // Main menu items - language based
        var showItem = new ToolStripMenuItem(IsChinese ? "显示窗口" : "Show");
        showItem.Click += (s, e) => ShowMainWindow();
        _trayMenu.Items.Add(showItem);

        _trayMenu.Items.Add(new ToolStripSeparator());

        var startItem = new ToolStripMenuItem(IsChinese ? "启动服务" : "Start Server");
        startItem.Click += (s, e) => StartServer();
        _trayMenu.Items.Add(startItem);

        var stopItem = new ToolStripMenuItem(IsChinese ? "停止服务" : "Stop Server");
        stopItem.Click += (s, e) => StopServer();
        _trayMenu.Items.Add(stopItem);

        _trayMenu.Items.Add(new ToolStripSeparator());

        var statusItem = new ToolStripMenuItem(IsChinese ? "状态" : "Status");
        statusItem.Click += (s, e) => ShowStatus();
        _trayMenu.Items.Add(statusItem);

        var pairedItem = new ToolStripMenuItem(IsChinese ? "已配对设备" : "Paired Devices");
        pairedItem.Click += (s, e) => ShowPairedDevices();
        _trayMenu.Items.Add(pairedItem);

        _trayMenu.Items.Add(new ToolStripSeparator());

        var quitItem = new ToolStripMenuItem(IsChinese ? "退出" : "Quit");
        quitItem.Click += (s, e) => QuitApplication();
        _trayMenu.Items.Add(quitItem);

        // Auto-start menu item
        _trayMenu.Items.Add(new ToolStripSeparator());
        var autoStartItem = new ToolStripMenuItem(IsChinese ? "开机自启动" : "Auto Start")
        {
            Checked = IsAutoStartEnabled()
        };
        autoStartItem.Click += (s, e) =>
        {
            ToggleAutoStart();
            autoStartItem.Checked = IsAutoStartEnabled();
        };
        _trayMenu.Items.Add(autoStartItem);

        // Create blue bell icon dynamically
        var bellIcon = CreateBellIcon();

        _trayIcon = new NotifyIcon
        {
            Text = IsChinese ? "BLE 通知同步" : "BLE Notification Sync",
            Icon = bellIcon,
            ContextMenuStrip = _trayMenu,
            Visible = true
        };

        _trayIcon.DoubleClick += (s, e) => ShowMainWindow();
        _trayIcon.BalloonTipClicked += (s, e) => ShowMainWindow();
    }

    private void InitializeServices()
    {
        _pairingStorage = new PairingStorage();
        _keyStorage = new KeyStorage();

        _gattServer = new GattServerService();
        _gattServer.OnDataReceived += OnGattDataReceived;
        _gattServer.OnStatusChanged += OnGattStatusChanged;
        _gattServer.OnDiscoveryStopped += (s, e) =>
        {
            UpdateTrayIcon("Discovery stopped");
        };

        _notificationManager = new NotificationManager(_trayIcon);

        // Periodic status refresh
        _statusTimer = new System.Windows.Forms.Timer { Interval = 5000 };
        _statusTimer.Tick += (s, e) => RefreshStatus();
        _statusTimer.Start();

        // Initial status
        UpdateStatus("Ready");
        AddLog("Application started");
    }

    private void ShowMainWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        ShowInTaskbar = true;
        Activate();
    }

    private void QuitApplication()
    {
        _isExiting = true;
        try { StopServer(); } catch { }
        try { _statusTimer?.Dispose(); } catch { }
        try { _notificationManager?.Dispose(); } catch { }
        try { _gattServer?.Dispose(); } catch { }
        try { _trayIcon.Visible = false; } catch { }
        try { _trayIcon.Dispose(); } catch { }
        Application.Exit();
    }

    private static Icon CreateBellIcon()
    {
        // Try to load ICO file from icons directory (preferred)
        string basePath = AppDomain.CurrentDomain.BaseDirectory;
        string[] iconFiles = { "icons/icon_64.ico", "icons/icon_32.ico", "icons/icon_64.png", "icons/icon_32.png", "icon.png" };

        foreach (var file in iconFiles)
        {
            string iconPath = Path.Combine(basePath, file);
            if (File.Exists(iconPath))
            {
                try
                {
                    if (iconPath.EndsWith(".ico", StringComparison.OrdinalIgnoreCase))
                        return new Icon(iconPath);
                    else
                    {
                        using var bitmap = new Bitmap(iconPath);
                        return Icon.FromHandle(bitmap.GetHicon());
                    }
                }
                catch { }
            }
        }

        // Fallback to system icon
        return SystemIcons.Information;
    }

    private static bool IsAutoStartEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(AutoStartRegPath, false);
            return key?.GetValue(AppName) != null;
        }
        catch
        {
            return false;
        }
    }

    private static void ToggleAutoStart()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(AutoStartRegPath, true);
            if (key == null) return;

            if (IsAutoStartEnabled())
            {
                key.DeleteValue(AppName, false);
            }
            else
            {
                string exePath = Environment.ProcessPath ?? "";
                key.SetValue(AppName, $"\"{exePath}\" --autostart");
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Failed to update auto-start: {ex.Message}", "Error",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private async void StartServer()
    {
        UpdateStatus("Starting GATT server...");
        _startButton.Enabled = false;

        try
        {
            bool started = await _gattServer.StartAsync();
            if (started)
            {
                UpdateStatus(IsChinese ? "服务运行中 - 广播中" : "Server running - advertising");
                _stopButton.Enabled = true;
                AddLog(IsChinese ? "GATT 服务已启动" : "GATT server started");
            }
            else
            {
                UpdateStatus(IsChinese ? "启动失败" : "Failed to start server");
                _startButton.Enabled = true;
                AddLog(IsChinese ? "GATT 服务启动失败" : "GATT server failed to start");
            }
        }
        catch (Exception ex)
        {
            UpdateStatus(IsChinese ? "启动错误" : "Start error");
            _startButton.Enabled = true;
            AddLog($"{(IsChinese ? "启动错误" : "Start error")}: {ex.Message}");
        }
    }

    private void StopServer()
    {
        _gattServer.Stop();
        UpdateStatus("Server stopped");
        _startButton.Enabled = true;
        _stopButton.Enabled = false;
        AddLog("GATT server stopped");
    }

    private void ShowStatus()
    {
        string status = _gattServer.IsRunning ? "Running" : "Stopped";
        int pairedCount = _pairingStorage.GetAll().Count;
        int keyCount = _keyStorage.GetAllKeys().Count;

        MessageBox.Show(
            $"Server: {status}\nPaired Devices: {pairedCount}\nStored Keys: {keyCount}",
            "BLE Notification Sync Status",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information);
    }

    private void ShowPairedDevices()
    {
        var devices = _pairingStorage.GetAll();
        if (devices.Count == 0)
        {
            MessageBox.Show("No paired devices.", "Paired Devices",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var sb = new StringBuilder();
        sb.AppendLine("Paired Devices:");
        sb.AppendLine(new string('-', 60));

        foreach (var device in devices)
        {
            sb.AppendLine($"MAC: {device.MacAddress}");
            sb.AppendLine($"  App: {device.AppName} ({device.PackageName})");
            sb.AppendLine($"  Paired: {device.PairedAt:yyyy-MM-dd HH:mm:ss} UTC");
            sb.AppendLine($"  Last Seen: {device.LastSeen:yyyy-MM-dd HH:mm:ss} UTC");
            sb.AppendLine();
        }

        MessageBox.Show(sb.ToString(), "Paired Devices",
            MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void ShowPairDialog()
    {
        var form = new Form
        {
            Text = IsChinese ? "配对设备" : "Pair Device",
            Size = new Size(400, 500),
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false
        };

        // Get local MAC address
        string macAddress = "Unknown";
        try
        {
            var adapter = Windows.Devices.Bluetooth.BluetoothAdapter.GetDefaultAsync().AsTask().Result;
            if (adapter != null)
            {
                macAddress = adapter.BluetoothAddress.ToString("X12");
                macAddress = macAddress.Insert(2, ":").Insert(5, ":").Insert(8, ":").Insert(11, ":").Insert(14, ":");
            }
        }
        catch { }

        var label = new Label
        {
            Text = IsChinese
                ? "使用手机 APP 扫描下方二维码绑定"
                : "Scan QR code with phone app to pair",
            Font = new Font("Segoe UI", 11),
            AutoSize = true,
            Location = new Point(20, 15)
        };

        var macLabel = new Label
        {
            Text = $"MAC: {macAddress}",
            Font = new Font("Consolas", 10),
            AutoSize = true,
            Location = new Point(20, 45)
        };

        // QR code PictureBox
        var qrBox = new PictureBox
        {
            Size = new Size(250, 250),
            Location = new Point(75, 75),
            SizeMode = PictureBoxSizeMode.Zoom,
            BorderStyle = BorderStyle.FixedSingle
        };

        // Generate QR code as bitmap
        string qrContent = $"ble://pair?mac={macAddress}&uuid=0000A1B2-0000-1000-8000-00805F9B34FB";
        qrBox.Image = GenerateQrCode(qrContent, 250, 250);

        var okButton = new Button
        {
            Text = IsChinese ? "关闭" : "Close",
            DialogResult = DialogResult.OK,
            Location = new Point(290, 430),
            Size = new Size(80, 30)
        };

        form.Controls.Add(label);
        form.Controls.Add(macLabel);
        form.Controls.Add(qrBox);
        form.Controls.Add(okButton);
        form.AcceptButton = okButton;

        form.ShowDialog(this);
    }

    private static Bitmap GenerateQrCode(string content, int width, int height)
    {
        // Simple QR-like pattern generator (placeholder)
        // In production, use a proper QR code library like QRCoder
        var bmp = new Bitmap(width, height);
        using var g = Graphics.FromImage(bmp);
        g.Clear(Color.White);

        // Draw a simple pattern as placeholder
        using var pen = new Pen(Color.Black, 2);
        int cellSize = width / 25;

        // Fixed pattern (top-left, top-right, bottom-left corners)
        for (int i = 0; i < 7; i++)
        {
            for (int j = 0; j < 7; j++)
            {
                if (i == 0 || i == 6 || j == 0 || j == 6 || (i >= 2 && i <= 4 && j >= 2 && j <= 4))
                {
                    g.FillRectangle(Brushes.Black, i * cellSize, j * cellSize, cellSize, cellSize);
                }
            }
        }

        // Draw some random data pattern
        var hash = content.GetHashCode();
        var rnd = new Random(hash);
        for (int i = 8; i < 25; i++)
        {
            for (int j = 8; j < 25; j++)
            {
                if (rnd.Next(3) == 0)
                {
                    g.FillRectangle(Brushes.Black, i * cellSize, j * cellSize, cellSize, cellSize);
                }
            }
        }

        return bmp;
    }

    #region GATT Data Handling

    private void OnGattDataReceived(object? sender, byte[] data)
    {
        // Parse frame header
        if (data.Length < GattServerService.FRAME_HEADER_SIZE)
        {
            AddLog("Received frame too short");
            return;
        }

        byte msgType = data[2];
        byte seq = data[3];
        byte totalSeq = data[4];
        byte[] payload = data[GattServerService.FRAME_HEADER_SIZE..];

        switch (msgType)
        {
            case GattServerService.MSG_REGISTER:
                HandleRegister(payload);
                break;
            case GattServerService.MSG_NOTIFY:
                HandleNotify(payload);
                break;
            case GattServerService.MSG_ICON_DATA:
                HandleIconData(seq, totalSeq, payload);
                break;
            case GattServerService.MSG_ICON_END:
                HandleIconEnd(payload);
                break;
            default:
                AddLog($"Unknown message type: 0x{msgType:X2}");
                break;
        }
    }

    private void HandleRegister(byte[] payload)
    {
        try
        {
            string json = Encoding.UTF8.GetString(payload);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            string appName = root.GetProperty("app_name").GetString() ?? "";
            string packageName = root.GetProperty("package").GetString() ?? "";

            AddLog($"REGISTER: {appName} ({packageName})");

            // Derive and store key
            byte[] key = Crypto.KeyDerivation.DeriveKey(packageName);
            _keyStorage.SaveKey("local", packageName, key);

            // Store pairing
            _pairingStorage.Save("local", packageName, appName);

            // Show notification
            _notificationManager.ShowNotification(
                "Device Paired",
                $"{appName} has been paired successfully");
        }
        catch (Exception ex)
        {
            AddLog($"REGISTER error: {ex.Message}");
        }
    }

    private void HandleNotify(byte[] payload)
    {
        try
        {
            string json = Encoding.UTF8.GetString(payload);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            string title = root.GetProperty("title").GetString() ?? "";
            string body = root.GetProperty("body").GetString() ?? "";
            string packageName = root.GetProperty("package").GetString() ?? "";

            AddLog($"NOTIFY: [{packageName}] {title} - {body}");

            // Look up icon data if available
            byte[]? iconData = null;
            var device = _pairingStorage.Load("local", packageName);
            if (device?.IconData != null)
            {
                iconData = device.IconData;
            }

            // Show notification
            _notificationManager.ShowNotification(title, body, iconData);
        }
        catch (Exception ex)
        {
            AddLog($"NOTIFY error: {ex.Message}");
        }
    }

    private void HandleIconData(byte seq, byte totalSeq, byte[] payload)
    {
        try
        {
            // Use a default key for icon transfers (not package-specific)
            string bufferKey = "icon_transfer";

            if (!_iconBuffers.ContainsKey(bufferKey))
            {
                _iconBuffers[bufferKey] = new MemoryStream();
                _iconExpectedFrames[bufferKey] = totalSeq;
            }

            _iconBuffers[bufferKey].Write(payload, 0, payload.Length);

            if (seq == totalSeq - 1)
            {
                // Last frame received, save icon
                byte[] iconData = _iconBuffers[bufferKey].ToArray();
                AddLog($"ICON complete: {iconData.Length} bytes");

                // Store in pairing (will be associated with next REGISTER)
                // For now, just log it
                _iconBuffers[bufferKey].Dispose();
                _iconBuffers.Remove(bufferKey);
                _iconExpectedFrames.Remove(bufferKey);
            }
        }
        catch (Exception ex)
        {
            AddLog($"ICON_DATA error: {ex.Message}");
        }
    }

    private void HandleIconEnd(byte[] payload)
    {
        try
        {
            string json = Encoding.UTF8.GetString(payload);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            int totalSize = root.GetProperty("total_size").GetInt32();
            AddLog($"ICON_END: total size {totalSize} bytes");
        }
        catch (Exception ex)
        {
            AddLog($"ICON_END error: {ex.Message}");
        }
    }

    #endregion

    private void OnGattStatusChanged(object? sender, string status)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => OnGattStatusChanged(sender, status));
            return;
        }

        UpdateStatus(status);
        AddLog(status);
        UpdateTrayIcon(status);
    }

    private void UpdateStatus(string status)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => UpdateStatus(status));
            return;
        }

        _statusLabel.Text = $"Status: {status}";
    }

    private void UpdateTrayIcon(string tooltip)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => UpdateTrayIcon(tooltip));
            return;
        }

        _trayIcon.Text = $"BLE Sync - {tooltip}";
    }

    private void AddLog(string message)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => AddLog(message));
            return;
        }

        string timestamp = DateTime.Now.ToString("HH:mm:ss");
        string line = $"[{timestamp}] {message}";

        _logBox.AppendText(line + Environment.NewLine);
        _logBox.SelectionStart = _logBox.TextLength;
        _logBox.ScrollToCaret();
    }

    private void RefreshStatus()
    {
        if (InvokeRequired)
        {
            BeginInvoke(RefreshStatus);
            return;
        }

        string serverStatus = _gattServer.IsRunning ? "Running" : "Stopped";
        _statusLabel.Text = $"Status: {serverStatus}";

        var devices = _pairingStorage.GetAll();
        _connectionLabel.Text = $"Paired devices: {devices.Count}";
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _statusTimer?.Dispose();
            _gattServer?.Dispose();
            _notificationManager?.Dispose();
            _trayIcon?.Dispose();
            _trayMenu?.Dispose();

            foreach (var stream in _iconBuffers.Values)
            {
                stream.Dispose();
            }
        }

        base.Dispose(disposing);
    }
}
