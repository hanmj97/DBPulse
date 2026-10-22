using System;
using System.Threading;
using System.Windows.Forms;

namespace DBPulse.WindowHost;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // No UI or Windows profile access during the CI self-test.
        if (args.Length == 1 && args[0] == "--self-test")
        {
            // Distinct codes let CI differentiate policy from OS port-table
            // self-test failures without printing any user or network data.
            Environment.ExitCode = !HostPolicy.SelfTest() ? 20
                : !BackendSession.SelfTestPortInspection() ? 21
                : !MainWindow.SelfTestSizing() ? 22 : 0;
            return;
        }

        // Setup/Uninstall checks this named mutex to avoid replacing running
        // window-host binaries. The mutex is NOT a single-instance lock.
        using var installGuard = new Mutex(false, @"Local\DBPulse.WindowHost.InstallGuard");
        WindowHostDiagnostics.Reset();
        ApplicationConfiguration.Initialize();
        Application.Run(new MainWindow());
    }
}
