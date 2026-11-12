// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Errors;
using Sangam.Sdk.Models;
using Sangam.Sdk.Testing;
using Xunit;

namespace Sangam.Sdk.Tests.Testing;

/// <summary>M-021: the test engine's VI.3 state machine, driven through the SDK's WorkApi.</summary>
public sealed class SangamTestEngineTests
{
    private static readonly FetchTopic[] Pay = { new("pay", 60_000) };

    private static async Task<ExternalWork> One(SangamClient c, string worker, string topic = "pay")
    {
        var l = await c.Work.FetchAndLockAsync(new FetchAndLockRequest(worker, new[] { new FetchTopic(topic, 60_000) }, 1, 0));
        return Assert.Single(l);
    }

    [Fact]
    public async Task GrantBumpsLockVersionAndAttemptAndRecordsTheIntent()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await One(c, "w1");
        Assert.Equal(id, w.Id);
        Assert.Equal(1, w.LockVersion);
        Assert.Equal(e.OperationId(id), w.OperationId);
        Assert.Equal("INTENT", w.Operation!.State);
        Assert.True(w.Operation.MayDispatch == true);
        Assert.Empty(await c.Work.FetchAndLockAsync(new FetchAndLockRequest("w2", Pay, 1, 0)));
        await c.Work.UnlockAsync(id, new UnlockRequest("w1", 1, w.AttemptId), "u");
        Assert.Equal(2, e.LockVersion(id));
        var again = await One(c, "w2");
        Assert.Equal(3, again.LockVersion);
        Assert.NotEqual(w.AttemptId, again.AttemptId);
        Assert.Equal(w.OperationId, again.OperationId);
        Assert.Equal(2, again.Operation!.Attempts);
    }

    [Fact]
    public async Task FencingFollowsTheServerOrder()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await One(c, "w1");
        var stale = await Assert.ThrowsAsync<StaleLeaseException>(() => c.Work.CompleteAsync(id, new CompleteWorkRequest("w1", 99, w.AttemptId, null), "k1"));
        Assert.Equal("STALE_VERSION", stale.Problem.Extensions!["reason"].GetString());
        Assert.Equal("https://sangam.dev/problems/stale-lease", stale.Problem.Type);
        var owner = await Assert.ThrowsAsync<StaleLeaseException>(() => c.Work.CompleteAsync(id, new CompleteWorkRequest("other", 1, w.AttemptId, null), "k2"));
        Assert.Equal("NOT_OWNER", owner.Problem.Extensions!["reason"].GetString());
        var attempt = await Assert.ThrowsAsync<StaleLeaseException>(() => c.Work.CompleteAsync(id, new CompleteWorkRequest("w1", 1, "x", null), "k3"));
        Assert.Equal("ATTEMPT_MISMATCH", attempt.Problem.Extensions!["reason"].GetString());
        Assert.Equal("LOCKED", e.State(id));
        var nf = await Assert.ThrowsAsync<SangamApiException>(() => c.Work.CompleteAsync("nope", new CompleteWorkRequest("w1", 1, null, null), "k4"));
        Assert.Equal(404, nf.Status);
    }

    [Fact]
    public async Task IdenticalRepeatedCompletionIsAnsweredAsTheFirst()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await One(c, "w1");
        var done = new CompleteWorkRequest("w1", 1, w.AttemptId, new Dictionary<string, object?> { ["ok"] = true });
        await c.Work.CompleteAsync(id, done, "k");
        await c.Work.CompleteAsync(id, done, "k");
        Assert.Equal(1, e.Completions(id));
        Assert.Equal("SUCCEEDED", e.OperationState(id));
    }

    [Fact]
    public async Task ExpiryReoffersAndIrreversibleExpiryGoesToUnknownOutcome()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        await One(c, "w1");
        e.Advance(TimeSpan.FromMinutes(2));
        Assert.Equal("AVAILABLE", e.State(id));
        Assert.Equal(2, e.LockVersion(id));
        e.IrreversibleTopic("transfer");
        var t = e.AddWork("transfer");
        await One(c, "w1", "transfer");
        e.Advance(TimeSpan.FromMinutes(2));
        Assert.Equal("UNKNOWN_OUTCOME", e.State(t));
        Assert.Contains(e.ReconciliationItems(), r => r.WorkId == t && r.Kind == "UNKNOWN_OUTCOME");
    }

    [Fact]
    public async Task FailRetriesThenIncident()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay", retries: 2);
        var w = await One(c, "w1");
        await c.Work.FailAsync(id, new FailWorkRequest("w1", 1, w.AttemptId, "boom", null, 1, 60_000), "f1");
        Assert.Equal("AVAILABLE", e.State(id));
        Assert.Empty(await c.Work.FetchAndLockAsync(new FetchAndLockRequest("w1", Pay, 1, 0)));
        e.Advance(TimeSpan.FromMinutes(2));
        var w2 = await One(c, "w1");
        await c.Work.FailAsync(id, new FailWorkRequest("w1", w2.LockVersion!.Value, w2.AttemptId, "boom", null, 0, 0), "f2");
        Assert.Equal("INCIDENT", e.State(id));
    }

    [Fact]
    public async Task DeletedInstanceRequestsCancelAndRejectsResults()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await One(c, "w1");
        e.DeleteInstance(id);
        var hb = await c.Work.ExtendLockAsync(id, new ExtendLockRequest("w1", 1, w.AttemptId, 60_000), "h");
        Assert.True(hb!.CancelRequested == true);
        var gone = await Assert.ThrowsAsync<GoneException>(() => c.Work.CompleteAsync(id, new CompleteWorkRequest("w1", 1, w.AttemptId, null), "c"));
        Assert.Equal("GONE_INSTANCE_DELETED", gone.Problem.Code);
        await c.Work.CancelAckAsync(id, new UnlockRequest("w1", 1, w.AttemptId), "a");
        Assert.Equal("CANCELLED", e.State(id));
    }

    [Fact]
    public async Task OutcomeUnknownThenReconciliationRetryKeepsTheOperationId()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await One(c, "w1");
        var r = await c.Work.OutcomeUnknownAsync(id, new OutcomeUnknownRequest("w1", 1, w.AttemptId, "timeout", null), "u");
        Assert.Equal("UNKNOWN_OUTCOME", r!.State);
        Assert.Empty(await c.Work.FetchAndLockAsync(new FetchAndLockRequest("w2", Pay, 1, 0)));
        e.ResolveReconciliation(r.ReconciliationItemId!, "RETRY");
        var again = await One(c, "w2");
        Assert.Equal(w.OperationId, again.OperationId);
    }
}
