// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// Undoes a saga's forward action (M-026). Runs as the external task of the BPMN compensation handler
/// (<c>isForCompensation="true"</c>); the result variables, errors and outcome-unknown follow <see cref="TaskHandler"/>.
/// </summary>
public delegate Task<IDictionary<string, object?>?> SagaCompensation(CompensationContext context, CancellationToken cancellationToken);
