using System;
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
            Environment.ExitCode = HostPolicy.SelfTest() ? 0 : 2;
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new MainWindow());
    }
}
