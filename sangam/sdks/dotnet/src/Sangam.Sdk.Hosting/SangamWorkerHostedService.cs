// SPDX-License-Identifier: Apache-2.0
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Sangam.Sdk.Auth;
using Sangam.Sdk.Logging;
using Sangam.Sdk.Worker;

namespace Sangam.Sdk.Hosting;

/// <summary>
/// Runs one <see cref="SangamWorker"/> with the host's lifecycle (M-027).
/// <list type="bullet">
/// <item>Start: resolves the client (a registered <see cref="SangamClient"/>, else one built from
/// <see cref="SangamWorkerHostingOptions.BaseUrl"/>/<see cref="SangamWorkerHostingOptions.Token"/>), the worker options
/// (a registered <see cref="SangamWorkerOptions"/>, else the bound options with a Microsoft.Extensions.Logging bridge)
/// and every subscription (builder registrations, <see cref="TopicSubscription"/> and <see cref="Saga"/> services), then
/// starts fetching.</item>
/// <item>Stop: <see cref="SangamWorker.StopAsync"/> (no new fetches, in-flight handlers finish within
/// <c>ShutdownTimeout</c>, the rest are cancelled and unlocked). If the host's shutdown token fires first, the host stops
/// waiting; leases of unfinished items expire on the server and the same operationId is reused (R36).</item>
/// </list>
/// </summary>
public sealed class SangamWorkerHostedService : IHostedService, IAsyncDisposable, IDisposable
{
    private readonly IServiceProvider _services;
    private readonly SangamWorkerHostingOptions _options;
    private SangamClient? _ownedClient;

    public SangamWorkerHostedService(IServiceProvider services, IOptions<SangamWorkerHostingOptions> options)
    {
        _services = services ?? throw new ArgumentNullException(nameof(services));
        _options = (options ?? throw new ArgumentNullException(nameof(options))).Value;
    }

    /// <summary>The running worker (null before start or when disabled), for health checks and metrics.</summary>
    public SangamWorker? Worker { get; private set; }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        if (Worker is not null) return Task.CompletedTask; // already started
        var logger = Bridge();
        if (!_options.Enabled)
        {
            logger?.Info("worker.disabled", "Sangam worker disabled by configuration (Enabled=false)");
            return Task.CompletedTask;
        }
        var subscriptions = ResolveSubscriptions();
        var client = _services.GetService<SangamClient>() ?? (_ownedClient = CreateClient(logger));
        var workerOptions = _services.GetService<SangamWorkerOptions>() ?? _options.ToWorkerOptions(logger);
        Worker = new SangamWorker(client, workerOptions, subscriptions);
        if (subscriptions.Count == 0) (logger ?? client.Logger).Warn("worker.no-subscriptions", "Sangam worker started without subscriptions");
        Worker.Start();
        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        var w = Worker;
        if (w is null) return;
        var stop = w.StopAsync();
        var first = await Task.WhenAny(stop, Task.Delay(Timeout.Infinite, cancellationToken)).ConfigureAwait(false);
        if (first != stop)
            Bridge()?.Warn("worker.host.shutdown.timeout",
                "host shutdown ended before the worker stopped; unfinished leases expire and the operationId is reused",
                new Dictionary<string, object?> { ["workerId"] = w.WorkerId, ["inFlight"] = w.InFlightCount });
        else
            await stop.ConfigureAwait(false);
    }

    /// <summary>Synchronous disposal (a container disposed with Dispose); prefer DisposeAsync.</summary>
    public void Dispose() => DisposeAsync().AsTask().GetAwaiter().GetResult();

    public async ValueTask DisposeAsync()
    {
        if (Worker is { } w) await w.DisposeAsync().ConfigureAwait(false);
        _ownedClient?.Dispose();
    }

    /// <summary>All registered subscriptions; one topic registered twice is an error.</summary>
    public List<TopicSubscription> ResolveSubscriptions()
    {
        var all = new List<TopicSubscription>();
        all.AddRange(_services.GetServices<TopicSubscription>());
        foreach (var saga in _services.GetServices<Saga>()) all.AddRange(saga.Subscriptions);
        foreach (var source in _services.GetServices<SangamSubscriptionSource>()) all.AddRange(source.Resolve(_services));
        var dup = all.GroupBy(s => s.Topic, StringComparer.Ordinal).FirstOrDefault(g => g.Count() > 1);
        if (dup is not null)
            throw new InvalidOperationException($"topic {dup.Key} has {dup.Count()} Sangam subscriptions; register one handler per topic");
        return all;
    }

    private SangamClient CreateClient(ISangamLogger? logger)
    {
        if (string.IsNullOrWhiteSpace(_options.BaseUrl))
            throw new InvalidOperationException("Sangam worker: set " + SangamWorkerHostingOptions.SectionName
                + ":BaseUrl or register a SangamClient in the container");
        return new SangamClient(new SangamClientOptions
        {
            BaseUrl = new Uri(_options.BaseUrl),
            TokenProvider = _options.Token is null ? TokenProviders.None : TokenProviders.Static(_options.Token),
            Logger = logger ?? SangamLoggers.Console(),
        });
    }

    private ISangamLogger? Bridge()
    {
        var f = _services.GetService<ILoggerFactory>();
        return f is null ? null : new MicrosoftSangamLogger(f.CreateLogger("Sangam.Sdk.Worker"));
    }
}
