// SPDX-License-Identifier: Apache-2.0
using Sangam.Sdk.Worker;

namespace Sangam.Sdk.Hosting;

/// <summary>
/// Options of the hosted Sangam worker (M-027), bindable from configuration (for example the <c>Sangam:Worker</c>
/// section: <c>"BaseUrl"</c>, <c>"Token"</c>, <c>"WorkerId"</c>, <c>"MaxConcurrency"</c>, <c>"ShutdownTimeout": "00:00:30"</c>).
/// Unset members keep the <see cref="SangamWorkerOptions"/> defaults.
/// </summary>
public sealed class SangamWorkerHostingOptions
{
    /// <summary>Default configuration section: <c>Sangam:Worker</c>.</summary>
    public const string SectionName = "Sangam:Worker";

    /// <summary>Server root; required unless a <see cref="SangamClient"/> is registered in the container.</summary>
    public string? BaseUrl { get; set; }

    /// <summary>Static bearer token (dev profile); null = no token, or a registered client's own token provider.</summary>
    public string? Token { get; set; }

    public string? WorkerId { get; set; }

    /// <summary>Maximum handlers running at once (default 8).</summary>
    public int? MaxConcurrency { get; set; }

    public int? MaxTasksPerFetch { get; set; }

    /// <summary>Long-poll wait on the server (default 30 s).</summary>
    public TimeSpan? AsyncResponseTimeout { get; set; }

    /// <summary>How long a stop waits for in-flight handlers before cancelling them (default 30 s).</summary>
    public TimeSpan? ShutdownTimeout { get; set; }

    public bool? UsePriority { get; set; }

    /// <summary>False keeps the worker registered but not started (for example on a read-only replica). Default true.</summary>
    public bool Enabled { get; set; } = true;

    internal SangamWorkerOptions ToWorkerOptions(Sangam.Sdk.Logging.ISangamLogger? logger)
    {
        var d = new SangamWorkerOptions();
        return new SangamWorkerOptions
        {
            WorkerId = WorkerId,
            MaxConcurrency = MaxConcurrency ?? d.MaxConcurrency,
            MaxTasksPerFetch = MaxTasksPerFetch ?? d.MaxTasksPerFetch,
            AsyncResponseTimeout = AsyncResponseTimeout ?? d.AsyncResponseTimeout,
            ShutdownTimeout = ShutdownTimeout ?? d.ShutdownTimeout,
            UsePriority = UsePriority,
            Logger = logger,
        };
    }
}
