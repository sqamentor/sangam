// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// The handle a durable step's body and lookup receive (<see cref="TaskContext.StepAsync{T}"/>, M-025): the step's
/// name, its stable idempotency key and a slot for the provider's reference.
/// </summary>
public sealed class Step
{
    private volatile string? _providerRef;

    internal Step(string name, string operationId, string? previousState)
    {
        Name = name;
        OperationId = operationId;
        PreviousState = previousState;
    }

    /// <summary>The step name, unique within the work's operation.</summary>
    public string Name { get; }

    /// <summary>The work's stable operationId (R36).</summary>
    public string OperationId { get; }

    /// <summary>
    /// Idempotency key for the remote call: <c>operationId + ":" + name</c>. Stable across retries, redelivery and
    /// lease expiry, and different for each step of the same operation.
    /// </summary>
    public string IdempotencyKey => OperationId + ":" + Name;

    /// <summary>
    /// What an earlier attempt recorded for this step: null on its first run, <c>"INTENT"</c> when that attempt's
    /// outcome is unknown, <c>"FAILED"</c> when it failed without effect.
    /// </summary>
    public string? PreviousState { get; }

    /// <summary>The provider's reference recorded with the outcome (for example a payment id); later lookups can use it.</summary>
    public string? ProviderRef
    {
        get => _providerRef;
        set => _providerRef = value;
    }
}
