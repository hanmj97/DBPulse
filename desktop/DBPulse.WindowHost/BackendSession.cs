using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace DBPulse.WindowHost;

/// <summary>
/// Only starts the bundled native Java app when port 18081 is free.
/// An existing, positively identified DBPulse process is reused, never killed.
/// Owned Java child is asked to close Spring via redirected stdin EOF.
/// </summary>
internal sealed class BackendSession : IDisposable
{
    private enum ProbeResult { Free, Ready, UnknownService }

    private readonly Process? ownedJava;
    public bool StartedJava => ownedJava is not null;

    private BackendSession(Process? ownedJava) => this.ownedJava = ownedJava;

    public static async Task<BackendSession> AttachOrStartAsync(CancellationToken cancellationToken)
    {
        // Another DBPulse may have bound its TCP port before Spring starts
        // serving health and React. Retry briefly but NEVER trust an unknown
        // service merely because the port is open.
        WindowHostDiagnostics.Record("PREFLIGHT_BEGIN");
        ProbeResult existing = await ProbeAsync(cancellationToken);
        for (int attempt = 0; existing == ProbeResult.UnknownService && attempt < 6; attempt++)
        {
            await Task.Delay(500, cancellationToken);
            existing = await ProbeAsync(cancellationToken);
        }
        if (existing == ProbeResult.Ready)
        {
            WindowHostDiagnostics.Record("PREFLIGHT_REUSED_EXISTING_DBPULSE");
            return new BackendSession(null);
        }
        if (existing == ProbeResult.UnknownService)
        {
            WindowHostDiagnostics.Record("PREFLIGHT_FAILED_UNIDENTIFIED_PORT");
            throw new InvalidOperationException("PORT_18081_OCCUPIED_BY_UNKNOWN_SERVICE");
        }
        WindowHostDiagnostics.Record("PREFLIGHT_PORT_FREE");

        string bundledExe = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory, "..", "DBPulse.exe"));
        if (!File.Exists(bundledExe))
            throw new InvalidOperationException("BUNDLED_DBPULSE_BACKEND_NOT_FOUND");

        var start = new ProcessStartInfo(bundledExe)
        {
            WorkingDirectory = Path.GetDirectoryName(bundledExe)!,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardInput = true
        };
        start.Environment["DBPULSE_EMBEDDED_WINDOW_HOST"] = "1";
        // No SQL login, DPAPI blobs, .env, or database data are passed to the UI.

        Process? child = null;
        try
        {
            child = Process.Start(start);
            if (child is null) throw new InvalidOperationException("DBPULSE_BACKEND_FAILED_TO_START");
            WindowHostDiagnostics.Record("OWNED_JAVA_PROCESS_STARTED");
            // A jpackage console-mode exe can be hosted without a visible console.
            // The backend only exits when the host closes its stdin or fails.
            for (int attempt = 0; attempt < 90; attempt++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (child.HasExited)
                    throw new InvalidOperationException("DBPULSE_BACKEND_EXITED_BEFORE_READY");
                var result = await ProbeAsync(cancellationToken);
                if (result == ProbeResult.Ready)
                {
                    WindowHostDiagnostics.Record("OWNED_JAVA_READY");
                    return new BackendSession(child);
                }
                await Task.Delay(500, cancellationToken);
            }
            throw new InvalidOperationException("DBPULSE_BACKEND_STARTUP_TIMEOUT");
        }
        catch
        {
            if (child is not null)
            {
                TryCloseInput(child);
                child.Dispose();
            }
            throw;
        }
    }

    internal static bool HasListeningPort(int port)
    {
        if (port < 1 || port > 65535) throw new ArgumentOutOfRangeException(nameof(port));
        // Operating-system listener snapshot, no socket connection or DB access.
        // Covers processes bound to IPv4 loopback, IPv6, or all interfaces.
        foreach (var endpoint in IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners())
        {
            if (endpoint.Port == port) return true;
        }
        return false;
    }

    // Windows CI can verify both the LISTEN and closed states without
    // requiring a live MSSQL database, WebView2 UI or port 18081.
    internal static bool SelfTestPortInspection()
    {
        var testListener = new TcpListener(IPAddress.Loopback, 0);
        testListener.Start();
        int ephemeralPort = ((IPEndPoint)testListener.LocalEndpoint).Port;
        try
        {
            if (!HasListeningPort(ephemeralPort)) return false;
        }
        finally
        {
            testListener.Stop();
        }
        // On Windows, the TCP table may briefly retain a just-closed socket.
        // Retry only in CI self-test; production probe remains fail-closed.
        for (int attempt = 0; attempt < 8; attempt++)
        {
            if (!HasListeningPort(ephemeralPort)) return true;
            Thread.Sleep(75);
        }
        return false;
    }

    private static async Task<ProbeResult> ProbeAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        // Inspect Windows' LISTEN table, not an outbound TCP connection attempt.
        // On some Windows systems ConnectAsync to an unbound loopback port
        // times out rather than rejecting immediately. That must NOT be
        // interpreted as proof that an unknown process occupies the port.
        // Treat any IPv4/IPv6 listener on the port as occupied (fail closed).
        try
        {
            if (!HasListeningPort(18081))
            {
                WindowHostDiagnostics.Record("PROBE_PORT_NO_LISTENER");
                return ProbeResult.Free;
            }
            WindowHostDiagnostics.Record("PROBE_PORT_LISTENER_PRESENT");
        }
        catch (NetworkInformationException)
        {
            WindowHostDiagnostics.Record("PROBE_PORT_TABLE_UNAVAILABLE");
            return ProbeResult.UnknownService;
        }

        // TCP is bound, but this is not necessarily DBPulse.
        // Require the exact expected JSON identity AND the embedded React page.
        using var http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false })
        {
            Timeout = TimeSpan.FromSeconds(2)
        };
        try
        {
            using var health = await http.GetAsync(
                HostPolicy.BaseUrl + "api/v1/health",
                HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (health.StatusCode != HttpStatusCode.OK ||
                health.Content.Headers.ContentType?.MediaType != "application/json")
            {
                WindowHostDiagnostics.Record("PROBE_HEALTH_HTTP_OR_CONTENT_TYPE_UNEXPECTED");
                return ProbeResult.UnknownService;
            }
            var json = await ReadBoundedAsync(health.Content, 4096, cancellationToken);
            if (json is null)
            {
                WindowHostDiagnostics.Record("PROBE_HEALTH_RESPONSE_TOO_LARGE");
                return ProbeResult.UnknownService;
            }
            using var document = JsonDocument.Parse(json);
            JsonElement root = document.RootElement;
            if (!root.TryGetProperty("service", out var service) ||
                service.GetString() != "DBPulse" ||
                !root.TryGetProperty("status", out var status) ||
                status.GetString() != "UP" ||
                !root.TryGetProperty("phase", out var phase) ||
                phase.GetInt32() != 14)
            {
                WindowHostDiagnostics.Record("PROBE_HEALTH_IDENTITY_MISMATCH");
                return ProbeResult.UnknownService;
            }

            using var page = await http.GetAsync(
                HostPolicy.BaseUrl, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (page.StatusCode != HttpStatusCode.OK ||
                page.Content.Headers.ContentType?.MediaType != "text/html")
            {
                WindowHostDiagnostics.Record("PROBE_REACT_HTTP_OR_CONTENT_TYPE_UNEXPECTED");
                return ProbeResult.UnknownService;
            }
            string? html = await ReadBoundedAsync(page.Content, 8192, cancellationToken);
            if (html is null || !html.Contains("<title>DBPulse", StringComparison.Ordinal))
            {
                WindowHostDiagnostics.Record("PROBE_REACT_TITLE_UNEXPECTED");
                return ProbeResult.UnknownService;
            }
            WindowHostDiagnostics.Record("PROBE_DBPULSE_IDENTITY_VERIFIED");
            return ProbeResult.Ready;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception exception) when (
            exception is HttpRequestException or TaskCanceledException
                or JsonException or InvalidOperationException)
        {
            WindowHostDiagnostics.Record(exception is JsonException
                ? "PROBE_HEALTH_JSON_INVALID" : "PROBE_HTTP_REQUEST_FAILED");
            return ProbeResult.UnknownService;
        }
    }

    private static async Task<string?> ReadBoundedAsync(
        HttpContent content, int maxBytes, CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength > maxBytes) return null;
        await using Stream stream = await content.ReadAsStreamAsync(cancellationToken);
        byte[] buffer = new byte[maxBytes + 1];
        int count = 0;
        while (count < buffer.Length)
        {
            int read = await stream.ReadAsync(buffer.AsMemory(count), cancellationToken);
            if (read == 0) break;
            count += read;
        }
        return count > maxBytes ? null : Encoding.UTF8.GetString(buffer, 0, count);
    }

    private static void TryCloseInput(Process child)
    {
        try { if (!child.HasExited) child.StandardInput.Close(); }
        catch (InvalidOperationException) { }
        catch (IOException) { }
    }

    public void Dispose()
    {
        if (ownedJava is null) return; // Never stop externally started DBPulse.
        TryCloseInput(ownedJava);
        try
        {
            // Spring shuts down on input EOF. Never force-kill an SQLite writer.
            if (!ownedJava.HasExited) ownedJava.WaitForExit(7000);
        }
        catch (InvalidOperationException) { }
        ownedJava.Dispose();
    }
}
