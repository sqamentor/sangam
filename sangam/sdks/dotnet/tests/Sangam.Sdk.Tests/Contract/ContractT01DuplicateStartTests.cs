// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Errors;
using Sangam.Sdk.Models;
using Sangam.Sdk.Testing;
using Xunit;

namespace Sangam.Sdk.Tests.Contract;

/// <summary>
/// T01 (SANGAM.md II §16, R41): duplicate process-start requests produce one intended instance; the repeated response is
/// the first one (Idempotent-Replayed: true); a conflicting payload under the same key is rejected (422).
/// </summary>
public sealed class ContractT01DuplicateStartTests
{
    private static StartProcessRequest Start(decimal amount) =>
        new("p1_bill", "BILL-2026-0001", new Dictionary<string, object?> { ["amount"] = TypedValue.Decimal(amount) });

    [Fact]
    public async Task T01_SameKeySamePayloadIsOneInstanceAndReplayed()
    {
        var engine = new SangamTestEngine();
        using var c = engine.Client();
        var first = await c.Processes.StartWithResponseAsync(Start(125000.00m), "start-BILL-2026-0001");
        var second = await c.Processes.StartWithResponseAsync(Start(125000.00m), "start-BILL-2026-0001");
        Assert.Equal(201, first.Status);
        Assert.False(first.IdempotentReplayed);
        Assert.Equal(201, second.Status);
        Assert.True(second.IdempotentReplayed);
        Assert.Equal(first.Body!.Id, second.Body!.Id);
        Assert.Single(engine.Instances());
    }

    [Fact]
    public async Task T01_ConflictingPayloadUnderTheSameKeyIsRejected()
    {
        var engine = new SangamTestEngine();
        using var c = engine.Client();
        await c.Processes.StartAsync(Start(125000.00m), "start-BILL-2026-0002");
        var e = await Assert.ThrowsAsync<SangamApiException>(() => c.Processes.StartAsync(Start(999.00m), "start-BILL-2026-0002"));
        Assert.Equal(422, e.Status);
        Assert.Equal("IDEMPOTENCY_KEY_REUSED", e.Problem.Code);
        Assert.Single(engine.Instances());
    }

    [Fact]
    public async Task T01_DifferentKeysAreDifferentBusinessActions()
    {
        var engine = new SangamTestEngine();
        using var c = engine.Client();
        var a = await c.Processes.StartAsync(Start(1m), "k-a");
        var b = await c.Processes.StartAsync(Start(1m), "k-b");
        Assert.NotEqual(a!.Id, b!.Id);
        Assert.Equal(2, engine.Instances().Count);
    }
}
