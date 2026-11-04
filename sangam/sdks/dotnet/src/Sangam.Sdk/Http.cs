// SPDX-License-Identifier: Apache-2.0
using System.Collections;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Sangam.Sdk.Auth;
using Sangam.Sdk.Errors;
using Sangam.Sdk.Logging;
using static Sangam.Sdk.Logging.SangamLoggers;

namespace Sangam.Sdk.Http;

/// <summary>
/// Exponential back-off with jitter for transport retries (R28). Only safe requests are retried: GET, or a
/// mutation that carries an Idempotency-Key (R41).
/// </summary>
public sealed record RetryPolicy(int MaxAttempts, TimeSpan InitialDelay, TimeSpan MaxDelay, double Multiplier)
{
    public static RetryPolicy Default { get; } = new(4, TimeSpan.FromMilliseconds(200), TimeSpan.FromSeconds(5), 2.0);
    public static RetryPolicy None { get; } = new(1, TimeSpan.Zero, TimeSpan.Zero, 1.0);

    /// <summary>Delay before retry number <paramref name="retry"/> (1-based), jittered in [cap/2, cap].</summary>
    public TimeSpan Delay(int retry)
    {
        var cap = InitialDelay.TotalMilliseconds * Math.Pow(Multiplier, Math.Max(0, retry - 1));
        var capped = (long)Math.Min(cap, MaxDelay.TotalMilliseconds);
        if (capped <= 0) return TimeSpan.Zero;
        return TimeSpan.FromMilliseconds(Random.Shared.NextInt64(capped / 2, capped + 1));
    }
}

/// <summary>One logical API call.</summary>
public sealed class ApiRequest
{
    internal HttpMethod Method { get; }
    internal string Path { get; }
    internal List<KeyValuePair<string, object>> Query { get; } = new();
    internal Dictionary<string, string> Headers { get; } = new();
    internal object? Body { get; private set; }
    internal HttpContent? RawContent { get; private set; }
    internal string? IdempotencyKeyValue { get; private set; }
    internal TimeSpan? TimeoutValue { get; private set; }
    internal bool Retry { get; private set; } = true;

    private ApiRequest(HttpMethod m, string path) { Method = m; Path = path; }

    public static ApiRequest Get(string path) => new(HttpMethod.Get, path);
    public static ApiRequest Post(string path) => new(HttpMethod.Post, path);
    public static ApiRequest Put(string path) => new(HttpMethod.Put, path);
    public static ApiRequest Patch(string path) => new(HttpMethod.Patch, path);
    public static ApiRequest Delete(string path) => new(HttpMethod.Delete, path);

    public ApiRequest WithQuery(string name, object? value)
    {
        if (value is not null) Query.Add(new(name, value));
        return this;
    }

    public ApiRequest WithQuery(IEnumerable<KeyValuePair<string, object?>>? values)
    {
        if (values is not null) foreach (var kv in values) WithQuery(kv.Key, kv.Value);
        return this;
    }

    public ApiRequest WithHeader(string name, string? value)
    {
        if (value is not null) Headers[name] = value;
        return this;
    }

    public ApiRequest WithBody(object? body) { Body = body; return this; }

    /// <summary>Raw content (multipart). Must be rebuildable: the transport buffers it once and resends the bytes.</summary>
    public ApiRequest WithContent(HttpContent content) { RawContent = content; return this; }

    /// <summary>Idempotency-Key (R41). Null on a mutation = generated once per logical call.</summary>
    public ApiRequest WithIdempotencyKey(string? key) { IdempotencyKeyValue = key; return this; }

    public ApiRequest WithTimeout(TimeSpan timeout) { TimeoutValue = timeout; return this; }

    /// <summary>Disable transport retries (long-poll fetch has its own loop).</summary>
    public ApiRequest NoRetry() { Retry = false; return this; }

    internal bool IsMutation => Method != HttpMethod.Get && Method != HttpMethod.Head;
}

/// <summary>Decoded response.</summary>
public sealed record ApiResponse<T>(int Status, HttpResponseHeaders Headers, HttpContentHeaders? ContentHeaders, T? Body)
{
    public string? Header(string name) =>
        Headers.TryGetValues(name, out var v) ? v.FirstOrDefault()
        : ContentHeaders is not null && ContentHeaders.TryGetValues(name, out var c) ? c.FirstOrDefault() : null;

    /// <summary>True when the server answered from its idempotency store (<c>Idempotent-Replayed</c> header, R41).</summary>
    public bool IdempotentReplayed => string.Equals(Header("Idempotent-Replayed"), "true", StringComparison.OrdinalIgnoreCase);
}

/// <summary>
/// The single HTTP path of the SDK: base URL + <c>/api/v1</c>, auth with one refresh on 401, Idempotency-Key on every
/// mutation (R41), problem+json decoding (RFC 9457) and jittered retries of safe calls.
/// </summary>
public sealed class HttpTransport
{
    public const string ApiPrefix = "/api/v1";
    public const string SdkVersion = "0.1.0";
    /// <summary>Seat context selected by the caller (IX.5 rule 1).</summary>
    public const string SeatHeader = "X-Sangam-Seat";
    /// <summary>Acting authority for a substituted seat (IX.5 rule 4); part of the R41 fingerprint.</summary>
    public const string ActingForSeatHeader = "X-Sangam-Acting-For-Seat";

    public static JsonSerializerOptions DefaultJson { get; } = CreateJson();

    private readonly HttpClient _http;
    private readonly string _base;
    private readonly ITokenProvider _tokens;
    private readonly RetryPolicy _retry;
    private readonly TimeSpan _timeout;
    private readonly string _userAgent;
    private readonly IReadOnlyDictionary<string, string> _defaultHeaders;

    public JsonSerializerOptions Json { get; }
    public ISangamLogger Logger { get; }

    public HttpTransport(HttpClient http, Uri baseUrl, ITokenProvider? tokens, RetryPolicy? retry, TimeSpan? requestTimeout,
        ISangamLogger? logger, string? userAgent, JsonSerializerOptions? json = null,
        IReadOnlyDictionary<string, string>? defaultHeaders = null)
    {
        _defaultHeaders = defaultHeaders ?? new Dictionary<string, string>();
        _http = http;
        var b = baseUrl.ToString().TrimEnd('/');
        if (b.EndsWith(ApiPrefix, StringComparison.Ordinal)) b = b[..^ApiPrefix.Length];
        _base = b;
        _tokens = tokens ?? TokenProviders.None;
        _retry = retry ?? RetryPolicy.Default;
        _timeout = requestTimeout ?? TimeSpan.FromSeconds(30);
        Logger = logger ?? SangamLoggers.None;
        _userAgent = userAgent ?? "sangam-sdk-dotnet/" + SdkVersion;
        Json = json ?? DefaultJson;
    }

    private static JsonSerializerOptions CreateJson()
    {
        var o = new JsonSerializerOptions(JsonSerializerDefaults.Web)
        {
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        };
        return o;
    }

    /// <summary>Percent-encodes one path segment.</summary>
    public static string Seg(string s) => Uri.EscapeDataString(s ?? throw new ArgumentNullException(nameof(s)));

    public async Task<ApiResponse<T>> ExecuteAsync<T>(ApiRequest req, CancellationToken ct = default)
    {
        var idemKey = req.IdempotencyKeyValue;
        if (idemKey is null && req.IsMutation) idemKey = Guid.NewGuid().ToString();
        var safe = !req.IsMutation || idemKey is not null;
        var maxAttempts = req.Retry && safe ? _retry.MaxAttempts : 1;
        byte[]? rawBytes = null;
        MediaTypeHeaderValue? rawType = null;
        if (req.RawContent is not null)
        {
            rawBytes = await req.RawContent.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
            rawType = req.RawContent.Headers.ContentType;
        }
        var refreshed = false;
        var attempt = 0;
        while (true)
        {
            attempt++;
            HttpResponseMessage resp;
            try
            {
                resp = await SendOnceAsync(req, idemKey, rawBytes, rawType, ct).ConfigureAwait(false);
            }
            catch (Exception e) when (e is HttpRequestException || (e is TaskCanceledException && !ct.IsCancellationRequested))
            {
                if (attempt < maxAttempts)
                {
                    var d = _retry.Delay(attempt);
                    Logger.Debug("http.retry", "retrying after transport error: " + e.Message,
                        F(("method", req.Method.Method), ("path", req.Path), ("attempt", attempt), ("delayMs", (long)d.TotalMilliseconds)));
                    await Task.Delay(d, ct).ConfigureAwait(false);
                    continue;
                }
                throw new TransportException($"{req.Method} {req.Path} failed: {e.Message}", e);
            }
            using (resp)
            {
                var status = (int)resp.StatusCode;
                if (status == 401 && !refreshed)
                {
                    refreshed = true;
                    _tokens.Invalidate();
                    attempt--; // an auth refresh does not consume a retry
                    continue;
                }
                var bytes = await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
                if (status is >= 200 and < 300)
                {
                    T? body = default;
                    if (bytes.Length > 0 && typeof(T) != typeof(object) && typeof(T) != typeof(byte[]))
                        body = JsonSerializer.Deserialize<T>(bytes, Json);
                    else if (typeof(T) == typeof(byte[]))
                        body = (T)(object)bytes;
                    return new ApiResponse<T>(status, resp.Headers, resp.Content.Headers, body);
                }
                var ex = ToException(req, status, bytes);
                if (ex.IsRetryable && attempt < maxAttempts)
                {
                    var d = RetryAfter(resp) ?? _retry.Delay(attempt);
                    Logger.Debug("http.retry", "retrying after HTTP " + status,
                        F(("method", req.Method.Method), ("path", req.Path), ("attempt", attempt), ("delayMs", (long)d.TotalMilliseconds)));
                    await Task.Delay(d, ct).ConfigureAwait(false);
                    continue;
                }
                throw ex;
            }
        }
    }

    private async Task<HttpResponseMessage> SendOnceAsync(ApiRequest req, string? idemKey, byte[]? raw, MediaTypeHeaderValue? rawType, CancellationToken ct)
    {
        using var msg = new HttpRequestMessage(req.Method, BuildUri(req));
        msg.Headers.Accept.ParseAdd("application/json");
        msg.Headers.Accept.ParseAdd("application/problem+json");
        msg.Headers.UserAgent.ParseAdd(_userAgent);
        var auth = await _tokens.GetAuthorizationAsync(ct).ConfigureAwait(false);
        if (auth is not null) msg.Headers.TryAddWithoutValidation("Authorization", auth);
        if (idemKey is not null) msg.Headers.TryAddWithoutValidation("Idempotency-Key", idemKey);
        foreach (var h in _defaultHeaders)
            if (!req.Headers.ContainsKey(h.Key)) msg.Headers.TryAddWithoutValidation(h.Key, h.Value);
        foreach (var h in req.Headers) msg.Headers.TryAddWithoutValidation(h.Key, h.Value);
        if (raw is not null)
        {
            msg.Content = new ByteArrayContent(raw);
            if (rawType is not null) msg.Content.Headers.ContentType = rawType;
        }
        else if (req.Body is not null)
        {
            msg.Content = new ByteArrayContent(JsonSerializer.SerializeToUtf8Bytes(req.Body, req.Body.GetType(), Json));
            msg.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        }
        else if (req.IsMutation)
        {
            msg.Content = new StringContent("{}", Encoding.UTF8, "application/json");
        }
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(req.TimeoutValue ?? _timeout);
        // HttpClient.Timeout should be infinite (or longer than the long poll); the per-call timeout is applied here.
        return await _http.SendAsync(msg, HttpCompletionOption.ResponseContentRead, cts.Token).ConfigureAwait(false);
    }

    private Uri BuildUri(ApiRequest req)
    {
        var sb = new StringBuilder(_base).Append(ApiPrefix);
        if (!req.Path.StartsWith('/')) sb.Append('/');
        sb.Append(req.Path);
        var sep = '?';
        foreach (var (k, v) in req.Query)
        {
            if (v is string s) Append(k, s);
            else if (v is IEnumerable e) foreach (var item in e) Append(k, Convert.ToString(item, System.Globalization.CultureInfo.InvariantCulture) ?? "");
            else if (v is bool bo) Append(k, bo ? "true" : "false");
            else Append(k, Convert.ToString(v, System.Globalization.CultureInfo.InvariantCulture) ?? "");
        }
        return new Uri(sb.ToString());

        void Append(string k, string val)
        {
            sb.Append(sep).Append(Uri.EscapeDataString(k)).Append('=').Append(Uri.EscapeDataString(val));
            sep = '&';
        }
    }

    private SangamApiException ToException(ApiRequest req, int status, byte[] body)
    {
        Problem? p = null;
        if (body.Length > 0)
        {
            try { p = JsonSerializer.Deserialize<Problem>(body, Json); }
            catch (JsonException) { /* not JSON */ }
        }
        p ??= new Problem { Status = status, Title = "HTTP " + status, Detail = body.Length == 0 ? null : Encoding.UTF8.GetString(body) };
        p.Status ??= status;
        var msg = $"{req.Method} {req.Path} -> {status}" + (p.Title is not null ? " " + p.Title : "") + (p.Detail is not null ? ": " + p.Detail : "");
        if (status == 409 && (p.Is("stale-lease") || p.Is("STALE_LEASE"))) return new StaleLeaseException(p, msg);
        if (status == 410) return new GoneException(p, msg);
        return new SangamApiException(status, p, msg);
    }

    private static TimeSpan? RetryAfter(HttpResponseMessage resp)
    {
        var ra = resp.Headers.RetryAfter;
        if (ra?.Delta is { } d) return d > TimeSpan.FromSeconds(60) ? TimeSpan.FromSeconds(60) : d;
        return null;
    }
}
