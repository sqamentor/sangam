// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Errors;
using Sangam.Sdk.Models;
using Sangam.Sdk.Testing;
using Sangam.Sdk.Worker;
using Xunit;

namespace Sangam.Sdk.Tests.Contract;

/// <summary>
/// T03 (SANGAM.md II §16, VI.3 rule 2): an old worker that resumes after its lease expired cannot change protected
/// state; its call gets 409 STALE_LEASE and the new lease holder completes.
/// </summary>
public sealed class ContractT03StaleWorkerTests
{
    [Fact]
    public async Task T03_StaleCompleteIsRejectedAndTheNewHolderCompletes()
    {
        var engine = new SangamTestEngine();
        var id = engine.AddWork("pay");
        var aStarted = new TaskCompletionSource();
        var releaseA = new TaskCompletionSource();
        var bStarted = new TaskCompletionSource();
        var releaseB = new TaskCompletionSource();
        long aVersion = 0, bVersion = 0;
        var logsA = new ContractSupport.LogSink();
        await using var a = ContractSupport.Worker(engine, "worker-A", logsA).Subscribe(new TopicSubscription("pay", async (ctx, _) =>
        {
            aVersion = ctx.LockVersion;
            aStarted.TrySetResult();
            await releaseA.Task;
            return new Dictionary<string, object?> { ["by"] = "A" };
        }) { LockDuration = TimeSpan.FromMinutes(1) });
        a.Start();
        await aStarted.Task.WaitAsync(TimeSpan.FromSeconds(10));
        engine.Advance(TimeSpan.FromMinutes(2)); // A's lease expires while A is paused

        await using var b = ContractSupport.Worker(engine, "worker-B").Subscribe(new TopicSubscription("pay", async (ctx, _) =>
        {
            bVersion = ctx.LockVersion;
            bStarted.TrySetResult();
            await releaseB.Task;
            return new Dictionary<string, object?> { ["by"] = "B" };
        }) { LockDuration = TimeSpan.FromMinutes(1) });
        b.Start();
        await bStarted.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(bVersion > aVersion);

        releaseA.TrySetResult(); // A resumes and reports its result
        await ContractSupport.UntilAsync(() => logsA.Logged("work.abandoned"), "A abandons");
        Assert.Equal("LOCKED", engine.State(id));
        Assert.Equal(0, engine.Completions(id));
        Assert.Single(engine.CallsOf("complete"));

        releaseB.TrySetResult();
        await engine.WaitForStateAsync(id, "COMPLETED", TimeSpan.FromSeconds(10));
        await a.StopAsync();
        await b.StopAsync();
        Assert.Equal(1, engine.Completions(id));
        Assert.Equal("B", engine.CompletedVariables(id)!["by"]!.GetValue<string>());
    }

    [Fact]
    public async Task T03_StaleLeaseSurfacesAsTheSdkException()
    {
        var engine = new SangamTestEngine();
        using var c = engine.Client();
        var id = engine.AddWork("pay");
        var topics = new[] { new FetchTopic("pay", 60_000) };
        var first = (await c.Work.FetchAndLockAsync(new FetchAndLockRequest("A", topics, 1, 0)))[0];
        engine.Advance(TimeSpan.FromMinutes(2));
        var second = (await c.Work.FetchAndLockAsync(new FetchAndLockRequest("B", topics, 1, 0)))[0];
        var e = await Assert.ThrowsAsync<StaleLeaseException>(() => c.Work.CompleteAsync(id,
            new CompleteWorkRequest("A", first.LockVersion!.Value, first.AttemptId, null), "k"));
        Assert.Equal(409, e.Status);
        Assert.Equal("STALE_LEASE", e.Problem.Code);
        Assert.False(e.IsRetryable);
        await c.Work.CompleteAsync(id, new CompleteWorkRequest("B", second.LockVersion!.Value, second.AttemptId, null), "k2");
        Assert.Equal("COMPLETED", engine.State(id));
    }
}
