// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Worker;

namespace Sangam.Sdk.Hosting;

/// <summary>
/// A task handler resolved from the container (M-027). Registered with
/// <see cref="SangamWorkerBuilder.AddHandler{THandler}(string, Func{TopicSubscription, TopicSubscription}?)"/>; one
/// DI scope is created per work item, so scoped dependencies (a DbContext, an HTTP client wrapper) live for one item.
/// Results and exceptions follow <see cref="TaskHandler"/>.
/// </summary>
public interface ISangamTaskHandler
{
    Task<IDictionary<string, object?>?> HandleAsync(TaskContext context, CancellationToken cancellationToken);
}
