// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// A saga's forward action (M-026): a <see cref="TaskHandler"/> whose completion also records a <see cref="SagaRecord"/>.
/// Return variables, errors and outcome-unknown follow <see cref="TaskHandler"/>.
/// </summary>
public delegate Task<IDictionary<string, object?>?> SagaAction(TaskContext context, CancellationToken cancellationToken);
