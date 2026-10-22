using System;

namespace DBPulse.WindowHost;

internal static class HostPolicy
{
    public const string BaseUrl = "http://127.0.0.1:18081/";
    public const string DashboardUrl = BaseUrl + "#targets";

    // Protect the embedded WebView from navigating to a foreign origin,
    // including localhost-alikes, IPv6, file://, javascript:, and redirects.
    public static bool IsAllowedAppUrl(string? value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)) return false;
        return uri.Scheme == Uri.UriSchemeHttp
            && uri.Host.Equals("127.0.0.1", StringComparison.Ordinal)
            && uri.Port == 18081
            && uri.UserInfo.Length == 0;
    }

    public static bool SelfTest()
    {
        string[] allowed = {
            BaseUrl, DashboardUrl,
            "http://127.0.0.1:18081/#queries",
            "http://127.0.0.1:18081/api/v1/health"
        };
        string[] blocked = {
            "https://127.0.0.1:18081/",
            "http://127.0.0.1:18082/",
            "http://localhost:18081/",
            "http://127.0.0.1.evil.example:18081/",
            "http://evil.example/?next=127.0.0.1:18081",
            "file:///C:/private.txt",
            "javascript:alert(1)",
            "http://name@127.0.0.1:18081/",
            ""
        };
        foreach (string url in allowed) if (!IsAllowedAppUrl(url)) return false;
        foreach (string url in blocked) if (IsAllowedAppUrl(url)) return false;
        return true;
    }
}
