// SPDX-License-Identifier: Apache-2.0
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Sangam.Sdk.Worker;

namespace Sangam.Sdk.Hosting;

/// <summary>
/// Registers handlers and sagas for the hosted Sangam worker (M-027). Returned by
/// <see cref="SangamServiceCollectionExtensions.AddSangamWorker(IServiceCollection, Action{SangamWorkerHostingOptions}?)"/>.
/// Every registration is resolved when the host starts; two subscriptions for one topic are an error at start.
/// </summary>
public sealed class SangamWorkerBuilder
{
    internal SangamWorkerBuilder(IServiceCollection services) => Services = services;

    public IServiceCollection Services { get; }

    /// <summary>A ready subscription (lease, retries, irreversible, variables already set).</summary>
    public SangamWorkerBuilder AddSubscription(TopicSubscription subscription)
    {
        ArgumentNullException.ThrowIfNull(subscription);
        Services.AddSingleton(new SangamSubscriptionSource(_ => new[] { subscription }));
        return this;
    }

    /// <summary>A delegate handler for <paramref name="topic"/>; <paramref name="configure"/> may adjust the subscription (<c>s =&gt; s with { ... }</c>).</summary>
    public SangamWorkerBuilder AddHandler(string topic, TaskHandler handler, Func<TopicSubscription, TopicSubscription>? configure = null)
    {
        var s = new TopicSubscription(topic, handler);
        return AddSubscription(configure is null ? s : configure(s));
    }

    /// <summary>
    /// A handler class resolved from the container in its own DI scope per work item (registered scoped when absent).
    /// </summary>
    public SangamWorkerBuilder AddHandler<THandler>(string topic, Func<TopicSubscription, TopicSubscription>? configure = null)
        where THandler : class, ISangamTaskHandler
    {
        ArgumentNullException.ThrowIfNull(topic);
        Services.TryAddScoped<THandler>();
        Services.AddSingleton(new SangamSubscriptionSource(sp =>
        {
            var s = new TopicSubscription(topic, async (ctx, ct) =>
            {
                await using var scope = sp.CreateAsyncScope();
                var h = scope.ServiceProvider.GetRequiredService<THandler>();
                return await h.HandleAsync(ctx, ct).ConfigureAwait(false);
            });
            return new[] { configure is null ? s : configure(s) };
        }));
        return this;
    }

    /// <summary>A saga step (M-026): its forward action and its compensation.</summary>
    public SangamWorkerBuilder AddSaga(Saga saga)
    {
        ArgumentNullException.ThrowIfNull(saga);
        Services.AddSingleton(new SangamSubscriptionSource(_ => saga.Subscriptions));
        return this;
    }

    /// <summary>A saga step built from the container (its handlers may capture services).</summary>
    public SangamWorkerBuilder AddSaga(Func<IServiceProvider, Saga> factory)
    {
        ArgumentNullException.ThrowIfNull(factory);
        Services.AddSingleton(new SangamSubscriptionSource(sp => factory(sp).Subscriptions));
        return this;
    }

    /// <summary>
    /// Replaces the bound <see cref="SangamWorkerHostingOptions"/> with complete worker options (back-offs, clock, logger).
    /// </summary>
    public SangamWorkerBuilder WithWorkerOptions(Func<IServiceProvider, SangamWorkerOptions> factory)
    {
        ArgumentNullException.ThrowIfNull(factory);
        Services.RemoveAll<SangamWorkerOptions>();
        Services.AddSingleton(factory);
        return this;
    }
}
