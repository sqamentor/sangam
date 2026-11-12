// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// The side-effecting body of one durable step (<see cref="TaskContext.StepAsync{T}"/>, M-025). Send
/// <see cref="Step.IdempotencyKey"/> to the remote API as its idempotency key, and report the provider's reference with
/// <see cref="Step.ProviderRef"/>.
/// <list type="bullet">
/// <item>Return normally: the step is recorded <c>SUCCEEDED</c> with the returned value; later attempts replay it.</item>
/// <item>Throw <see cref="StepNoEffectException"/>: the call certainly had no effect (refused before it was sent, a
/// business rejection); the step is recorded <c>FAILED</c> and may run again on a later attempt.</item>
/// <item>Throw anything else: the outcome is uncertain, the step stays <c>INTENT</c>; a later attempt looks the outcome
/// up (<see cref="StepOptions{T}.Lookup"/>), runs again only for an idempotent call
/// (<see cref="StepOptions{T}.Idempotent"/>), or reports outcome-unknown (R37: never a blind re-issue).</item>
/// </list>
/// </summary>
public delegate Task<T> StepCall<T>(Step step, CancellationToken cancellationToken);
