using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace DBPulse.WindowHost;

internal static class Program
{
    // A named mutex prevents two window hosts in the current logon session.
    // An existing DBPulse window receives a registered window message and
    // restores itself; it is never closed and its backend is never restarted.
    internal static readonly uint ActivationMessage =
        RegisterWindowMessage("DBPulse.WindowHost.Activate.v1");
    private const uint Broadcast = 0xffff;
    private const uint AbortIfHung = 0x0002;

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint RegisterWindowMessage(string name);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SendMessageTimeout(
        IntPtr hwnd, uint message, IntPtr wparam, IntPtr lparam,
        uint flags, uint timeout, out IntPtr result);

    [STAThread]
    private static void Main(string[] args)
    {
        // No UI or Windows profile access during the CI self-test.
        if (args.Length == 1 && args[0] == "--self-test")
        {
            Environment.ExitCode = !HostPolicy.SelfTest() ? 20
                : !BackendSession.SelfTestPortInspection() ? 21
                : !MainWindow.SelfTestSizing() ? 22 : 0;
            return;
        }

        // This is distinct from InstallGuard, which the installer checks.
        // Keep ownership for the entire UI lifetime, including Java startup.
        using var singleInstance = new Mutex(
            true, @"Local\DBPulse.WindowHost.SingleInstance.v1",
            out bool firstInstance);
        if (!firstInstance)
        {
            // A rapid second click can arrive before the first form has
            // created its handle. Repeat briefly; no extra window is opened.
            for (int attempt = 0; attempt < 8; attempt++)
            {
                SendMessageTimeout(new IntPtr(Broadcast), ActivationMessage,
                    IntPtr.Zero, IntPtr.Zero, AbortIfHung, 400, out _);
                Thread.Sleep(180);
            }
            return;
        }

        try
        {
            // Keep original installer protection intact.
            using var installGuard = new Mutex(false, @"Local\DBPulse.WindowHost.InstallGuard");
            WindowHostDiagnostics.Reset();
            ApplicationConfiguration.Initialize();
            Application.Run(new MainWindow());
        }
        finally
        {
            singleInstance.ReleaseMutex();
        }
    }
}
