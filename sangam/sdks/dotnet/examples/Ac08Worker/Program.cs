// SPDX-License-Identifier: Apache-2.0
// AC-08 conformance worker of the .NET SDK (SANGAM.md IX.8 AC-08, M-023). The cross-language orchestrator starts the
// same program in every Tier-1 SDK against one Sangam server and compares the JSON lines they print.
//
// Environment: SANGAM_BASE_URL, SANGAM_TOKEN (optional), AC08_TOPIC, AC08_WORKER_ID, AC08_SCENARIO
// (complete | bpmn-error | fail-retry | outcome-unknown | stale-lease), AC08_TASKS (default 1), AC08_TIMEOUT_S
// (default 60), AC08_LOCK_MS (default 30000). Output: one JSON line per task, then exit 0; exit 1 on timeout.
//
// Every scenario runs on the SDK worker runtime except stale-lease: the runtime heart-beats at lease/3, so its lease
// never lapses while it works. That scenario is the "old worker resumes" case (T03) and uses the WorkApi directly:
// fetch, stop for AC08_LOCK_MS + 2 s without heart-beating, then complete and expect 409 STALE_LEASE.
using System.Collections.Concurrent;
using System.Text.Json;
using Sangam.Sdk;
using Sangam.Sdk.Auth;
using Sangam.Sdk.Errors;
using Sangam.Sdk.Logging;
using Sangam.Sdk.Models;
using Sangam.Sdk.Worker;

static string? Env(string name) => Environment.GetEnvironmentVariable(name) is { Length: > 0 } v ? v : null;

var baseUrl = Env("SANGAM_BASE_URL");
var topic = Env("AC08_TOPIC");
if (baseUrl is null || topic is null)
{
    Console.Error.WriteLine("SANGAM_BASE_URL and AC08_TOPIC are required");
    return 2;
}
var workerId = Env("AC08_WORKER_ID") ?? "ac08-dotnet";
var scenario = Env("AC08_SCENARIO") ?? "complete";
var tasks = int.Parse(Env("AC08_TASKS") ?? "1", System.Globalization.CultureInfo.InvariantCulture);
var timeout = TimeSpan.FromSeconds(int.Parse(Env("AC08_TIMEOUT_S") ?? "60", System.Globalization.CultureInfo.InvariantCulture));
var lockMs = long.Parse(Env("AC08_LOCK_MS") ?? "30000", System.Globalization.CultureInfo.InvariantCulture);
var outLock = new object();
var failedOnce = new ConcurrentDictionary<string, bool>();
var printed = new ConcurrentDictionary<string, bool>();
var done = new SemaphoreSlim(0);

void Print(IReadOnlyDictionary<string, object?> f, string result, string? detail)
{
    var line = new Dictionary<string, object?>
    {
        ["lang"] = "dotnet",
        ["workId"] = f.GetValueOrDefault("workId"),
        ["operationId"] = f.GetValueOrDefault("operationId"),
        ["lockVersion"] = f.GetValueOrDefault("lockVersion"),
        ["attemptId"] = f.GetValueOrDefault("attemptId"),
        ["scenario"] = scenario,
        ["result"] = result,
        ["detail"] = detail,
    };
    lock (outLock) Console.Out.WriteLine(JsonSerializer.Serialize(line));
}

var resultLogger = SangamLoggers.From((_, ev, msg, f, err) =>
{
    if (f is null || f.GetValueOrDefault("workId") is not string workId) return;
    string? result = ev switch
    {
        "work.complete" => failedOnce.ContainsKey(workId) ? "failed-then-completed" : "completed",
        "work.bpmn-error" => "bpmn-error",
        "work.outcome-unknown" => "outcome-unknown",
        "work.abandoned" => "stale-lease",
        "work.report.failed" or "work.report.gave-up" => "error",
        _ => null,
    };
    if (result is null || !printed.TryAdd(workId, true)) return;
    Print(f, result, err?.Message ?? msg);
    done.Release();
});

using var client = new SangamClient(new SangamClientOptions
{
    BaseUrl = new Uri(baseUrl),
    TokenProvider = Env("SANGAM_TOKEN") is { } token ? TokenProviders.Static(token) : TokenProviders.None,
    Logger = resultLogger,
});

if (scenario == "stale-lease")
{
    var end = DateTime.UtcNow + timeout;
    var seen = 0;
    while (seen < tasks && DateTime.UtcNow < end)
    {
        var items = await client.Work.FetchAndLockAsync(new FetchAndLockRequest(workerId, new[] { new FetchTopic(topic, lockMs) }, 1, 5_000));
        foreach (var w in items)
        {
            seen++;
            await Task.Delay(TimeSpan.FromMilliseconds(lockMs + 2_000));
            var f = new Dictionary<string, object?>
            {
                ["workId"] = w.Id, ["operationId"] = w.OperationId, ["lockVersion"] = w.LockVersion, ["attemptId"] = w.AttemptId,
            };
            try
            {
                await client.Work.CompleteAsync(w.Id!, new CompleteWorkRequest(workerId, w.LockVersion!.Value, w.AttemptId, null),
                    $"{w.OperationId}:complete:{w.LockVersion}");
                Print(f, "completed", "the expired lease was accepted");
            }
            catch (StaleLeaseException e)
            {
                Print(f, "stale-lease", e.Problem.Extensions is { } x && x.TryGetValue("reason", out var r) ? r.ToString() : e.Message);
            }
            catch (Exception e)
            {
                Print(f, "error", e.Message);
            }
        }
    }
    return seen >= tasks ? 0 : 1;
}

IDictionary<string, object?> Outputs(TaskContext ctx) => new Dictionary<string, object?>
{
    ["out_amount"] = ctx.Variable<decimal?>("amount") is { } a ? TypedValue.Decimal(a) : null,
    ["out_count"] = ctx.Variable<long?>("count") is { } c ? TypedValue.Long(c) : null,
    ["out_when"] = ctx.Variable<DateTimeOffset?>("when") is { } t ? TypedValue.Date(t) : null,
    ["out_day"] = ctx.Variable<DateOnly?>("day") is { } d ? TypedValue.LocalDate(d) : null,
    ["out_note"] = ctx.Variable<string>("note") is { } n ? TypedValue.String(n) : null,
};

Task<IDictionary<string, object?>?> Handle(TaskContext ctx, CancellationToken ct)
{
    switch (scenario)
    {
        case "bpmn-error": throw new BpmnErrorException("AC08_ERROR", "ac08 business error");
        case "outcome-unknown": throw new OutcomeUnknownException("ac08: downstream outcome unknown");
        case "fail-retry":
            if (failedOnce.TryAdd(ctx.WorkId, true))
                throw new TaskFailedException("ac08 transient failure", retries: 1, retryTimeout: TimeSpan.Zero);
            break;
    }
    return Task.FromResult<IDictionary<string, object?>?>(Outputs(ctx));
}

await using var worker = new SangamWorker(client, new SangamWorkerOptions
{
    WorkerId = workerId,
    MaxConcurrency = Math.Clamp(tasks, 1, 8),
    Logger = resultLogger,
});
worker.Subscribe(new TopicSubscription(topic, Handle) { LockDuration = TimeSpan.FromMilliseconds(lockMs), Retries = 2 });
worker.Start();
var deadline = DateTime.UtcNow + timeout;
for (var i = 0; i < tasks; i++)
{
    var left = deadline - DateTime.UtcNow;
    if (left <= TimeSpan.Zero || !await done.WaitAsync(left))
    {
        await worker.StopAsync();
        return 1;
    }
}
await worker.StopAsync();
return 0;
