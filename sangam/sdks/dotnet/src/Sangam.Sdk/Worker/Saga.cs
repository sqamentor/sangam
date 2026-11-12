// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using Sangam.Sdk.Models;

namespace Sangam.Sdk.Worker;

/// <summary>
/// Saga and compensation worker helper (M-026, §3.2, R36): registers one forward action and its compensation as two
/// external-task handlers, and carries the forward action's identity to the compensation.
/// <para>
/// In the model, the forward step is a service task with topic <c>actionTopic</c>, and its compensation handler
/// (attached through a compensation boundary event, <c>isForCompensation="true"</c>) is a service task with topic
/// <c>compensationTopic</c>. When the forward action completes, this helper adds the process variable
/// <see cref="RecordVariable"/> holding a <see cref="SagaRecord"/> (the forward operationId, provider reference and work
/// id) as an R33 <c>Json</c> typed value. When the engine compensates, the compensation handler gets that record in its
/// <see cref="CompensationContext"/> and reuses the forward operationId: the undo call's idempotency key is derived from
/// it, so compensating the same forward action twice undoes it once.
/// </para>
/// <para>
/// One record per step name and process scope: a step repeated in a loop or multi-instance body needs its own name per
/// iteration, or a compensation that reads the iteration's data from local variables.
/// </para>
/// <example><code>
/// Saga.Step("charge")
///     .Action("payment.charge", async (ctx, ct) =&gt; new Dictionary&lt;string, object?&gt; { ["chargeId"] = await Charge(ctx, ct) })
///     .Compensation("payment.refund", async (c, ct) =&gt; { await Refund(c.ForwardProviderRef, c.IdempotencyKey, ct); return null; })
///     .Build()
///     .RegisterOn(worker);
/// </code></example>
/// </summary>
public sealed class Saga
{
    /// <summary>Prefix of the record variables.</summary>
    public const string RecordPrefix = "sangamSaga_";

    internal Saga(string name, TopicSubscription action, TopicSubscription compensation)
    {
        Name = name;
        Action = action;
        Compensation = compensation;
    }

    /// <summary>Starts a saga step named <paramref name="name"/> (letters, digits, '.', '-', '_'; at most 64 characters).</summary>
    public static SagaBuilder Step(string name) => new(name);

    /// <summary>The process variable that holds the record of step <paramref name="name"/>: <c>sangamSaga_&lt;name&gt;</c> with '.' and '-' as '_'.</summary>
    public static string RecordVariable(string name) => RecordPrefix + name.Replace('.', '_').Replace('-', '_');

    public string Name { get; }

    /// <summary>The forward action's subscription.</summary>
    public TopicSubscription Action { get; }

    /// <summary>The compensation's subscription.</summary>
    public TopicSubscription Compensation { get; }

    /// <summary>Both subscriptions, forward action first (for <see cref="SangamWorker"/>'s constructor).</summary>
    public IReadOnlyList<TopicSubscription> Subscriptions => new[] { Action, Compensation };

    /// <summary>Subscribes both handlers on <paramref name="worker"/>.</summary>
    public SangamWorker RegisterOn(SangamWorker worker)
    {
        ArgumentNullException.ThrowIfNull(worker);
        worker.Subscribe(Action);
        return worker.Subscribe(Compensation);
    }

    /// <summary>The forward handler's variables plus the record variable of <paramref name="step"/>.</summary>
    internal static IDictionary<string, object?> WithRecord(string step, TaskContext ctx, IDictionary<string, object?>? vars)
    {
        var output = vars is null ? new Dictionary<string, object?>() : new Dictionary<string, object?>(vars);
        var r = new SagaRecord(step, ctx.OperationId, ctx.OutcomeReference, ctx.WorkId);
        output[RecordVariable(step)] = TypedValue.Json(r.ToMap());
        return output;
    }

    /// <summary>The record of <paramref name="step"/> from the compensation's variables, or null when absent.</summary>
    internal static SagaRecord? RecordOf(string step, TaskContext ctx)
    {
        if (!ctx.Variables.TryGetValue(RecordVariable(step), out var raw) || raw is null) return null;
        var m = ctx.Variable<Dictionary<string, JsonElement>>(RecordVariable(step));
        return SagaRecord.FromMap(m);
    }
}
