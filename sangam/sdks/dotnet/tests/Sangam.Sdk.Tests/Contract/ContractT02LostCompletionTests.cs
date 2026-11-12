// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Testing;
using Sangam.Sdk.Worker;
using Xunit;

namespace Sangam.Sdk.Tests.Contract;

/// <summary>
/// T02 (SANGAM.md II §16, VI.3 rules 4-5): a lost answer after the downstream effect gives a safe recovery (one
/// completion) or an explicit unknown outcome, never a duplicate effect.
/// </summary>
public sealed class ContractT02LostCompletionTests
{
    [Fact]
    public async Task T02_LostCompleteResponseIsRetriedWithTheSameKeyAndCompletesOnce()
    {
        var engine = new SangamTestEngine();
        var id = engine.AddWork("pay");
        engine.LoseNextResponse("complete");
        var effects = 0;
        var logs = new ContractSupport.LogSink();
        await using var w = ContractSupport.Worker(engine, "w1", logs).Subscribe(new TopicSubscription("pay", (_, _) =>
        {
            Interlocked.Increment(ref effects);
            return ContractSupport.Vars(("paymentRef", "PAY-1"));
        }) { LockDuration = TimeSpan.FromMinutes(1) });
        w.Start();
        await ContractSupport.UntilAsync(() => engine.CallsOf("complete").Count >= 2, "complete retried");
        await ContractSupport.UntilAsync(() => logs.Logged("work.complete"), "completion reported");
        await w.StopAsync();
        var completes = engine.CallsOf("complete");
        Assert.Equal(completes[0].IdempotencyKey, completes[1].IdempotencyKey);
        Assert.Equal(completes[0].Body!.ToJsonString(), completes[1].Body!.ToJsonString());
        Assert.Equal(1, effects);
        Assert.Equal(1, engine.Completions(id));
        Assert.Equal("COMPLETED", engine.State(id));
        Assert.Equal("SUCCEEDED", engine.OperationState(id));
    }

    [Fact]
    public async Task T02_UnknownDownstreamOutcomeOpensAReconciliationItemAndIsNeverReoffered()
    {
        var engine = new SangamTestEngine();
        var id = engine.AddWork("pay");
        var runs = 0;
        await using var w = ContractSupport.Worker(engine, "w1").Subscribe(new TopicSubscription("pay", (_, _) =>
        {
            Interlocked.Increment(ref runs);
            throw new OutcomeUnknownException("bank timed out after the debit request");
        }) { LockDuration = TimeSpan.FromMinutes(1) });
        w.Start();
        await engine.WaitForStateAsync(id, "UNKNOWN_OUTCOME", TimeSpan.FromSeconds(10));
        engine.Advance(TimeSpan.FromHours(1));
        await Task.Delay(300); // the worker keeps polling; nothing may be handed out
        await w.StopAsync();
        Assert.Equal(1, runs);
        Assert.Equal(1, engine.Grants(id));
        Assert.Equal("UNKNOWN_OUTCOME", engine.State(id));
        Assert.Contains(engine.ReconciliationItems(), r => r.WorkId == id && r.Kind == "UNKNOWN_OUTCOME" && r.State == "OPEN");
    }

    [Fact]
    public async Task T02_ReconciliationRetryReusesTheOperationId()
    {
        var engine = new SangamTestEngine();
        var id = engine.AddWork("pay");
        var ops = new System.Collections.Concurrent.ConcurrentQueue<string>();
        var runs = 0;
        await using var w = ContractSupport.Worker(engine, "w1").Subscribe(new TopicSubscription("pay", (ctx, _) =>
        {
            ops.Enqueue(ctx.OperationId);
            if (Interlocked.Increment(ref runs) == 1) throw new OutcomeUnknownException("unknown");
            return ContractSupport.Vars();
        }) { LockDuration = TimeSpan.FromMinutes(1) });
        w.Start();
        await engine.WaitForStateAsync(id, "UNKNOWN_OUTCOME", TimeSpan.FromSeconds(10));
        engine.ResolveReconciliation(engine.ReconciliationItems()[0].Id, "RETRY");
        await engine.WaitForStateAsync(id, "COMPLETED", TimeSpan.FromSeconds(10));
        await w.StopAsync();
        var list = ops.ToList();
        Assert.Equal(2, list.Count);
        Assert.Equal(list[0], list[1]);
    }
}
