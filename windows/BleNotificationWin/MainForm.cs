using System;
using System.Drawing;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;
using BleNotificationWin.Gatt;
using BleNotificationWin.Notification;
using BleNotificationWin.Storage;

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
    private ListBox _logList = null!;

    // Timer for periodic status refresh
    private System.Windows.Forms.Timer _statusTimer = null!;

    // Icon buffer for multi-frame icon transfers
    private readonly Dictionary<string, MemoryStream> _iconBuffers = new();
    private readonly Dictionary<string, int> _iconExpectedFrames = new();

    // Language support
    private static bool IsChinese => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "zh";

    // Exit flag
    private bool _isExiting;

    public MainForm()
    {
        InitializeComponents();
        InitializeTray();
        InitializeServices();
    }

    private void InitializeComponents()
    {
        Text = IsChinese ? "BLE 通知同步" : "BLE Notification Sync";
        Size = new Size(500, 400);
        StartPosition = FormStartPosition.CenterScreen;
        ShowInTaskbar = false;
        WindowState = FormWindowState.Minimized;

        var titleLabel = new Label
        {
            Text = IsChinese ? "BLE 通知同步" : "BLE Notification Sync",
            Font = new Font("Segoe UI", 16, FontStyle.Bold),
            AutoSize = true,
            Location = new Point(20, 15)
        };

        _statusLabel = new Label
        {
            Text = IsChinese ? "状态: 初始化中..." : "Status: Initializing...",
            Font = new Font("Segoe UI", 10),
            AutoSize = true,
            Location = new Point(20, 55)
        };

        _connectionLabel = new Label
        {
            Text = IsChinese ? "连接数: 0" : "Connections: 0",
            Font = new Font("Segoe UI", 10),
            AutoSize = true,
            Location = new Point(20, 80)
        };

        _startButton = new Button
        {
            Text = IsChinese ? "启动服务" : "Start",
            Size = new Size(120, 35),
            Location = new Point(20, 115),
            Enabled = true
        };
        _startButton.Click += (s, e) => StartServer();

        _stopButton = new Button
        {
            Text = IsChinese ? "停止服务" : "Stop",
            Size = new Size(120, 35),
            Location = new Point(150, 115),
            Enabled = false
        };
        _stopButton.Click += (s, e) => StopServer();

        _logList = new ListBox
        {
            Location = new Point(20, 165),
            Size = new Size(455, 180),
            Font = new Font("Consolas", 9)
        };

        Controls.Add(titleLabel);
        Controls.Add(_statusLabel);
        Controls.Add(_connectionLabel);
        Controls.Add(_startButton);
        Controls.Add(_stopButton);
        Controls.Add(_logList);

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

        // Load custom icon or fallback to system icon
        Icon appIcon;
        try
        {
            string iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "icon.png");
            if (File.Exists(iconPath))
                appIcon = new Icon(iconPath);
            else
                appIcon = SystemIcons.Application;
        }
        catch
        {
            appIcon = SystemIcons.Application;
        }

        _trayIcon = new NotifyIcon
        {
            Text = IsChinese ? "BLE 通知同步" : "BLE Notification Sync",
            Icon = appIcon,
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

    private async void StartServer()
    {
        UpdateStatus("Starting GATT server...");
        _startButton.Enabled = false;

        bool started = await _gattServer.StartAsync();
        if (started)
        {
            UpdateStatus("Server running - advertising");
            _stopButton.Enabled = true;
            AddLog("GATT server started");
        }
        else
        {
            UpdateStatus("Failed to start server");
            _startButton.Enabled = true;
            AddLog("GATT server failed to start");
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
        _logList.Items.Add($"[{timestamp}] {message}");

        // Keep only last 100 entries
        while (_logList.Items.Count > 100)
        {
            _logList.Items.RemoveAt(0);
        }

        _logList.TopIndex = _logList.Items.Count - 1;
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
