// SPDX-License-Identifier: Apache-2.0
using System.Collections.Concurrent;
using Sangam.Sdk.Models;
using Sangam.Sdk.Testing;
using Sangam.Sdk.Tests.Contract;
using Sangam.Sdk.Worker;
using Xunit;
using WorkStepRequest = Sangam.Sdk.Generated.Models.WorkStepRequest;

namespace Sangam.Sdk.Tests.Worker;

/// <summary>
/// M-025 / R37: <c>TaskContext.StepAsync</c> on the test engine's step journal: replay of a SUCCEEDED step, the INTENT
/// crash window per capability class (lookup, idempotent, outcome-unknown), FAILED on StepNoEffectException, INTENT on an
/// uncertain error, name validation and lease loss.
/// </summary>
public sealed class DurableStepsTests
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(10);

    private static TopicSubscription Sub(TaskHandler h) => new("pay", h) { LockDuration = TimeSpan.FromMinutes(1), Retries = 2 };

    /// <summary>Records <paramref name="state"/> for step <paramref name="name"/> under the work's operation, as an earlier attempt would.</summary>
    private static async Task SeedAsync(SangamTestEngine e, string id, string name, string state, object? result = null, string? providerRef = null)
    {
        using var c = e.Client();
        var w = Assert.Single(await c.Work.FetchAndLockAsync(new FetchAndLockRequest("seed", new[] { new FetchTopic("pay", 60_000) }, 1, 0)));
        WorkStepRequest Req(string s, object? r, string? p) => new()
        {
            WorkerId = "seed", LockVersion = w.LockVersion, AttemptId = w.AttemptId, State = s, Result = r, ProviderRef = p,
            Error = s == "FAILED" ? "refused" : null,
        };
        await c.Api.Work.RecordWorkStepAsync(id, name, Req("INTENT", null, null));
        if (state != "INTENT") await c.Api.Work.RecordWorkStepAsync(id, name, Req(state, result, providerRef));
        await c.Work.UnlockAsync(id, new UnlockRequest("seed", w.LockVersion!.Value, w.AttemptId), "seed-unlock");
    }

    private static async Task<List<Generated.Models.WorkStep>> StepsAsync(SangamTestEngine e, string id)
    {
        using var c = e.Client();
        return await c.Api.Work.ListWorkStepsAsync(id) ?? new List<Generated.Models.WorkStep>();
    }

    [Fact]
    public async Task FirstRunRecordsIntentThenSucceededWithResultAndProviderRef()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        var calls = 0;
        string? key = null;
        await using var w = ContractSupport.Worker(e, "w1").Subscribe(Sub(async (ctx, ct) =>
        {
            var r = await ctx.StepAsync("charge", (step, _) =>
            {
                Interlocked.Increment(ref calls);
                key = step.IdempotencyKey;
                Assert.Null(step.PreviousState);
                step.ProviderRef = "PAY-1";
                return Task.FromResult("charged");
            }, cancellationToken: ct);
            return new Dictionary<string, object?> { ["r"] = r };
        }));
        w.Start();
        await e.WaitForStateAsync(id, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.Equal(1, calls);
        Assert.Equal(e.OperationId(id) + ":charge", key);
        var step = Assert.Single(await StepsAsync(e, id));
        Assert.Equal("SUCCEEDED", step.State);
        Assert.Equal("PAY-1", step.ProviderRef);
        Assert.Equal("charged", e.CompletedVariables(id)!["r"]!.GetValue<string>());
        var puts = e.Calls().Where(c => c.Method == "PUT").Select(c => c.Body!["state"]!.GetValue<string>()).ToArray();
        Assert.Equal(new[] { "INTENT", "SUCCEEDED" }, puts);
    }

    [Fact]
    public async Task SucceededStepIsReplayedWithoutCallingAndTypedValuesAreUnwrapped()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        await SeedAsync(e, id, "charge", "SUCCEEDED", TypedValue.Decimal(12.50m), "PAY-9");
        var calls = 0;
        decimal replayed = 0;
        var logs = new ContractSupport.LogSink();
        await using var w = ContractSupport.Worker(e, "w1", logs).Subscribe(Sub(async (ctx, ct) =>
        {
            replayed = await ctx.StepAsync<decimal>("charge", (_, _) =>
            {
                Interlocked.Increment(ref calls);
                return Task.FromResult(99m);
            });
            return null;
        }));
        w.Start();
        await e.WaitForStateAsync(id, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.Equal(0, calls);
        Assert.Equal(12.50m, replayed);
        Assert.True(logs.Logged("step.replayed"));
    }

    [Fact]
    public async Task IntentWithoutLookupOrIdempotencyReportsOutcomeUnknown()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        await SeedAsync(e, id, "charge", "INTENT");
        var calls = 0;
        await using var w = ContractSupport.Worker(e, "w1").Subscribe(Sub(async (ctx, ct) =>
        {
            await ctx.StepAsync("charge", (_, _) => { Interlocked.Increment(ref calls); return Task.FromResult(1); });
            return null;
        }));
        w.Start();
        await e.WaitForStateAsync(id, "UNKNOWN_OUTCOME", Wait);
        await w.StopAsync();
        Assert.Equal(0, calls);
        Assert.Contains(e.ReconciliationItems(), r => r.WorkId == id && r.Kind == "UNKNOWN_OUTCOME");
        var report = Assert.Single(e.CallsOf("outcome-unknown"));
        Assert.Contains("outcome is unknown (R37)", report.Body!["detail"]!.GetValue<string>());
        Assert.Equal(e.OperationId(id) + ":charge", report.Body["evidence"]!["idempotencyKey"]!.GetValue<string>());
        Assert.Equal("INTENT", Assert.Single(await StepsAsync(e, id)).State);
    }

    [Fact]
    public async Task IntentOfAnIdempotentCallRunsAgainWithTheSameKey()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        await SeedAsync(e, id, "charge", "INTENT");
        var keys = new ConcurrentQueue<string>();
        string? previous = null;
        await using var w = ContractSupport.Worker(e, "w1").Subscribe(Sub(async (ctx, ct) =>
        {
            await ctx.StepAsync("charge", (step, _) =>
            {
                keys.Enqueue(step.IdempotencyKey);
                previous = step.PreviousState;
                return Task.FromResult(7);
            }, StepOptions<int>.IdempotentCall());
            return null;
        }));
        w.Start();
        await e.WaitForStateAsync(id, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.Equal(new[] { e.OperationId(id) + ":charge" }, keys.ToArray());
        Assert.Equal("INTENT", previous);
        Assert.Equal("SUCCEEDED", Assert.Single(await StepsAsync(e, id)).State);
    }

    [Fact]
    public async Task LookupThatFindsTheEffectRecordsItWithoutCalling()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        await SeedAsync(e, id, "charge", "INTENT");
        var calls = 0;
        string? result = null;
        var logs = new ContractSupport.LogSink();
        var options = StepOptions<string>.WithLookup((step, _) =>
        {
            step.ProviderRef = "PAY-FOUND";
            return Task.FromResult(StepLookupResult<string>.Found("found:" + step.IdempotencyKey));
        });
        await using var w = ContractSupport.Worker(e, "w1", logs).Subscribe(Sub(async (ctx, ct) =>
        {
            result = await ctx.StepAsync("charge", (_, _) => { Interlocked.Increment(ref calls); return Task.FromResult("called"); }, options);
            return null;
        }));
        w.Start();
        await e.WaitForStateAsync(id, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.Equal(0, calls);
        Assert.Equal("found:" + e.OperationId(id) + ":charge", result);
        var step = Assert.Single(await StepsAsync(e, id));
        Assert.Equal("SUCCEEDED", step.State);
        Assert.Equal("PAY-FOUND", step.ProviderRef);
        Assert.True(logs.Logged("step.looked-up"));
    }

    [Fact]
    public async Task LookupWithoutEffectRunsTheCallAgain()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        await SeedAsync(e, id, "charge", "INTENT");
        var calls = 0;
        var options = StepOptions<string>.WithLookup((_, _) => Task.FromResult(StepLookupResult<string>.NoEffect));
        await using var w = ContractSupport.Worker(e, "w1").Subscribe(Sub(async (ctx, ct) =>
        {
            await ctx.StepAsync("charge", (_, _) => { Interlocked.Increment(ref calls); return Task.FromResult("called"); }, options);
            return null;
        }));
        w.Start();
        await e.WaitForStateAsync(id, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.Equal(1, calls);
        Assert.Equal("SUCCEEDED", Assert.Single(await StepsAsync(e, id)).State);
    }

    [Fact]
    public async Task NoEffectFailureRecordsFailedAndTheNextAttemptRunsAgain()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay", retries: 3);
        var previous = new ConcurrentQueue<string?>();
        await using var w = ContractSupport.Worker(e, "w1").Subscribe(Sub(async (ctx, ct) =>
        {
            await ctx.StepAsync("charge", (step, _) =>
            {
                previous.Enqueue(step.PreviousState);
                if (step.PreviousState is null) throw new StepNoEffectException("card refused");
                return Task.FromResult(1);
            });
            return null;
        }));
        w.Start();
        await ContractSupport.UntilAsync(() => e.CallsOf("fail").Count == 1, "first attempt failed");
        var failed = Assert.Single(await StepsAsync(e, id));
        Assert.Equal("FAILED", failed.State);
        Assert.Equal("card refused", failed.Error);
        e.Advance(TimeSpan.FromMinutes(30)); // past the retry back-off
        await e.WaitForStateAsync(id, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.Equal(new string?[] { null, "FAILED" }, previous.ToArray());
        Assert.Equal("SUCCEEDED", Assert.Single(await StepsAsync(e, id)).State);
    }

    [Fact]
    public async Task UncertainErrorLeavesTheIntentAndPropagates()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay", retries: 3);
        Exception? seen = null;
        await using var w = ContractSupport.Worker(e, "w1").Subscribe(Sub(async (ctx, ct) =>
        {
            try
            {
                await ctx.StepAsync<int>("charge", (_, _) => throw new TimeoutException("bank timed out"));
            }
            catch (Exception x)
            {
                seen = x;
                throw;
            }
            return null;
        }));
        w.Start();
        await ContractSupport.UntilAsync(() => e.CallsOf("fail").Count == 1, "attempt failed");
        await w.StopAsync();
        Assert.IsType<TimeoutException>(seen);
        Assert.Equal("INTENT", Assert.Single(await StepsAsync(e, id)).State);
    }

    [Fact]
    public async Task InvalidStepNamesAreUsageErrors()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        var errors = new ConcurrentQueue<Exception>();
        await using var w = ContractSupport.Worker(e, "w1").Subscribe(Sub(async (ctx, ct) =>
        {
            foreach (var bad in new[] { "", new string('x', 129), "tab\there", "naïve" })
            {
                try { await ctx.StepAsync(bad, (_, _) => Task.FromResult(1)); }
                catch (Exception x) { errors.Enqueue(x); }
            }
            return null;
        }));
        w.Start();
        await e.WaitForStateAsync(id, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.Equal(4, errors.Count);
        Assert.All(errors, x => Assert.IsType<ArgumentException>(x));
        Assert.DoesNotContain(e.Calls(), c => c.Path.Contains("/steps", StringComparison.Ordinal));
    }

    [Fact]
    public async Task StaleLeaseOnTheJournalMarksTheLeaseLostAndCancels()
    {
        var e = new SangamTestEngine();
        var id = e.AddWork("pay");
        Exception? seen = null;
        var leaseLost = false;
        var attempts = 0;
        await using var w = ContractSupport.Worker(e, "w1").Subscribe(Sub(async (ctx, ct) =>
        {
            if (Interlocked.Increment(ref attempts) > 1) return null;
            try
            {
                await ctx.StepAsync("charge", (_, _) =>
                {
                    e.Advance(TimeSpan.FromMinutes(2)); // the lease expires during the remote call
                    return Task.FromResult(1);
                });
            }
            catch (Exception x)
            {
                seen = x;
                leaseLost = ctx.IsLeaseLost;
                throw;
            }
            return null;
        }));
        w.Start();
        await ContractSupport.UntilAsync(() => seen is not null, "step saw the lost lease");
        await w.StopAsync();
        Assert.IsAssignableFrom<OperationCanceledException>(seen);
        Assert.True(leaseLost);
        Assert.Contains("lease lost", seen!.Message);
        Assert.Equal("INTENT", Assert.Single(await StepsAsync(e, id)).State); // the SUCCEEDED record was refused
    }
}
