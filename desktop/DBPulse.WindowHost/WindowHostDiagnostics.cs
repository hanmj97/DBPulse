using System;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace DBPulse.WindowHost;

/// <summary>
/// Minimal, bounded diagnostic codes only. Never log paths, HTTP bodies,
/// SQL text, passwords, process arguments, usernames, or raw exceptions.
/// </summary>
internal static class WindowHostDiagnostics
{
    private static readonly object Sync = new();

    private static string? PathForLog()
    {
        string root = Environment.GetFolderPath(
            Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(root)) return null;
        return Path.Combine(root, "DBPulse", "logs", "window-host-status.txt");
    }

    public static void Reset()
    {
        lock (Sync)
        {
            try
            {
                string? path = PathForLog();
                if (path is null) return;
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                File.WriteAllText(path,
                    DateTimeOffset.UtcNow.ToString("O") + " WINDOW_HOST_STARTED" + Environment.NewLine,
                    Encoding.UTF8);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    public static void Record(string code)
    {
        if (!Regex.IsMatch(code, "^[A-Z0-9_]{2,90}$")) return;
        lock (Sync)
        {
            try
            {
                string? path = PathForLog();
                if (path is null) return;
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                if (File.Exists(path) && new FileInfo(path).Length > 16000) return;
                File.AppendAllText(path,
                    DateTimeOffset.UtcNow.ToString("O") + " " + code + Environment.NewLine,
                    Encoding.UTF8);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
