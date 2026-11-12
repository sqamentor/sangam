// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// How a durable step recovers when an earlier attempt left it <c>INTENT</c> (the crash window of R37), following the
/// capability class of the remote call (R36):
/// <list type="bullet">
/// <item>(a) idempotent, with or without lookup: <see cref="IdempotentCall"/> / <see cref="AlsoIdempotent"/> runs the
/// call again with the same <see cref="Step.IdempotencyKey"/>;</item>
/// <item>(b) outcome lookup only: <see cref="WithLookup"/> asks the remote system before any controlled retry;</item>
/// <item>(c) neither (the default): the step throws <see cref="OutcomeUnknownException"/>, the worker reports
/// outcome-unknown and reconciliation decides; nothing is repeated automatically.</item>
/// </list>
/// A lookup, when given, is always asked first. Instances are immutable.
/// </summary>
public sealed class StepOptions<T>
{
    private StepOptions(bool idempotent, StepLookup<T>? lookup)
    {
        Idempotent = idempotent;
        Lookup = lookup;
    }

    /// <summary>Capability class (c): no lookup, not idempotent.</summary>
    public static StepOptions<T> Defaults { get; } = new(false, null);

    /// <summary>Capability class (a): the remote call is idempotent on <see cref="Step.IdempotencyKey"/>.</summary>
    public static StepOptions<T> IdempotentCall() => new(true, null);

    /// <summary>Capability class (b): look the outcome up before running the call again.</summary>
    public static StepOptions<T> WithLookup(StepLookup<T> lookup) =>
        new(false, lookup ?? throw new ArgumentNullException(nameof(lookup)));

    /// <summary>These options, also idempotent (class (a) with a lookup).</summary>
    public StepOptions<T> AlsoIdempotent() => new(true, Lookup);

    /// <summary>These options with an outcome lookup.</summary>
    public StepOptions<T> AndLookup(StepLookup<T> lookup) =>
        new(Idempotent, lookup ?? throw new ArgumentNullException(nameof(lookup)));

    /// <summary>True when an <c>INTENT</c> left by an earlier attempt may be run again with the same key.</summary>
    public bool Idempotent { get; }

    /// <summary>The outcome lookup, or null.</summary>
    public StepLookup<T>? Lookup { get; }
}
