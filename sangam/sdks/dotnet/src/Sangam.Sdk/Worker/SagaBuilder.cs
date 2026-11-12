// SPDX-License-Identifier: Apache-2.0
using System.Text.RegularExpressions;

namespace Sangam.Sdk.Worker;

/// <summary>Builds a <see cref="Saga"/> step (M-026): <see cref="Saga.Step"/>, then action, compensation and options.</summary>
public sealed class SagaBuilder
{
    private static readonly Regex NamePattern = new(@"\A[A-Za-z0-9._-]{1,64}\z", RegexOptions.CultureInvariant);

    private readonly string _name;
    private string? _actionTopic;
    private SagaAction? _action;
    private string? _compensationTopic;
    private SagaCompensation? _compensation;
    private TimeSpan _lockDuration = TimeSpan.FromMinutes(5);
    private int _retries = 3;
    private bool _irreversible;
    private IReadOnlyList<string>? _variables;

    internal SagaBuilder(string name)
    {
        ArgumentNullException.ThrowIfNull(name);
        if (!NamePattern.IsMatch(name))
            throw new ArgumentException("saga step name must be 1-64 of [A-Za-z0-9._-]: " + name, nameof(name));
        _name = name;
    }

    /// <summary>The forward action: topic and handler.</summary>
    public SagaBuilder Action(string topic, SagaAction handler)
    {
        _actionTopic = topic ?? throw new ArgumentNullException(nameof(topic));
        _action = handler ?? throw new ArgumentNullException(nameof(handler));
        return this;
    }

    /// <summary>The compensation: topic and handler.</summary>
    public SagaBuilder Compensation(string topic, SagaCompensation handler)
    {
        _compensationTopic = topic ?? throw new ArgumentNullException(nameof(topic));
        _compensation = handler ?? throw new ArgumentNullException(nameof(handler));
        return this;
    }

    /// <summary>Lease per fetch for both topics (default 5 min).</summary>
    public SagaBuilder LockDuration(TimeSpan value)
    {
        _lockDuration = value;
        return this;
    }

    /// <summary>Retries for the first failure of either handler (default 3).</summary>
    public SagaBuilder Retries(int value)
    {
        _retries = value;
        return this;
    }

    /// <summary>The forward action has an irreversible effect (VI.3.2 rule 5): unexpected errors become outcome-unknown.</summary>
    public SagaBuilder Irreversible(bool value = true)
    {
        _irreversible = value;
        return this;
    }

    /// <summary>Variables to fetch for both handlers (null = all); the record variable is always added for the compensation.</summary>
    public SagaBuilder Variables(IReadOnlyList<string>? value)
    {
        _variables = value;
        return this;
    }

    /// <summary>The two subscriptions; throws <see cref="InvalidOperationException"/> without both handlers or with one topic for both.</summary>
    public Saga Build()
    {
        if (_action is null || _compensation is null)
            throw new InvalidOperationException("saga step " + _name + " needs an action and a compensation");
        if (_actionTopic == _compensationTopic)
            throw new InvalidOperationException("saga step " + _name + ": action and compensation need their own topics");
        var step = _name;
        var a = _action;
        var comp = _compensation;
        var forward = new TopicSubscription(_actionTopic!,
            async (ctx, ct) => Saga.WithRecord(step, ctx, await a(ctx, ct).ConfigureAwait(false)))
        {
            LockDuration = _lockDuration,
            Retries = _retries,
            Irreversible = _irreversible,
            Variables = _variables,
        };
        List<string>? compVars = null;
        if (_variables is not null)
        {
            compVars = new List<string>(_variables);
            if (!compVars.Contains(Saga.RecordVariable(step))) compVars.Add(Saga.RecordVariable(step));
        }
        var undo = new TopicSubscription(_compensationTopic!,
            (ctx, ct) => comp(new CompensationContext(ctx, Saga.RecordOf(step, ctx), step), ct))
        {
            LockDuration = _lockDuration,
            Retries = _retries,
            Variables = compVars,
        };
        return new Saga(step, forward, undo);
    }
}
