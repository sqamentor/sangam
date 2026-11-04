// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using Sangam.Sdk.Api;
using Sangam.Sdk.Auth;
using Sangam.Sdk.Http;
using Sangam.Sdk.Logging;

namespace Sangam.Sdk;

/// <summary>Options for <see cref="SangamClient"/>.</summary>
public sealed class SangamClientOptions
{
    /// <summary>Server root, for example <c>https://host:8080</c>; a trailing <c>/api/v1</c> is accepted.</summary>
    public required Uri BaseUrl { get; init; }
    public ITokenProvider TokenProvider { get; init; } = TokenProviders.None;
    public RetryPolicy RetryPolicy { get; init; } = RetryPolicy.Default;
    public TimeSpan RequestTimeout { get; init; } = TimeSpan.FromSeconds(30);
    public ISangamLogger Logger { get; init; } = SangamLoggers.Console();
    /// <summary>Optional shared HttpClient (its Timeout should be infinite; per-call timeouts are applied by the SDK).</summary>
    public HttpClient? HttpClient { get; init; }
    public string? UserAgent { get; init; }
    public JsonSerializerOptions? Json { get; init; }
    /// <summary>Default <c>X-Sangam-Seat</c> (seat context, IX.5 rule 1); a request may override it.</summary>
    public string? SeatId { get; init; }
    /// <summary>Default <c>X-Sangam-Acting-For-Seat</c> (acting authority for a substituted seat, IX.5 rule 4).</summary>
    public string? ActingForSeatId { get; init; }
}

/// <summary>
/// Entry point of the Sangam .NET SDK: a typed client for <c>/api/v1</c>. Thread-safe; share one instance.
/// </summary>
public sealed class SangamClient : IDisposable
{
    private readonly HttpClient _http;
    private readonly bool _ownsHttp;

    public SangamClient(SangamClientOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        _ownsHttp = options.HttpClient is null;
        _http = options.HttpClient ?? new HttpClient(new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            ConnectTimeout = TimeSpan.FromSeconds(10),
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        }) { Timeout = Timeout.InfiniteTimeSpan };
        Transport = new HttpTransport(_http, options.BaseUrl, options.TokenProvider, options.RetryPolicy,
            options.RequestTimeout, options.Logger, options.UserAgent, options.Json, DefaultHeaders(options));
        Work = new WorkApi(Transport);
        Tasks = new TasksApi(Transport);
        Processes = new ProcessesApi(Transport);
        Decisions = new DecisionsApi(Transport);
        Audit = new AuditApi(Transport);
        Admin = new AdminApi(Transport);
        Ops = new OpsApi(Transport);
        Repository = new RepositoryApi(Transport);
    }

    /// <summary>Shortcut: base URL plus a static token (dev profile) or none.</summary>
    public SangamClient(string baseUrl, string? token = null) : this(new SangamClientOptions
    {
        BaseUrl = new Uri(baseUrl),
        TokenProvider = token is null ? TokenProviders.None : TokenProviders.Static(token),
    }) { }

    private static Dictionary<string, string> DefaultHeaders(SangamClientOptions o)
    {
        var h = new Dictionary<string, string>();
        if (o.SeatId is not null) h[HttpTransport.SeatHeader] = o.SeatId;
        if (o.ActingForSeatId is not null) h[HttpTransport.ActingForSeatHeader] = o.ActingForSeatId;
        return h;
    }

    /// <summary><c>GET /me</c>: the caller's identity, tenant, seats and roles (untyped).</summary>
    public async Task<Dictionary<string, object?>?> MeAsync(CancellationToken ct = default) =>
        (await Transport.ExecuteAsync<Dictionary<string, object?>>(ApiRequest.Get("/me"), ct).ConfigureAwait(false)).Body;

    public HttpTransport Transport { get; }
    public ISangamLogger Logger => Transport.Logger;
    /// <summary>Worker API <c>/work/**</c>.</summary>
    public WorkApi Work { get; }
    public TasksApi Tasks { get; }
    public ProcessesApi Processes { get; }
    public DecisionsApi Decisions { get; }
    public AuditApi Audit { get; }
    public AdminApi Admin { get; }
    public OpsApi Ops { get; }
    public RepositoryApi Repository { get; }

    public void Dispose()
    {
        if (_ownsHttp) _http.Dispose();
    }
}
