// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Errors;
using Sangam.Sdk.Models;
using Sangam.Sdk.Testing;
using Xunit;
using WorkStepRequest = Sangam.Sdk.Generated.Models.WorkStepRequest;

namespace Sangam.Sdk.Tests.Testing;

/// <summary>
/// M-025 / R37: the test engine's step journal (GET/PUT /work/{id}/steps/{name}, GET /work/{id}/steps) follows the
/// server's transitions and fencing, driven through the generated WorkApi.
/// </summary>
public sealed class EngineStepsTests
{
    private static async Task<Models.ExternalWork> Lease(SangamClient c, string worker)
    {
        var l = await c.Work.FetchAndLockAsync(new FetchAndLockRequest(worker, new[] { new FetchTopic("pay", 60_000) }, 1, 0));
        return Assert.Single(l);
    }

    private static WorkStepRequest Req(Models.ExternalWork w, string state, object? result = null, string? providerRef = null, string? error = null) =>
        new()
        {
            WorkerId = "w1",
            LockVersion = w.LockVersion,
            AttemptId = w.AttemptId,
            State = state,
            Result = result,
            ProviderRef = providerRef,
            Error = error,
        };

    [Fact]
    public async Task UnrecordedStepIs404AndOutcomeNeedsAnIntent()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await Lease(c, "w1");
        var nf = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.GetWorkStepAsync(id, "charge", "w1", 1, w.AttemptId!));
        Assert.Equal(404, nf.Status);
        var noIntent = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "SUCCEEDED", 1)));
        Assert.Equal(409, noIntent.Status);
        Assert.Equal("STEP_INTENT_REQUIRED", noIntent.Problem.Code);
        var noIntentFailed = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "FAILED")));
        Assert.Equal("STEP_INTENT_REQUIRED", noIntentFailed.Problem.Code);
    }

    [Fact]
    public async Task IntentThenSucceededIsFinalAndAnIdenticalRepeatIsANoOp()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await Lease(c, "w1");
        var intent = await c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT"));
        Assert.Equal("INTENT", intent!.State);
        Assert.Equal(e.OperationId(id), intent.OperationId);
        await c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT")); // identical repeat by the same attempt
        var done = await c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "SUCCEEDED", new Dictionary<string, object?> { ["ref"] = "P-1" }, "P-1"));
        Assert.Equal("SUCCEEDED", done!.State);
        Assert.Equal("P-1", done.ProviderRef);
        Assert.Equal(w.AttemptId, done.AttemptId);
        var again = await c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "SUCCEEDED", new Dictionary<string, object?> { ["ref"] = "P-1" }, "P-1"));
        Assert.Equal("SUCCEEDED", again!.State);
        var other = await Assert.ThrowsAsync<SangamApiException>(() =>
            c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "SUCCEEDED", new Dictionary<string, object?> { ["ref"] = "P-2" }, "P-2")));
        Assert.Equal(409, other.Status);
        Assert.Equal("STEP_ALREADY_RECORDED", other.Problem.Code);
        var reIntent = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT")));
        Assert.Equal("STEP_ALREADY_RECORDED", reIntent.Problem.Code);
        var read = await c.Api.Work.GetWorkStepAsync(id, "charge", "w1", 1, w.AttemptId!);
        Assert.Equal("SUCCEEDED", read!.State);
        Assert.Equal("P-1", read.ProviderRef);
    }

    [Fact]
    public async Task FailedStepMayBeStartedAgain()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await Lease(c, "w1");
        await c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT"));
        var failed = await c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "FAILED", error: "card refused"));
        Assert.Equal("FAILED", failed!.State);
        Assert.Equal("card refused", failed.Error);
        var again = await c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT"));
        Assert.Equal("INTENT", again!.State);
    }

    [Fact]
    public async Task JournalCallsAreFencedAndValidated()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await Lease(c, "w1");
        var stale = await Assert.ThrowsAsync<StaleLeaseException>(() => c.Api.Work.GetWorkStepAsync(id, "charge", "w1", 99, w.AttemptId!));
        Assert.Equal("STALE_VERSION", stale.Problem.Extensions!["reason"].GetString());
        var owner = await Assert.ThrowsAsync<StaleLeaseException>(() =>
            c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT") with { WorkerId = "other" }));
        Assert.Equal("NOT_OWNER", owner.Problem.Extensions!["reason"].GetString());
        var attempt = await Assert.ThrowsAsync<StaleLeaseException>(() =>
            c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT") with { AttemptId = "x" }));
        Assert.Equal("ATTEMPT_MISMATCH", attempt.Problem.Extensions!["reason"].GetString());
        var noAttempt = await Assert.ThrowsAsync<SangamApiException>(() =>
            c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT") with { AttemptId = null }));
        Assert.Equal(400, noAttempt.Status);
        Assert.Equal("VALIDATION_FAILED", noAttempt.Problem.Code);
        var badName = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.RecordWorkStepAsync(id, new string('x', 129), Req(w, "INTENT")));
        Assert.Equal("VALIDATION_FAILED", badName.Problem.Code);
        var control = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.RecordWorkStepAsync(id, "a\u0001b", Req(w, "INTENT")));
        Assert.Equal(400, control.Status);
        var badState = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "DONE")));
        Assert.Equal("VALIDATION_FAILED", badState.Problem.Code);
        var unknown = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.RecordWorkStepAsync("nope", "charge", Req(w, "INTENT")));
        Assert.Equal(404, unknown.Status);
        e.DeleteInstance(id);
        var gone = await Assert.ThrowsAsync<GoneException>(() => c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT")));
        Assert.Equal("GONE_INSTANCE_DELETED", gone.Problem.Code);
    }

    [Fact]
    public async Task StepsAreKeyedByOperationAcrossAttemptsAndListedInRecordingOrder()
    {
        var e = new SangamTestEngine();
        using var c = e.Client();
        var id = e.AddWork("pay");
        var w = await Lease(c, "w1");
        await c.Api.Work.RecordWorkStepAsync(id, "reserve", Req(w, "INTENT"));
        await c.Api.Work.RecordWorkStepAsync(id, "reserve", Req(w, "SUCCEEDED", TypedValue.Decimal(12.50m)));
        await c.Api.Work.RecordWorkStepAsync(id, "charge", Req(w, "INTENT"));
        await c.Work.UnlockAsync(id, new UnlockRequest("w1", 1, w.AttemptId), "u");
        var w2 = await Lease(c, "w1");
        Assert.NotEqual(w.AttemptId, w2.AttemptId);
        var seen = await c.Api.Work.GetWorkStepAsync(id, "charge", "w1", w2.LockVersion!.Value, w2.AttemptId!);
        Assert.Equal("INTENT", seen!.State);
        Assert.Equal(w.AttemptId, seen.AttemptId);
        var list = await c.Api.Work.ListWorkStepsAsync(id);
        Assert.Equal(new[] { "reserve", "charge" }, list!.Select(s => s.Name).ToArray());
        Assert.Equal(new[] { "SUCCEEDED", "INTENT" }, list!.Select(s => s.State).ToArray());
        var missing = await Assert.ThrowsAsync<SangamApiException>(() => c.Api.Work.ListWorkStepsAsync("nope"));
        Assert.Equal(404, missing.Status);
    }
}
