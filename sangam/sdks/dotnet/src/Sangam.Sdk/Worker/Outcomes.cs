// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>A business error that the BPMN model routes (error boundary event). Not retried.</summary>
public class BpmnErrorException : Exception
{
    public string ErrorCode { get; }
    public IDictionary<string, object?>? Variables { get; }

    public BpmnErrorException(string errorCode, string message, IDictionary<string, object?>? variables = null) : base(message)
    {
        ErrorCode = errorCode;
        Variables = variables;
    }
}

/// <summary>
/// The handler cannot know whether the downstream effect happened (for example a payment timed out after sending).
/// The worker reports <c>outcome-unknown</c>; the engine opens an UNKNOWN_OUTCOME reconciliation item and never
/// repeats the effect automatically (VI.3.2 rules 4–5, AC-03).
/// </summary>
public class OutcomeUnknownException : Exception
{
    public string? Details { get; }
    /// <summary>Evidence for the reconciliation item (provider reference, request handle, ...).</summary>
    public IDictionary<string, object?>? Variables { get; }

    public OutcomeUnknownException(string reason, string? details = null, IDictionary<string, object?>? variables = null, Exception? inner = null)
        : base(reason, inner)
    {
        Details = details;
        Variables = variables;
    }
}

/// <summary>
/// A technical failure where the handler asserts that no external effect happened, so a retry is safe.
/// <c>Retries</c>/<c>RetryTimeout</c> override the subscription defaults; <c>Retries = 0</c> raises an incident.
/// </summary>
public class TaskFailedException : Exception
{
    public string? Details { get; }
    public int? Retries { get; }
    public TimeSpan? RetryTimeout { get; }

    public TaskFailedException(string message, Exception? inner = null, string? details = null, int? retries = null, TimeSpan? retryTimeout = null)
        : base(message, inner)
    {
        Details = details;
        Retries = retries;
        RetryTimeout = retryTimeout;
    }
}

/// <summary>Exponential back-off with jitter: min(max, initial * factor^(n-1)) +/- jitter fraction.</summary>
public sealed record Backoff(TimeSpan Initial, TimeSpan Max, double Factor, double Jitter)
{
    public static Backoff FetchDefaults { get; } = new(TimeSpan.FromMilliseconds(500), TimeSpan.FromSeconds(60), 2.0, 0.2);
    /// <summary>R28 default: about one minute, growing, capped at 10 minutes.</summary>
    public static Backoff RetryDefaults { get; } = new(TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(10), 2.0, 0.2);
    public static Backoff ReportDefaults { get; } = new(TimeSpan.FromMilliseconds(500), TimeSpan.FromSeconds(15), 2.0, 0.2);

    /// <summary>Delay for the n-th consecutive failure (1-based).</summary>
    public TimeSpan Delay(int n)
    {
        var b = Initial.TotalMilliseconds * Math.Pow(Factor, Math.Max(0, n - 1));
        var capped = Math.Min(b, Max.TotalMilliseconds);
        var j = capped * Jitter;
        var v = capped + (j == 0 ? 0 : (Random.Shared.NextDouble() * 2 - 1) * j);
        return TimeSpan.FromMilliseconds(Math.Max(0, v));
    }
}

/// <summary>
/// Handles one locked work item, only after the LOCKED confirmation (A-1).
/// Return variables → <c>complete</c>; <see cref="BpmnErrorException"/> → <c>bpmn-error</c>;
/// <see cref="OutcomeUnknownException"/> → <c>outcome-unknown</c>; <see cref="TaskFailedException"/> → <c>fail</c>;
/// <see cref="OperationCanceledException"/> after a cancel request → <c>cancel-ack</c> (otherwise <c>unlock</c>);
/// any other exception → <c>fail</c> with retries, or <c>outcome-unknown</c> when the subscription is irreversible (VI.3.2 rule 5).
/// Downstream calls should send <see cref="TaskContext.OperationId"/> as their idempotency key (R36).
/// </summary>
public delegate Task<IDictionary<string, object?>?> TaskHandler(TaskContext context, CancellationToken cancellationToken);

/// <summary>Handler registration for one topic.</summary>
public sealed record TopicSubscription
{
    public static readonly TimeSpan MaxLock = TimeSpan.FromHours(24);

    public TopicSubscription(string topic, TaskHandler handler)
    {
        Topic = topic ?? throw new ArgumentNullException(nameof(topic));
        Handler = handler ?? throw new ArgumentNullException(nameof(handler));
    }

    public string Topic { get; }
    public TaskHandler Handler { get; }

    private TimeSpan _lock = TimeSpan.FromMinutes(5);
    /// <summary>Lease per fetch (VI.3.2 rule 3: default 5 min, max 24 h); heartbeat at lease/3.</summary>
    public TimeSpan LockDuration
    {
        get => _lock;
        init
        {
            if (value > MaxLock) throw new ArgumentOutOfRangeException(nameof(LockDuration), "lockDuration exceeds 24 h (VI.3)");
            if (value < TimeSpan.FromSeconds(3)) throw new ArgumentOutOfRangeException(nameof(LockDuration), "lockDuration must be at least 3 s");
            _lock = value;
        }
    }

    /// <summary>Variables to fetch (null = all).</summary>
    public IReadOnlyList<string>? Variables { get; init; }

    /// <summary>Fetch only local variables of the activity (TopicRequest.localVariables).</summary>
    public bool? LocalVariables { get; init; }

    /// <summary>Only fetch work of this process definition key.</summary>
    public string? ProcessDefinitionKey { get; init; }

    /// <summary>Only fetch work of this business key.</summary>
    public string? BusinessKey { get; init; }

    private int _retries = 3;
    /// <summary>Retries for the first failure when the work item carries none.</summary>
    public int Retries
    {
        get => _retries;
        init => _retries = value >= 0 ? value : throw new ArgumentOutOfRangeException(nameof(Retries));
    }

    public Backoff RetryBackoff { get; init; } = Backoff.RetryDefaults;

    /// <summary>
    /// The topic has an irreversible external effect (VI.3.2 rule 5): unexpected handler exceptions are reported as
    /// outcome-unknown instead of fail-with-retry. Contract 0.1.0 carries no per-item effect flag, so this is the source.
    /// </summary>
    public bool Irreversible { get; init; }
}
