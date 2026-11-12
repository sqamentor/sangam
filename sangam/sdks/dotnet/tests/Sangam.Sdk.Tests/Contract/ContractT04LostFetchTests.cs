// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Errors;
using Sangam.Sdk.Models;
using Sangam.Sdk.Testing;
using Sangam.Sdk.Worker;
using Xunit;

namespace Sangam.Sdk.Tests.Contract;

/// <summary>
/// T04 (SANGAM.md II §16, VI.3 rule 1; REST form of "the stream breaks while the worker still runs"): losing the
/// connection does not release acknowledged (LOCKED) work; only lease expiry does, and a lease holder that keeps
/// heart-beating is never re-offered.
/// </summary>
public sealed class ContractT04LostFetchTests
{
    private static readonly FetchTopic[] Topics = { new("pay", 60_000) };

    [Fact]
    public async Task T04_LockedWorkIsNotReofferedAfterALostFetchUntilItsLeaseExpires()
    {
        var engine = new SangamTestEngine();
        using var c = engine.Client();
        var id = engine.AddWork("pay");
        engine.LoseNextResponse("fetch-and-lock");
        await Assert.ThrowsAsync<TransportException>(() => c.Work.FetchAndLockAsync(new FetchAndLockRequest("A", Topics, 1, 0)));
        Assert.Equal("LOCKED", engine.State(id));
        Assert.Equal(1, engine.LockVersion(id));
        Assert.Empty(await c.Work.FetchAndLockAsync(new FetchAndLockRequest("B", Topics, 1, 0)));
        Assert.Empty(await c.Work.FetchAndLockAsync(new FetchAndLockRequest("A", Topics, 1, 0)));
        engine.Advance(TimeSpan.FromMinutes(2));
        Assert.Equal("AVAILABLE", engine.State(id));
        Assert.Equal(2, engine.LockVersion(id));
        var again = (await c.Work.FetchAndLockAsync(new FetchAndLockRequest("B", Topics, 1, 0)))[0];
        Assert.Equal(3, again.LockVersion);
        Assert.Equal(2, engine.Grants(id));
    }

    [Fact]
    public async Task T04_HeartbeatingHolderIsNeverReoffered()
    {
        var engine = new SangamTestEngine();
        var id = engine.AddWork("pay");
        var runsA = 0;
        var runsB = 0;
        // lease 3 s, heartbeat every 1 s; the handler runs about 2.5 leases
        await using var a = ContractSupport.Worker(engine, "A").Subscribe(new TopicSubscription("pay", async (_, _) =>
        {
            Interlocked.Increment(ref runsA);
            await Task.Delay(7_500);
            return new Dictionary<string, object?> { ["by"] = "A" };
        }) { LockDuration = TimeSpan.FromSeconds(3) });
        a.Start();
        await ContractSupport.UntilAsync(() => Volatile.Read(ref runsA) == 1, "A started");
        await using var b = ContractSupport.Worker(engine, "B").Subscribe(new TopicSubscription("pay", (_, _) =>
        {
            Interlocked.Increment(ref runsB);
            return ContractSupport.Vars(("by", "B"));
        }) { LockDuration = TimeSpan.FromSeconds(3) });
        b.Start();
        await engine.WaitForStateAsync(id, "COMPLETED", TimeSpan.FromSeconds(20));
        await a.StopAsync();
        await b.StopAsync();
        Assert.Equal(0, runsB);
        Assert.Equal(1, engine.Grants(id));
        Assert.True(engine.CallsOf("extend-lock").Count >= 2);
        Assert.Equal("A", engine.CompletedVariables(id)!["by"]!.GetValue<string>());
    }
}
