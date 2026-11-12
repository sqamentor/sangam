// SPDX-License-Identifier: Apache-2.0
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace Sangam.Sdk.Hosting;

/// <summary>
/// <c>services.AddSangamWorker(...)</c> (M-027): registers <see cref="SangamWorkerHostedService"/>, which starts the SDK
/// worker with the host and stops it gracefully (<see cref="Worker.SangamWorker.StopAsync"/>) on shutdown.
/// <example><code>
/// builder.Services.AddSangamWorker(builder.Configuration.GetSection(SangamWorkerHostingOptions.SectionName))
///     .AddHandler&lt;ChargeHandler&gt;("payment.charge")
///     .AddSaga(Saga.Step("charge").Action(...).Compensation(...).Build());
/// </code></example>
/// </summary>
public static class SangamServiceCollectionExtensions
{
    /// <summary>Adds the hosted worker; <paramref name="configure"/> sets <see cref="SangamWorkerHostingOptions"/>.</summary>
    public static SangamWorkerBuilder AddSangamWorker(this IServiceCollection services, Action<SangamWorkerHostingOptions>? configure = null)
    {
        ArgumentNullException.ThrowIfNull(services);
        services.AddOptions<SangamWorkerHostingOptions>();
        if (configure is not null) services.Configure(configure);
        if (!services.Any(d => d.ServiceType == typeof(SangamWorkerHostedService)))
        {
            services.AddSingleton<SangamWorkerHostedService>();
            services.AddSingleton<IHostedService>(sp => sp.GetRequiredService<SangamWorkerHostedService>());
        }
        return new SangamWorkerBuilder(services);
    }

    /// <summary>Adds the hosted worker with <see cref="SangamWorkerHostingOptions"/> bound from <paramref name="configuration"/>.</summary>
    public static SangamWorkerBuilder AddSangamWorker(this IServiceCollection services, IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(configuration);
        var b = services.AddSangamWorker();
        services.Configure<SangamWorkerHostingOptions>(configuration);
        return b;
    }
}
