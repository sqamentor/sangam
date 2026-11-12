// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using Sangam.Sdk.Errors;
using Sangam.Sdk.Models;
using Sangam.Sdk.Worker;
using Xunit;

namespace Sangam.Sdk.Tests;

/// <summary>Worker runtime: loop, stored-outcome completion (SL-16), outcome mapping, report retries, fencing.</summary>
public sealed class WorkerTests
{
    private const string Succeeded =
        "{\"state\":\"SUCCEEDED\",\"attempts\":2,\"capabilityClass\":\"A_IDEMPOTENT_WITH_LOOKUP\",\"mayDispatch\":false," +
        "\"storedOutcome\":{\"output\":{\"paymentRef\":\"PAY-77\",\"amount\":{\"type\":\"Decimal\",\"value\":\"12.50\"}}," +
        "\"providerRef\":\"PAY-77\",\"duplicate\":false}}";

    private static TaskHandler Returns(IDictionary<string, object?>? vars) => (_, _) => Task.FromResult(vars);

    private static TaskHandler Throws(Exception e) => (_, _) => Task.FromException<IDictionary<string, object?>?>(e);

    /// <summary>Runs one leased item through a started worker and returns its report call.</summary>
    private static async Task<Recorded> RunOne(FakeServer server, TopicSubscription sub, string item)
    {
        server.Enqueue(item);
        var worker = server.Worker().Subscribe(sub);
        worker.Start();
        try
        {
            return await server.WaitForAsync(c => FakeServer.ReportActions.Contains(c.Action));
        }
        finally
        {
            await worker.StopAsync();
        }
    }

    private static TopicSubscription Sub(TaskHandler h) => new("pay", h) { LockDuration = TimeSpan.FromMinutes(1) };

    private static string Str(Recorded r, string name) => r.Body!.Value.GetProperty(name).ToString();

    // ---------------------------------------------------------------- stored outcome (SL-16)

    [Fact]
    public async Task CompletesFromTheStoredOutcomeWithoutCallingTheHandler()
    {
        var server = new FakeServer();
        var calls = 0;
        var r = await RunOne(server, Sub((_, _) =>
        {
            calls++;
            return Task.FromResult<IDictionary<string, object?>?>(new Dictionary<string, object?> { ["paymentRef"] = "NEW" });
        }), FakeServer.Work(Succeeded));
        Assert.Equal(0, calls);
        Assert.Equal("complete", r.Action);
        Assert.Equal("op-1:complete:3", r.IdempotencyKey);
        var body = r.Body!.Value;
        Assert.Equal("worker-1", body.GetProperty("workerId").GetString());
        Assert.Equal(3, body.GetProperty("lockVersion").GetInt64());
        Assert.Equal("att-1", body.GetProperty("attemptId").GetString());
        Assert.Equal("{\"paymentRef\":\"PAY-77\",\"amount\":{\"type\":\"Decimal\",\"value\":\"12.50\"}}",
            body.GetProperty("variables").GetRawText());
        Assert.Equal("{\"reference\":\"PAY-77\"}", body.GetProperty("outcome").GetRawText());
        Assert.True(server.Logged("work.replayed"));
    }

    [Fact]
    public async Task RunsTheHandlerWhileTheOperationMayBeDispatched()
    {
        var server = new FakeServer();
        WorkOperation? seen = null;
        var r = await RunOne(server, Sub((ctx, _) =>
        {
            seen = ctx.Operation;
            return Task.FromResult<IDictionary<string, object?>?>(new Dictionary<string, object?> { ["done"] = true });
        }), FakeServer.Work("{\"state\":\"INTENT\",\"attempts\":2,\"mayDispatch\":true}"));
        Assert.Equal(2, seen!.Attempts);
        Assert.Equal("{\"done\":true}", r.Body!.Value.GetProperty("variables").GetRawText());
        Assert.False(r.Body!.Value.TryGetProperty("outcome", out _));
    }

    [Fact]
    public async Task SendsTheHandlerSetOutcomeReference()
    {
        var server = new FakeServer();
        var r = await RunOne(server, Sub((ctx, _) =>
        {
            ctx.OutcomeReference = "BANK-REF-1";
            return Task.FromResult<IDictionary<string, object?>?>(null);
        }), FakeServer.Work());
        Assert.Equal("{\"reference\":\"BANK-REF-1\"}", r.Body!.Value.GetProperty("outcome").GetRawText());
    }

    // ---------------------------------------------------------------- outcome mapping

    [Fact]
    public async Task BpmnErrorIsReportedAsBpmnError()
    {
        var server = new FakeServer();
        var r = await RunOne(server, Sub(Throws(new BpmnErrorException("INSUFFICIENT_FUNDS", "balance too low",
            new Dictionary<string, object?> { ["balance"] = 3 }))), FakeServer.Work());
        Assert.Equal("bpmn-error", r.Action);
        Assert.Equal("INSUFFICIENT_FUNDS", Str(r, "errorCode"));
        Assert.Equal("balance too low", Str(r, "errorMessage"));
        Assert.Equal("{\"balance\":3}", r.Body!.Value.GetProperty("variables").GetRawText());
        Assert.Equal("op-1:bpmn-error:3", r.IdempotencyKey);
    }

    [Fact]
    public async Task OutcomeUnknownIsReportedWithDetailAndEvidence()
    {
        var server = new FakeServer();
        var r = await RunOne(server, Sub(Throws(new OutcomeUnknownException("bank timed out after send", "socket read timeout",
            new Dictionary<string, object?> { ["sent"] = true }))), FakeServer.Work());
        Assert.Equal("outcome-unknown", r.Action);
        Assert.Equal("bank timed out after send" + Environment.NewLine + "socket read timeout", Str(r, "detail"));
        Assert.Equal("{\"sent\":true}", r.Body!.Value.GetProperty("evidence").GetRawText());
    }

    [Fact]
    public async Task TaskFailedOverridesRetriesAndTimeout()
    {
        var server = new FakeServer();
        var r = await RunOne(server, Sub(Throws(new TaskFailedException("downstream 503", details: "d", retries: 0,
            retryTimeout: TimeSpan.FromMilliseconds(1234)))), FakeServer.Work());
        Assert.Equal("fail", r.Action);
        Assert.Equal("downstream 503", Str(r, "errorMessage"));
        Assert.Equal("d", Str(r, "errorDetails"));
        Assert.Equal(0, r.Body!.Value.GetProperty("retries").GetInt32());
        Assert.Equal(1234, r.Body!.Value.GetProperty("retryTimeoutMs").GetInt64());
    }

    [Fact]
    public async Task UnexpectedErrorFailsWithOneRetryLess()
    {
        var server = new FakeServer();
        var sub = Sub(Throws(new InvalidOperationException("boom"))) with
        {
            RetryBackoff = new Backoff(TimeSpan.FromMilliseconds(10), TimeSpan.FromMilliseconds(10), 1, 0),
        };
        var r = await RunOne(server, sub, FakeServer.Work(retries: 3));
        Assert.Equal("fail", r.Action);
        Assert.Equal("boom", Str(r, "errorMessage"));
        Assert.Equal(2, r.Body!.Value.GetProperty("retries").GetInt32());
        Assert.Equal(10, r.Body!.Value.GetProperty("retryTimeoutMs").GetInt64());
    }

    [Fact]
    public async Task UnexpectedErrorOnIrreversibleTopicIsOutcomeUnknown()
    {
        var server = new FakeServer();
        var r = await RunOne(server, Sub(Throws(new InvalidOperationException("connection reset"))) with { Irreversible = true },
            FakeServer.Work());
        Assert.Equal("outcome-unknown", r.Action);
        Assert.StartsWith("unexpected error on irreversible topic: connection reset", Str(r, "detail"));
    }

    // ---------------------------------------------------------------- report retries and fencing

    [Fact]
    public async Task RetryableAnswerIsRetriedWithTheSameKey()
    {
        var server = new FakeServer().Reply("complete", (503, "{\"title\":\"busy\"}"), (503, null), (204, null));
        await RunOne(server, Sub(Returns(null)), FakeServer.Work());
        await FakeServer.UntilAsync(() => server.CallsOf("complete").Count == 3);
        Assert.True(server.CallsOf("complete").All(c => c.IdempotencyKey == "op-1:complete:3"));
        await FakeServer.UntilAsync(() => server.Logged("work.complete", f => f is not null && f.TryGetValue("attempts", out var a) && Equals(a, 3)));
    }

    [Fact]
    public async Task NonRetryableAnswerIsNotRetried()
    {
        var server = new FakeServer().Reply("complete", (400, "{\"title\":\"bad\"}"));
        await RunOne(server, Sub(Returns(null)), FakeServer.Work());
        await FakeServer.UntilAsync(() => server.Logged("work.report.failed"));
        Assert.Single(server.CallsOf("complete"));
    }

    [Fact]
    public async Task StaleLeaseAbandonsTheItem()
    {
        var server = new FakeServer().Reply("complete", (409, "{\"code\":\"STALE_LEASE\"}"));
        await RunOne(server, Sub(Returns(null)), FakeServer.Work());
        await FakeServer.UntilAsync(() => server.Logged("work.abandoned"));
        Assert.Single(server.CallsOf("complete"));
    }

    [Fact]
    public async Task NoRetryAfterLeaseExpiry()
    {
        var server = new FakeServer().Reply("complete", (503, null), (503, null));
        await RunOne(server, Sub(Returns(null)), FakeServer.Work(lockExpiresAt: DateTimeOffset.UtcNow.AddSeconds(-1)));
        await FakeServer.UntilAsync(() => server.Logged("work.report.failed"));
        Assert.Single(server.CallsOf("complete"));
    }

    // ---------------------------------------------------------------- irreversibility (subscription flag only)

    [Fact]
    public async Task AStrayEffectMemberDoesNotMakeAnItemIrreversible()
    {
        // the server sends no effect (WorkPort.ExternalWork); irreversibility is the subscription flag only
        var server = new FakeServer();
        var item = FakeServer.Work()[..^1] + ",\"effect\":\"irreversible\"}";
        var r = await RunOne(server, Sub(Throws(new InvalidOperationException("boom"))), item);
        Assert.Equal("fail", r.Action);
    }

    [Fact]
    public async Task ItemWithoutLockVersionNeverStarts()
    {
        var server = new FakeServer().Enqueue(FakeServer.Work().Replace("\"lockVersion\":3,", ""));
        var calls = 0;
        var worker = server.Worker().Subscribe(Sub((_, _) =>
        {
            calls++;
            return Task.FromResult<IDictionary<string, object?>?>(null);
        }));
        worker.Start();
        await FakeServer.UntilAsync(() => server.Logged("work.protocol.violation"));
        await worker.StopAsync();
        Assert.Equal(0, calls);
    }

    // ---------------------------------------------------------------- loop

    [Fact]
    public async Task FetchAsksForFreeSlotsWithTheSubscriptionFilters()
    {
        var server = new FakeServer();
        await RunOne(server, Sub(Returns(null)) with { Variables = new[] { "amount" }, BusinessKey = "BILL-1" }, FakeServer.Work());
        var f = server.CallsOf("fetch-and-lock")[0].Body!.Value;
        Assert.Equal("worker-1", f.GetProperty("workerId").GetString());
        Assert.Equal(1, f.GetProperty("maxTasks").GetInt32());
        Assert.Equal("[{\"name\":\"pay\",\"lockDurationMs\":60000,\"variables\":[\"amount\"],\"businessKey\":\"BILL-1\"}]",
            f.GetProperty("topics").GetRawText());
    }

    [Fact]
    public async Task ItemWithoutLockedConfirmationNeverStarts()
    {
        var server = new FakeServer().Enqueue(FakeServer.Work(attemptId: null));
        var calls = 0;
        var worker = server.Worker().Subscribe(Sub((_, _) =>
        {
            calls++;
            return Task.FromResult<IDictionary<string, object?>?>(null);
        }));
        worker.Start();
        await FakeServer.UntilAsync(() => server.Logged("work.protocol.violation"));
        await worker.StopAsync();
        Assert.Equal(0, calls);
        Assert.DoesNotContain(server.Calls, c => FakeServer.ReportActions.Contains(c.Action));
    }

    [Fact]
    public async Task ItemOfUnsubscribedTopicIsUnlocked()
    {
        var server = new FakeServer().Enqueue(FakeServer.Work(topic: "other"));
        var worker = server.Worker().Subscribe(Sub(Returns(null)));
        worker.Start();
        var r = await server.WaitForAsync(c => c.Action == "unlock");
        await worker.StopAsync();
        Assert.Equal("op-1:unlock:3", r.IdempotencyKey);
    }

    [Fact]
    public void InvalidSubscriptionsAreRejected()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => Sub(Returns(null)) with { LockDuration = TimeSpan.FromSeconds(1) });
        Assert.Throws<ArgumentOutOfRangeException>(() => Sub(Returns(null)) with { LockDuration = TimeSpan.FromHours(25) });
        Assert.Throws<ArgumentOutOfRangeException>(() => Sub(Returns(null)) with { Retries = -1 });
    }

    // ---------------------------------------------------------------- HTTP error mapping

    [Theory]
    [InlineData(409, "{\"type\":\"https://sangam.dev/problems/stale-lease\"}", typeof(StaleLeaseException))]
    [InlineData(409, "{\"code\":\"STALE_LEASE\"}", typeof(StaleLeaseException))]
    [InlineData(409, "{\"code\":\"CONFLICT\"}", typeof(SangamApiException))]
    [InlineData(410, "{\"code\":\"GONE_INSTANCE_DELETED\"}", typeof(GoneException))]
    [InlineData(502, null, typeof(SangamApiException))]
    public async Task ErrorMapping(int status, string? body, Type expected)
    {
        var server = new FakeServer().Reply("unlock", (status, body));
        using var client = server.Client();
        var e = await Assert.ThrowsAnyAsync<SangamApiException>(() =>
            client.Work.UnlockAsync("wk-1", new UnlockRequest("w", 1, "a"), "k"));
        Assert.Equal(expected, e.GetType());
        Assert.Equal(status, e.Status);
    }

    [Fact]
    public void RetryableStatuses()
    {
        var statuses = new[] { 400, 401, 403, 404, 408, 409, 422, 425, 429, 500, 501, 502, 503, 504 };
        Assert.Equal(new[] { 408, 425, 429, 500, 502, 503, 504 },
            statuses.Where(s => new SangamApiException(s, new Problem { Status = s }, "x").IsRetryable).ToArray());
        Assert.False(new StaleLeaseException(new Problem(), "x").IsRetryable);
        Assert.False(new GoneException(new Problem(), "x").IsRetryable);
    }

    [Fact]
    public void ExternalWorkDeserializesTheOperation()
    {
        var w = JsonSerializer.Deserialize<ExternalWork>(FakeServer.Work(Succeeded), Sangam.Sdk.Http.HttpTransport.DefaultJson)!;
        Assert.NotNull(w.Operation);
        Assert.Equal("SUCCEEDED", w.Operation!.State);
        Assert.True(w.Operation.MayDispatch == false);
    }
}
