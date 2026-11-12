// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Testing;
using Sangam.Sdk.Tests.Contract;
using Sangam.Sdk.Worker;
using Xunit;

namespace Sangam.Sdk.Tests.Worker;

/// <summary>
/// M-026 / R36: the saga helper registers a forward action and its compensation, records the forward identity as an R33
/// Json variable and hands it to the compensation with a stable undo idempotency key.
/// </summary>
public sealed class SagaTests
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(10);

    private static Task<IDictionary<string, object?>?> Vars(params (string Key, object? Value)[] kv) => ContractSupport.Vars(kv);

    [Fact]
    public void RecordVariableReplacesDotsAndDashes()
    {
        Assert.Equal("sangamSaga_pay_charge_1", Saga.RecordVariable("pay.charge-1"));
        Assert.Equal("sangamSaga_a_b", Saga.RecordVariable("a_b"));
    }

    [Fact]
    public void BuildValidatesNameHandlersAndTopics()
    {
        Assert.Throws<ArgumentException>(() => Saga.Step("bad name"));
        Assert.Throws<ArgumentException>(() => Saga.Step(new string('a', 65)));
        Assert.Throws<ArgumentException>(() => Saga.Step(""));
        Assert.Throws<ArgumentNullException>(() => Saga.Step(null!));
        Assert.Throws<InvalidOperationException>(() => Saga.Step("charge").Action("a", (_, _) => Vars()).Build());
        Assert.Throws<InvalidOperationException>(() => Saga.Step("charge")
            .Action("same", (_, _) => Vars()).Compensation("same", (_, _) => Vars()).Build());
    }

    [Fact]
    public void SubscriptionsCarryTheOptionsAndTheCompensationFetchesTheRecord()
    {
        var saga = Saga.Step("charge")
            .Action("payment.charge", (_, _) => Vars())
            .Compensation("payment.refund", (_, _) => Vars())
            .LockDuration(TimeSpan.FromMinutes(2)).Retries(1).Irreversible().Variables(new[] { "amount" })
            .Build();
        Assert.Equal("charge", saga.Name);
        Assert.Equal(new[] { "payment.charge", "payment.refund" }, saga.Subscriptions.Select(s => s.Topic).ToArray());
        Assert.True(saga.Action.Irreversible);
        Assert.False(saga.Compensation.Irreversible);
        Assert.Equal(TimeSpan.FromMinutes(2), saga.Compensation.LockDuration);
        Assert.Equal(1, saga.Action.Retries);
        Assert.Equal(new[] { "amount" }, saga.Action.Variables!.ToArray());
        Assert.Equal(new[] { "amount", "sangamSaga_charge" }, saga.Compensation.Variables!.ToArray());
        var all = Saga.Step("x").Action("a", (_, _) => Vars()).Compensation("b", (_, _) => Vars()).Build();
        Assert.Null(all.Action.Variables);
        Assert.Null(all.Compensation.Variables);
    }

    [Fact]
    public async Task ForwardRecordReachesTheCompensation()
    {
        var e = new SangamTestEngine();
        var fwd = e.AddWork("payment.charge");
        CompensationContext? seen = null;
        var saga = Saga.Step("pay.charge-1")
            .Action("payment.charge", (ctx, _) =>
            {
                ctx.OutcomeReference = "PAY-77";
                return Vars(("charged", true));
            })
            .Compensation("payment.refund", (c, _) =>
            {
                seen = c;
                return Vars(("refunded", c.ForwardProviderRef));
            })
            .Build();
        await using var w = saga.RegisterOn(ContractSupport.Worker(e, "w1"));
        w.Start();
        await e.WaitForStateAsync(fwd, "COMPLETED", Wait);
        var done = e.CompletedVariables(fwd)!;
        Assert.True(done["charged"]!.GetValue<bool>());
        var record = done["sangamSaga_pay_charge_1"]!;
        Assert.Equal("Json", record["type"]!.GetValue<string>());
        Assert.Equal("pay.charge-1", record["value"]!["step"]!.GetValue<string>());
        Assert.Equal(e.OperationId(fwd), record["value"]!["operationId"]!.GetValue<string>());
        Assert.Equal("PAY-77", record["value"]!["providerRef"]!.GetValue<string>());
        Assert.Equal(fwd, record["value"]!["workId"]!.GetValue<string>());

        var undo = e.AddWork("payment.refund", new Dictionary<string, object?> { ["sangamSaga_pay_charge_1"] = record });
        await e.WaitForStateAsync(undo, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.NotNull(seen);
        Assert.Equal(e.OperationId(fwd), seen!.ForwardOperationId);
        Assert.Equal("PAY-77", seen.ForwardProviderRef);
        Assert.Equal(fwd, seen.Forward!.WorkId);
        Assert.Equal(e.OperationId(fwd) + ":compensate:pay.charge-1", seen.IdempotencyKey);
        Assert.Equal(e.OperationId(undo), seen.Task.OperationId);
        Assert.Equal("PAY-77", e.CompletedVariables(undo)!["refunded"]!.GetValue<string>());
    }

    [Fact]
    public async Task CompensationWithoutARecordUsesItsOwnOperationId()
    {
        var e = new SangamTestEngine();
        CompensationContext? seen = null;
        var saga = Saga.Step("charge")
            .Action("payment.charge", (_, _) => Vars())
            .Compensation("payment.refund", (c, _) => { seen = c; return Vars(); })
            .Build();
        await using var w = new SangamWorker(e.Client(), new SangamWorkerOptions
        {
            WorkerId = "w1",
            MaxConcurrency = 1,
            AsyncResponseTimeout = TimeSpan.FromMilliseconds(100),
            FetchBackoff = ContractSupport.Fast,
            ReportBackoff = ContractSupport.Fast,
            Clock = e.Now,
            Logger = Sangam.Sdk.Logging.SangamLoggers.None,
        }, saga.Subscriptions);
        var undo = e.AddWork("payment.refund");
        w.Start();
        await e.WaitForStateAsync(undo, "COMPLETED", Wait);
        await w.StopAsync();
        Assert.NotNull(seen);
        Assert.Null(seen!.Forward);
        Assert.Null(seen.ForwardOperationId);
        Assert.Null(seen.ForwardProviderRef);
        Assert.Equal(e.OperationId(undo), seen.IdempotencyKey);
    }
}
