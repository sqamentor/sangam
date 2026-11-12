// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// Outcome lookup for a step whose earlier attempt left an <c>INTENT</c> (capability classes (a) and (b), R36/R37):
/// ask the remote system, by <see cref="Step.IdempotencyKey"/>, whether the call took effect.
/// <list type="bullet">
/// <item><see cref="StepLookupResult{T}.Found"/>: the call took effect; the value is recorded <c>SUCCEEDED</c> and
/// returned without calling again.</item>
/// <item><see cref="StepLookupResult{T}.NoEffect"/> (or <c>default</c>): the call certainly did not take effect; the
/// step runs again.</item>
/// <item>An exception: the outcome is still unknown; the step stays <c>INTENT</c> and the exception propagates.</item>
/// </list>
/// </summary>
public delegate Task<StepLookupResult<T>> StepLookup<T>(Step step, CancellationToken cancellationToken);
