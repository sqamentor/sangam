// SPDX-License-Identifier: Apache-2.0
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Sangam.Sdk.Hosting;
using Sangam.Sdk.Logging;
using Sangam.Sdk.Testing;
using Sangam.Sdk.Tests.Contract;
using Sangam.Sdk.Worker;
using Xunit;

namespace Sangam.Sdk.Tests.Hosting;

/// <summary>
/// M-027: <c>AddSangamWorker</c> and <see cref="SangamWorkerHostedService"/> start the SDK worker with the host, resolve
/// handlers and sagas from the container, bind options from configuration and stop gracefully.
/// </summary>
public sealed class SangamWorkerHostedServiceTests
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(10);

    private static SangamWorkerOptions Fast(SangamTestEngine e) => new()
    {
        WorkerId = "host-1",
        MaxConcurrency = 2,
        AsyncResponseTimeout = TimeSpan.FromMilliseconds(100),
        FetchBackoff = ContractSupport.Fast,
        ReportBackoff = ContractSupport.Fast,
        ShutdownTimeout = TimeSpan.FromSeconds(5),
        Clock = e.Now,
        Logger = SangamLoggers.None,
    };

    private static (ServiceProvider Sp, SangamWorkerBuilder B) Host(SangamTestEngine e, Action<SangamWorkerBuilder> register)
    {
        var services = new ServiceCollection();
        services.AddSingleton(e.Client());
        var b = services.AddSangamWorker().WithWorkerOptions(_ => Fast(e));
        register(b);
        return (services.BuildServiceProvider(), b);
    }

    /// <summary>A scoped dependency: one instance per work item.</summary>
    public sealed class Counter
    {
        private static int _created;
        public Counter() => Id = Interlocked.Increment(ref _created);
        public int Id { get; }
    }

    public sealed class PayHandler : ISangamTaskHandler
    {
        private readonly Counter _counter;
        public PayHandler(Counter counter) => _counter = counter;

        public Task<IDictionary<string, object?>?> HandleAsync(TaskContext context, CancellationToken cancellationToken) =>
            ContractSupport.Vars(("scope", _counter.Id));
    }

    [Fact]
    public void AddSangamWorkerRegistersOneHostedService()
    {
        var services = new ServiceCollection();
        services.AddSangamWorker();
        services.AddSangamWorker();
        using var sp = services.BuildServiceProvider();
        var hosted = Assert.Single(sp.GetServices<IHostedService>());
        Assert.Same(sp.GetRequiredService<SangamWorkerHostedService>(), hosted);
    }

    [Fact]
    public async Task DelegateHandlerRunsWithTheHostLifecycle()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        var (sp, _) = Host(e, b => b.AddHandler("pay", (_, _) => ContractSupport.Vars(("ok", true)),
            s => s with { LockDuration = TimeSpan.FromMinutes(1) }));
        await using var _sp = sp;
        var hosted = sp.GetRequiredService<SangamWorkerHostedService>();
        await hosted.StartAsync(CancellationToken.None);
        Assert.NotNull(hosted.Worker);
        Assert.Equal("host-1", hosted.Worker!.WorkerId);
        await e.WaitForStateAsync(id, "COMPLETED", Wait);
        await hosted.StopAsync(CancellationToken.None);
        Assert.True(e.CompletedVariables(id)!["ok"]!.GetValue<bool>());
    }

    [Fact]
    public async Task ContainerHandlerGetsItsOwnScopePerWorkItem()
    {
        var e = new SangamTestEngine();
        var a = e.AddWork("pay");
        var b2 = e.AddWork("pay");
        var (sp, _) = Host(e, b =>
        {
            b.Services.AddScoped<Counter>();
            b.AddHandler<PayHandler>("pay");
        });
        await using var _sp = sp;
        var hosted = sp.GetRequiredService<SangamWorkerHostedService>();
        await hosted.StartAsync(CancellationToken.None);
        await e.WaitForStateAsync(a, "COMPLETED", Wait);
        await e.WaitForStateAsync(b2, "COMPLETED", Wait);
        await hosted.StopAsync(CancellationToken.None);
        Assert.NotEqual(e.CompletedVariables(a)!["scope"]!.GetValue<int>(), e.CompletedVariables(b2)!["scope"]!.GetValue<int>());
    }

    [Fact]
    public void SagasAndDirectRegistrationsAreCollectedAndDuplicateTopicsRejected()
    {
        var e = new SangamTestEngine();
        var saga = Saga.Step("charge")
            .Action("payment.charge", (_, _) => ContractSupport.Vars())
            .Compensation("payment.refund", (_, _) => ContractSupport.Vars())
            .Build();
        var (sp, _) = Host(e, b =>
        {
            b.AddSaga(saga);
            b.Services.AddSingleton(new TopicSubscription("audit", (_, _) => ContractSupport.Vars()));
        });
        using (sp)
        {
            var topics = sp.GetRequiredService<SangamWorkerHostedService>().ResolveSubscriptions().Select(s => s.Topic).OrderBy(t => t).ToArray();
            Assert.Equal(new[] { "audit", "payment.charge", "payment.refund" }, topics);
        }
        var (dup, _) = Host(e, b =>
        {
            b.AddHandler("pay", (_, _) => ContractSupport.Vars());
            b.AddHandler("pay", (_, _) => ContractSupport.Vars());
        });
        using (dup)
        {
            var hosted = dup.GetRequiredService<SangamWorkerHostedService>();
            Assert.Throws<InvalidOperationException>(() => hosted.StartAsync(CancellationToken.None).GetAwaiter().GetResult());
            Assert.Null(hosted.Worker);
        }
    }

    [Fact]
    public void MissingBaseUrlWithoutAClientIsAStartError()
    {
        var services = new ServiceCollection();
        services.AddSangamWorker().AddHandler("pay", (_, _) => ContractSupport.Vars());
        using var sp = services.BuildServiceProvider();
        var hosted = sp.GetRequiredService<SangamWorkerHostedService>();
        var ex = Assert.Throws<InvalidOperationException>(() => hosted.StartAsync(CancellationToken.None).GetAwaiter().GetResult());
        Assert.Contains("BaseUrl", ex.Message);
    }

    [Fact]
    public async Task DisabledWorkerDoesNotStart()
    {
        var services = new ServiceCollection();
        services.AddSangamWorker(o => o.Enabled = false);
        await using var sp = services.BuildServiceProvider();
        var hosted = sp.GetRequiredService<SangamWorkerHostedService>();
        await hosted.StartAsync(CancellationToken.None);
        Assert.Null(hosted.Worker);
        await hosted.StopAsync(CancellationToken.None);
    }

    [Fact]
    public void OptionsBindFromConfiguration()
    {
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["Sangam:Worker:BaseUrl"] = "http://sangam.test/",
            ["Sangam:Worker:WorkerId"] = "cfg-1",
            ["Sangam:Worker:MaxConcurrency"] = "3",
            ["Sangam:Worker:ShutdownTimeout"] = "00:00:05",
            ["Sangam:Worker:UsePriority"] = "true",
        }).Build();
        var services = new ServiceCollection();
        services.AddSangamWorker(config.GetSection(SangamWorkerHostingOptions.SectionName));
        using var sp = services.BuildServiceProvider();
        var o = sp.GetRequiredService<IOptions<SangamWorkerHostingOptions>>().Value;
        Assert.Equal("http://sangam.test/", o.BaseUrl);
        Assert.Equal("cfg-1", o.WorkerId);
        Assert.Equal(3, o.MaxConcurrency);
        Assert.Equal(TimeSpan.FromSeconds(5), o.ShutdownTimeout);
        Assert.True(o.UsePriority);
        Assert.True(o.Enabled);
    }

    [Fact]
    public async Task StopWaitsForTheInFlightHandler()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var (sp, _) = Host(e, b => b.AddHandler("pay", async (_, _) =>
        {
            started.TrySetResult();
            await release.Task;
            return null;
        }));
        await using var _sp = sp;
        var hosted = sp.GetRequiredService<SangamWorkerHostedService>();
        await hosted.StartAsync(CancellationToken.None);
        await started.Task.WaitAsync(Wait);
        var stop = hosted.StopAsync(CancellationToken.None);
        await Task.Delay(100);
        Assert.False(stop.IsCompleted);
        release.SetResult();
        await stop.WaitAsync(Wait);
        Assert.Equal("COMPLETED", e.State(id));
    }

    [Fact]
    public void SangamLogLinesReachMicrosoftLogging()
    {
        var lines = new List<string>();
        var services = new ServiceCollection();
        services.AddLogging(l => l.AddProvider(new ListProvider(lines)).SetMinimumLevel(Microsoft.Extensions.Logging.LogLevel.Debug));
        using var sp = services.BuildServiceProvider();
        var log = new MicrosoftSangamLogger(sp.GetRequiredService<Microsoft.Extensions.Logging.ILoggerFactory>().CreateLogger("t"));
        log.Info("work.completed", "reported complete", new Dictionary<string, object?> { ["workId"] = "w-1" });
        Assert.Contains(lines, l => l.Contains("work.completed") && l.Contains("reported complete"));
    }

    private sealed class ListProvider : Microsoft.Extensions.Logging.ILoggerProvider
    {
        private readonly List<string> _lines;
        public ListProvider(List<string> lines) => _lines = lines;
        public Microsoft.Extensions.Logging.ILogger CreateLogger(string categoryName) => new ListLogger(_lines);
        public void Dispose() { }
    }

    private sealed class ListLogger : Microsoft.Extensions.Logging.ILogger
    {
        private readonly List<string> _lines;
        public ListLogger(List<string> lines) => _lines = lines;
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(Microsoft.Extensions.Logging.LogLevel logLevel) => true;
        public void Log<TState>(Microsoft.Extensions.Logging.LogLevel logLevel, Microsoft.Extensions.Logging.EventId eventId, TState state,
            Exception? exception, Func<TState, Exception?, string> formatter)
        {
            lock (_lines) _lines.Add(formatter(state, exception));
        }
    }
}
