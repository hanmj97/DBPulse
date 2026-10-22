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
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(900, 600);
        Size = new Size(1440, 900);
        BackColor = Color.FromArgb(10, 22, 42);

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
