// SPDX-License-Identifier: Apache-2.0
using System.Collections.Concurrent;
using Sangam.Sdk.Logging;
using Sangam.Sdk.Testing;
using Sangam.Sdk.Worker;

namespace Sangam.Sdk.Tests.Contract;

/// <summary>Shared set-up of the SDK contract tests (T01-T04, T18; R18, ADR-D-4) on the R18 test engine.</summary>
internal static class ContractSupport
{
    public static readonly Backoff Fast = new(TimeSpan.FromMilliseconds(5), TimeSpan.FromMilliseconds(20), 1, 0);

    /// <summary>Records every log line (event name and fields) of a worker.</summary>
    public sealed class LogSink
    {
        public ConcurrentQueue<(string Event, IReadOnlyDictionary<string, object?>? Fields)> Lines { get; } = new();
        public ISangamLogger Logger => SangamLoggers.From((_, ev, _, f, _) => Lines.Enqueue((ev, f)));
        public bool Logged(string ev) => Lines.Any(l => l.Event == ev);
    }

    /// <summary>A worker on the engine: fast back-offs, short long-poll, the engine clock.</summary>
    public static SangamWorker Worker(SangamTestEngine engine, string workerId, LogSink? logs = null) =>
        new(engine.Client(logs?.Logger), new SangamWorkerOptions
        {
            WorkerId = workerId,
            MaxConcurrency = 1,
            MaxTasksPerFetch = 1,
            AsyncResponseTimeout = TimeSpan.FromMilliseconds(100),
            FetchBackoff = Fast,
            ReportBackoff = Fast,
            ShutdownTimeout = TimeSpan.FromSeconds(2),
            Clock = engine.Now,
            Logger = logs?.Logger ?? SangamLoggers.None,
        });

    public static async Task UntilAsync(Func<bool> cond, string what, int timeoutMs = 10_000)
    {
        var end = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (!cond())
        {
            if (DateTime.UtcNow > end) throw new TimeoutException("not reached in time: " + what);
            await Task.Delay(5);
        }
    }

    public static Task<IDictionary<string, object?>?> Vars(params (string Key, object? Value)[] kv) =>
        Task.FromResult<IDictionary<string, object?>?>(kv.ToDictionary(x => x.Key, x => x.Value));
}
