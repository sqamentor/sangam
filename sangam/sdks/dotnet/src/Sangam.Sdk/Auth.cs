// SPDX-License-Identifier: Apache-2.0
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace Sangam.Sdk.Auth;

/// <summary>
/// Supplies the credential for the <c>Authorization</c> header (ARCHITECTURE §4: <c>Bearer &lt;OIDC JWT&gt;</c>).
/// Implementations must be thread-safe.
/// </summary>
public interface ITokenProvider
{
    /// <summary>Full <c>Authorization</c> header value, or null for anonymous calls.</summary>
    ValueTask<string?> GetAuthorizationAsync(CancellationToken ct);

    /// <summary>Called after a 401; the next call must not return the cached credential.</summary>
    void Invalidate() { }
}

/// <summary>Factory helpers.</summary>
public static class TokenProviders
{
    /// <summary>No authentication (local dev profile without a token).</summary>
    public static ITokenProvider None { get; } = new NoneTokenProvider();

    /// <summary>Fixed bearer token (dev profile static token).</summary>
    public static ITokenProvider Static(string token) => new StaticTokenProvider(token);

    /// <summary>HTTP Basic (Flowable external-client parity, FC-37); only for dev/test gateways.</summary>
    public static ITokenProvider Basic(string user, string password) => new BasicTokenProvider(user, password);

    private sealed class NoneTokenProvider : ITokenProvider
    {
        public ValueTask<string?> GetAuthorizationAsync(CancellationToken ct) => ValueTask.FromResult<string?>(null);
    }
}

/// <summary>A token that never changes.</summary>
public sealed class StaticTokenProvider : ITokenProvider
{
    private readonly string _header;
    public StaticTokenProvider(string token)
    {
        ArgumentException.ThrowIfNullOrEmpty(token);
        _header = "Bearer " + token;
    }
    public ValueTask<string?> GetAuthorizationAsync(CancellationToken ct) => ValueTask.FromResult<string?>(_header);
}

/// <summary>HTTP Basic credentials.</summary>
public sealed class BasicTokenProvider : ITokenProvider
{
    private readonly string _header;
    public BasicTokenProvider(string user, string password) =>
        _header = "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + password));
    public ValueTask<string?> GetAuthorizationAsync(CancellationToken ct) => ValueTask.FromResult<string?>(_header);
}

/// <summary>How the client authenticates at the token endpoint.</summary>
public enum ClientAuthMethod { Basic, PostBody }

/// <summary>Raised when no token can be obtained.</summary>
public sealed class TokenException : Exception
{
    public TokenException(string message, Exception? inner = null) : base(message, inner) { }
}

/// <summary>
/// OIDC / OAuth 2.0 client-credentials grant (RFC 6749 §4.4). The token is cached and refreshed
/// <see cref="RefreshSkew"/> before <c>expires_in</c> ends; refresh is single-flight.
/// </summary>
public sealed class ClientCredentialsTokenProvider : ITokenProvider, IDisposable
{
    private readonly HttpClient _http;
    private readonly bool _ownsHttp;
    private readonly SemaphoreSlim _lock = new(1, 1);
    private string? _cached;
    private DateTimeOffset _expiresAt = DateTimeOffset.MinValue;

    public required Uri TokenEndpoint { get; init; }
    public required string ClientId { get; init; }
    public required string ClientSecret { get; init; }
    public string? Scope { get; init; }
    public string? Audience { get; init; }
    public ClientAuthMethod ClientAuth { get; init; } = ClientAuthMethod.Basic;
    public TimeSpan RefreshSkew { get; init; } = TimeSpan.FromSeconds(30);
    public Func<DateTimeOffset> Clock { get; init; } = () => DateTimeOffset.UtcNow;

    public ClientCredentialsTokenProvider(HttpClient? http = null)
    {
        _ownsHttp = http is null;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
    }

    public async ValueTask<string?> GetAuthorizationAsync(CancellationToken ct)
    {
        var t = Fresh();
        if (t is not null) return "Bearer " + t;
        await _lock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            t = Fresh() ?? await FetchAsync(ct).ConfigureAwait(false);
            return "Bearer " + t;
        }
        finally { _lock.Release(); }
    }

    public void Invalidate()
    {
        _cached = null;
        _expiresAt = DateTimeOffset.MinValue;
    }

    private string? Fresh()
    {
        var c = _cached;
        return c is not null && Clock() < _expiresAt - RefreshSkew ? c : null;
    }

    private async Task<string> FetchAsync(CancellationToken ct)
    {
        var form = new List<KeyValuePair<string, string>> { new("grant_type", "client_credentials") };
        if (Scope is not null) form.Add(new("scope", Scope));
        if (Audience is not null) form.Add(new("audience", Audience));
        using var req = new HttpRequestMessage(HttpMethod.Post, TokenEndpoint);
        req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (ClientAuth == ClientAuthMethod.Basic)
        {
            var basic = Uri.EscapeDataString(ClientId) + ":" + Uri.EscapeDataString(ClientSecret);
            req.Headers.Authorization = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(basic)));
        }
        else
        {
            form.Add(new("client_id", ClientId));
            form.Add(new("client_secret", ClientSecret));
        }
        req.Content = new FormUrlEncodedContent(form);
        try
        {
            using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
            var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode)
                throw new TokenException($"token endpoint returned HTTP {(int)resp.StatusCode}: {body}");
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            var token = root.TryGetProperty("access_token", out var at) ? at.GetString() : null;
            if (string.IsNullOrEmpty(token)) throw new TokenException("token endpoint response has no access_token");
            var expiresIn = root.TryGetProperty("expires_in", out var ei) && ei.TryGetInt64(out var v) ? v : 300;
            _cached = token;
            _expiresAt = Clock().AddSeconds(expiresIn);
            return token;
        }
        catch (HttpRequestException e) { throw new TokenException("token request failed: " + e.Message, e); }
        catch (JsonException e) { throw new TokenException("token response is not JSON", e); }
    }

    public void Dispose()
    {
        if (_ownsHttp) _http.Dispose();
        _lock.Dispose();
    }
}
