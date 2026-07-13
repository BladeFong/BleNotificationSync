using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace BleNotificationWin.Notification;

/// <summary>
/// Manages Windows notifications for BLE notification sync.
/// Uses BalloonTip for tray notification display with optional icon support.
/// </summary>
public class NotificationManager : IDisposable
{
    private readonly NotifyIcon _notifyIcon;
    private readonly string _appIconPath;
    private bool _disposed;

    /// <summary>
    /// Event raised when user clicks a notification balloon.
    /// </summary>
    public event EventHandler? OnNotificationClicked;

    /// <summary>
    /// Create a new NotificationManager.
    /// </summary>
    /// <param name="notifyIcon">The tray NotifyIcon to display balloon notifications from</param>
    public NotificationManager(NotifyIcon notifyIcon)
    {
        _notifyIcon = notifyIcon ?? throw new ArgumentNullException(nameof(notifyIcon));
        _appIconPath = GetAppIconPath();
    }

    /// <summary>
    /// Show a notification with title, body, and optional icon.
    /// </summary>
    /// <param name="title">Notification title</param>
    /// <param name="body">Notification body text</param>
    /// <param name="iconPath">Optional path to notification icon (.ico file)</param>
    public void ShowNotification(string title, string body, string? iconPath = null)
    {
        if (_disposed) return;

        try
        {
            // Determine icon to use
            Icon? icon = null;
            if (!string.IsNullOrEmpty(iconPath) && File.Exists(iconPath))
            {
                icon = new Icon(iconPath);
            }
            else if (!string.IsNullOrEmpty(_appIconPath) && File.Exists(_appIconPath))
            {
                icon = new Icon(_appIconPath);
            }

            // Show balloon tip
            _notifyIcon.BalloonTipTitle = title;
            _notifyIcon.BalloonTipText = body;
            _notifyIcon.BalloonTipIcon = ToolTipIcon.Info;

            if (icon != null)
            {
                _notifyIcon.Icon = icon;
            }

            _notifyIcon.ShowBalloonTip(5000);
        }
        catch (Exception ex)
        {
            // Fallback: log error (in production, use proper logging)
            System.Diagnostics.Debug.WriteLine($"Notification error: {ex.Message}");
        }
    }

    /// <summary>
    /// Show a notification with an icon loaded from byte data (e.g., app icon from BLE transfer).
    /// </summary>
    /// <param name="title">Notification title</param>
    /// <param name="body">Notification body text</param>
    /// <param name="iconData">Icon binary data (ICO format)</param>
    public void ShowNotification(string title, string body, byte[]? iconData)
    {
        if (_disposed) return;

        if (iconData != null && iconData.Length > 0)
        {
            try
            {
                using var stream = new MemoryStream(iconData);
                using var icon = new Icon(stream);
                _notifyIcon.Icon = icon;
            }
            catch
            {
                // Ignore invalid icon data, use default
            }
        }

        ShowNotification(title, body);
    }

    /// <summary>
    /// Show a warning notification.
    /// </summary>
    public void ShowWarning(string title, string body)
    {
        if (_disposed) return;

        try
        {
            _notifyIcon.BalloonTipTitle = title;
            _notifyIcon.BalloonTipText = body;
            _notifyIcon.BalloonTipIcon = ToolTipIcon.Warning;
            _notifyIcon.ShowBalloonTip(5000);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Warning notification error: {ex.Message}");
        }
    }

    /// <summary>
    /// Show an error notification.
    /// </summary>
    public void ShowError(string title, string body)
    {
        if (_disposed) return;

        try
        {
            _notifyIcon.BalloonTipTitle = title;
            _notifyIcon.BalloonTipText = body;
            _notifyIcon.BalloonTipIcon = ToolTipIcon.Error;
            _notifyIcon.ShowBalloonTip(5000);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Error notification error: {ex.Message}");
        }
    }

    /// <summary>
    /// Get the application icon path.
    /// </summary>
    private static string GetAppIconPath()
    {
        string exePath = Environment.ProcessPath ?? "";
        string? dir = Path.GetDirectoryName(exePath);
        if (string.IsNullOrEmpty(dir)) return "";

        // Look for app.ico in the same directory as the executable
        string iconPath = Path.Combine(dir, "app.ico");
        if (File.Exists(iconPath)) return iconPath;

        // Fallback: use the executable's embedded icon
        return exePath;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        // Don't dispose the NotifyIcon here - the form owns it
        GC.SuppressFinalize(this);
    }
}
