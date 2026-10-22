using System;
using System.Drawing;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace DBPulse.WindowHost;

internal sealed class MainWindow : Form
{
    private readonly CancellationTokenSource cancel = new();
    private readonly WebView2 browser;
    private readonly Panel waiting;
    private readonly Label message;
    private BackendSession? session;

    public MainWindow()
    {
        Text = "DBPulse — Database Operations";
        // Open ~92% of usable display width / 90% of its height without
        // covering the taskbar. The original 1440x900 clipped many panels.
        // Clamp on small laptops and cap on ultrawide/multi-monitor setups.
        StartPosition = FormStartPosition.Manual;
        var working = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1440, 900);
        MinimumSize = new Size(Math.Min(900, Math.Max(480, working.Width - 24)),
                               Math.Min(600, Math.Max(360, working.Height - 24)));
        Bounds = ComputeInitialBounds(working);
        BackColor = Color.FromArgb(10, 22, 42);

        // The embedded app icon also appears in the title bar / taskbar.
        // A packaged ICO is included in the window directory for shortcuts.
        string iconFile = Path.Combine(AppContext.BaseDirectory, "DBPulse.ico");
        if (File.Exists(iconFile))
        {
            Icon = new Icon(iconFile);
        }

        browser = new WebView2 { Dock = DockStyle.Fill, Visible = false };
        waiting = new Panel { Dock = DockStyle.Fill, BackColor = BackColor };
        message = new Label
        {
            Dock = DockStyle.Fill, AutoSize = false,
            ForeColor = Color.FromArgb(215, 229, 250),
            Font = new Font("Segoe UI", 14),
            TextAlign = ContentAlignment.MiddleCenter,
            Text = "DBPulse를 준비하는 중입니다..."
        };
        waiting.Controls.Add(message);
        Controls.Add(browser);
        Controls.Add(waiting);
    }


    // The second shortcut launch sends this registered message rather than
    // creating another window or reconnecting to SQL Server.
    protected override void WndProc(ref Message m)
    {
        if (Program.ActivationMessage != 0 && m.Msg == Program.ActivationMessage)
        {
            if (WindowState == FormWindowState.Minimized)
                WindowState = FormWindowState.Normal;
            Show();
            Activate();
            BringToFront();
            m.Result = IntPtr.Zero;
            return;
        }
        base.WndProc(ref m);
    }

    internal static Rectangle ComputeInitialBounds(Rectangle usable)
    {
        int availableWidth = Math.Max(1, usable.Width);
        int availableHeight = Math.Max(1, usable.Height);
        int width = Math.Min(1800, Math.Max(1, (int)Math.Round(availableWidth * 0.92)));
        int height = Math.Min(1100, Math.Max(1, (int)Math.Round(availableHeight * 0.90)));
        // Leave a small visible edge even with a particularly small display.
        width = Math.Min(width, Math.Max(1, availableWidth - Math.Min(24, availableWidth / 12)));
        height = Math.Min(height, Math.Max(1, availableHeight - Math.Min(24, availableHeight / 12)));
        return new Rectangle(usable.Left + (availableWidth - width) / 2,
                             usable.Top + (availableHeight - height) / 2,
                             width, height);
    }

    internal static bool SelfTestSizing()
    {
        var large = ComputeInitialBounds(new Rectangle(0, 0, 1920, 1040));
        var smallWorking = new Rectangle(120, 50, 1280, 680);
        var small = ComputeInitialBounds(smallWorking);
        return large.Width >= 1600 && large.Height > 900 &&
               large.Bottom <= 1040 && large.Right <= 1920 &&
               smallWorking.Contains(small) && small.Width > 1000 &&
               small.Height < smallWorking.Height;
    }

    protected override async void OnShown(EventArgs e)
    {
        base.OnShown(e);
        try
        {
            message.Text = "SQLite 기반 DBPulse 서버를 확인하는 중입니다...";
            session = await BackendSession.AttachOrStartAsync(cancel.Token);
            cancel.Token.ThrowIfCancellationRequested();

            message.Text = "Windows WebView2 화면을 준비하는 중입니다...";
            string profile = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "DBPulse", "webview2");
            Directory.CreateDirectory(profile);
            var environment = await CoreWebView2Environment.CreateAsync(
                userDataFolder: profile);
            await browser.EnsureCoreWebView2Async(environment);
            cancel.Token.ThrowIfCancellationRequested();

            var core = browser.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreBrowserAcceleratorKeysEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.NewWindowRequested += (_, args) =>
            {
                // Preview fail-closed: no arbitrary popups or unreviewed external URLs.
                args.Handled = true;
            };
            core.NavigationStarting += (_, args) =>
            {
                if (!HostPolicy.IsAllowedAppUrl(args.Uri)) args.Cancel = true;
            };
            core.NavigationCompleted += (_, args) =>
            {
                if (!args.IsSuccess && !IsDisposed)
                {
                    message.Text = "대시보드 로드에 실패했습니다. 앱을 다시 실행해 주세요.";
                    waiting.BringToFront();
                    waiting.Visible = true;
                }
            };

            browser.Visible = true;
            waiting.Visible = false;
            core.Navigate(HostPolicy.DashboardUrl);
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            // The user closed the window while Spring / WebView2 was starting.
        }
        catch (Exception error)
        {
            if (IsDisposed || cancel.IsCancellationRequested) return;
            // Do not print raw Java/SQL errors or local paths into the window.
            string code = error is InvalidOperationException
                ? error.Message : "WINDOW_STARTUP_FAILED";
            if (code.Length > 72 || !System.Text.RegularExpressions.Regex.IsMatch(
                code, "^[A-Z0-9_]+$")) code = "WINDOW_STARTUP_FAILED";
            WindowHostDiagnostics.Record("WINDOW_START_FAILED");
            message.Text = "DBPulse 앱을 시작할 수 없습니다.\n\n" + code
                + "\n\n기존 DBPulse.exe는 변경되지 않았습니다.";
            waiting.Visible = true;
            waiting.BringToFront();
        }
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        cancel.Cancel();
        browser.Dispose();
        // Owned backend receives stdin EOF and closes Spring normally.
        // Reused external DBPulse is NEVER terminated by this window.
        session?.Dispose();
        cancel.Dispose();
        base.OnFormClosed(e);
    }
}
