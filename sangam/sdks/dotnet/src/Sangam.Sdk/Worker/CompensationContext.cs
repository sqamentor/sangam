// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// What a saga compensation handler sees (M-026): the compensation work's own <see cref="TaskContext"/> and the record
/// of the forward action it undoes. The undo call reuses the forward operationId (R36): send
/// <see cref="IdempotencyKey"/> so that compensating the same forward action twice undoes it once.
/// </summary>
public sealed class CompensationContext
{
    private readonly string _step;

    internal CompensationContext(TaskContext task, SagaRecord? forward, string step)
    {
        Task = task;
        Forward = forward;
        _step = step;
    }

    /// <summary>The compensation work item (lease, variables, durable steps, logger, client).</summary>
    public TaskContext Task { get; }

    /// <summary>
    /// The forward action's record, or null when the instance holds none (the forward action did not complete through
    /// <see cref="Saga"/>, so there is nothing this helper knows to undo).
    /// </summary>
    public SagaRecord? Forward { get; }

    /// <summary>The forward action's operationId (R36), or null without a record.</summary>
    public string? ForwardOperationId => Forward?.OperationId;

    /// <summary>The forward action's provider reference (for example the payment to refund), or null.</summary>
    public string? ForwardProviderRef => Forward?.ProviderRef;

    /// <summary>
    /// Idempotency key of the undo call: <c>forwardOperationId + ":compensate:" + step</c>; without a record, the
    /// compensation work's own operationId.
    /// </summary>
    public string IdempotencyKey => Forward is null ? Task.OperationId : Forward.OperationId + ":compensate:" + _step;
}
