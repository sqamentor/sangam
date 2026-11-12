// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// What a <see cref="StepLookup{T}"/> found (M-025, R37): either the value of an earlier call that took effect, or no
/// effect. <c>default(StepLookupResult&lt;T&gt;)</c> is <see cref="NoEffect"/>, so a lookup may <c>return default;</c>.
/// </summary>
public readonly struct StepLookupResult<T>
{
    private StepLookupResult(T value)
    {
        HasValue = true;
        Value = value;
    }

    /// <summary>True when the earlier call took effect and <see cref="Value"/> is its result.</summary>
    public bool HasValue { get; }

    /// <summary>The result of the earlier call; meaningful only when <see cref="HasValue"/>.</summary>
    public T Value { get; }

    /// <summary>The earlier call took effect with <paramref name="value"/> (not null, like the Java SDK's Optional).</summary>
    public static StepLookupResult<T> Found(T value) =>
        value is null ? throw new ArgumentNullException(nameof(value), "a found value must not be null; return NoEffect instead") : new(value);

    /// <summary>The earlier call certainly did not take effect; the step runs again.</summary>
    public static StepLookupResult<T> NoEffect => default;
}
