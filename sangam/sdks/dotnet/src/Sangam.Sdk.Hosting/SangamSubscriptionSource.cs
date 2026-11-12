// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Worker;

namespace Sangam.Sdk.Hosting;

/// <summary>
/// One registration of topic subscriptions in the container (M-027), resolved when the hosted worker starts. Added by
/// <see cref="SangamWorkerBuilder"/>; <see cref="TopicSubscription"/> and <see cref="Saga"/> instances registered
/// directly in the container are picked up as well.
/// </summary>
public sealed class SangamSubscriptionSource
{
    private readonly Func<IServiceProvider, IEnumerable<TopicSubscription>> _factory;

    public SangamSubscriptionSource(Func<IServiceProvider, IEnumerable<TopicSubscription>> factory) =>
        _factory = factory ?? throw new ArgumentNullException(nameof(factory));

    public IEnumerable<TopicSubscription> Resolve(IServiceProvider services) => _factory(services);
}
