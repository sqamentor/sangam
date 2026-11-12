// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using Sangam.Sdk.Logging;
using Sangam.Sdk.Models;

namespace Sangam.Sdk.Worker;

/// <summary>
/// What a handler sees while it holds the lease: the work item, its identities, the live lease state kept fresh by
/// the heartbeat, cancellation, a logger pre-filled with the work fields, and the client.
/// </summary>
public sealed class TaskContext
{
    private long _lockVersion;
    private long _lockExpiresAtTicks;
    private volatile bool _cancelRequested;
    private volatile bool _leaseLost;
    private volatile bool _shuttingDown;
    internal readonly CancellationTokenSource Cts;

    internal TaskContext(ExternalWork work, string workerId, SangamClient client, CancellationToken workerStop)
    {
        Work = work;
        WorkerId = workerId;
        Client = client;
        if (work.Id is null || work.AttemptId is null || work.LockVersion is null)
            // A-1: a handler runs only after the LOCKED confirmation (work id, lockVersion and attemptId)
            throw new ArgumentException("work item without its LOCKED confirmation (id, lockVersion, attemptId)", nameof(work));
        _lockVersion = work.LockVersion.Value;
        _lockExpiresAtTicks = (work.LockExpiresAt ?? DateTimeOffset.MaxValue).UtcTicks;
        Cts = CancellationTokenSource.CreateLinkedTokenSource(workerStop);
        var f = new Dictionary<string, object?>
        {
            ["workerId"] = workerId,
            ["workId"] = work.Id,
            ["topic"] = work.Topic,
            ["lockVersion"] = _lockVersion,
        };
        if (work.OperationId is not null) f["operationId"] = work.OperationId;
        if (work.AttemptId is not null) f["attemptId"] = work.AttemptId;
        if (work.BusinessKey is not null) f["businessKey"] = work.BusinessKey;
        if (work.ProcessInstanceId is not null) f["processInstanceId"] = work.ProcessInstanceId;
        LogFields = f;
        Logger = new FieldLogger(client.Logger, f);
    }

    public ExternalWork Work { get; }
    public string WorkId => Work.Id!;
    /// <summary>The topic (the worker starts only items whose topic matches a subscription).</summary>
    public string Topic => Work.Topic ?? "";
    public string? BusinessKey => Work.BusinessKey;
    public string? ProcessInstanceId => Work.ProcessInstanceId;
    public string WorkerId { get; }
    public SangamClient Client { get; }

    /// <summary>R36 stable business-operation id (falls back to the work id).</summary>
    public string OperationId => Work.OperationId ?? Work.Id!;

    /// <summary>Idempotency key to forward downstream (= operationId, VI.3.2 rule 4).</summary>
    public string IdempotencyKey => OperationId;

    /// <summary>Changes per lease (A-2).</summary>
    public string? AttemptId => Work.AttemptId;

    /// <summary>The server-recorded operation-ledger entry of <see cref="OperationId"/> (SL-16); null from older servers.</summary>
    public WorkOperation? Operation => Work.Operation;

    /// <summary>W3C <c>traceparent</c> the engine stored for this task (ExternalWork.traceContext, M-062), or null.</summary>
    public string? Traceparent => Work.TraceContext?.Traceparent;

    /// <summary>W3C <c>tracestate</c> of the task's trace context, or null.</summary>
    public string? Tracestate => Work.TraceContext?.Tracestate;

    /// <summary>
    /// Downstream reference of this work's effect (for example a payment reference). Set it in the handler; it is sent
    /// as <c>outcome.reference</c> on complete and recorded as the operation-ledger providerRef (SL-16).
    /// </summary>
    public string? OutcomeReference { get; set; }

    /// <summary>Current fencing token.</summary>
    public long LockVersion => Interlocked.Read(ref _lockVersion);
    internal void SetLockVersion(long v) => Interlocked.Exchange(ref _lockVersion, v);

    public DateTimeOffset LockExpiresAt => new(Interlocked.Read(ref _lockExpiresAtTicks), TimeSpan.Zero);
    internal void SetLockExpiresAt(DateTimeOffset v) => Interlocked.Exchange(ref _lockExpiresAtTicks, v.UtcTicks);

    public IReadOnlyDictionary<string, object?> Variables =>
        (IReadOnlyDictionary<string, object?>?)Work.Variables ?? new Dictionary<string, object?>();

    /// <summary>
    /// A variable converted to <typeparamref name="T"/>. R33 TypedValues are unwrapped, so
    /// <c>{"type":"Decimal","value":"12.50"}</c> reads as <c>12.50m</c>; plain JSON values convert directly.
    /// </summary>
    public T? Variable<T>(string name)
    {
        if (!Variables.TryGetValue(name, out var v) || v is null) return default;
        if (v is T t) return t;
        var je = v as JsonElement? ?? JsonSerializer.SerializeToElement(v, Client.Transport.Json);
        return TypedValue.Unwrap<T>(je, Client.Transport.Json);
    }

    /// <summary>True once a heartbeat reported <c>cancelRequested</c> (VI.3.1). The token is cancelled as well.</summary>
    public bool IsCancelRequested => _cancelRequested;
    internal void MarkCancelRequested() { _cancelRequested = true; TryCancel(); }

    /// <summary>True once the lease is gone (409, 410, or expiry while unreachable); results are discarded.</summary>
    public bool IsLeaseLost => _leaseLost;
    internal void MarkLeaseLost() { _leaseLost = true; TryCancel(); }

    public bool IsShuttingDown => _shuttingDown;
    internal void MarkShuttingDown() { _shuttingDown = true; TryCancel(); }

    /// <summary>Cancelled on lease loss, cancel request or forced shutdown.</summary>
    public CancellationToken CancellationToken => Cts.Token;

    /// <summary>Cooperative checkpoint.</summary>
    public void ThrowIfCancelled() => Cts.Token.ThrowIfCancellationRequested();

    /// <summary>
    /// Durable step (M-025, R37): runs <paramref name="fn"/> at most once per successful outcome of this work's operation,
    /// as far as the step journal can tell. A step that an earlier attempt recorded <c>SUCCEEDED</c> is not called again;
    /// its recorded result is decoded to <typeparamref name="T"/> (R33 typed values unwrapped as in
    /// <see cref="Variable{T}"/>) and returned. The call should send <see cref="Step.IdempotencyKey"/> to the remote API.
    /// <para>
    /// <b>NOT exactly-once for remote APIs.</b> The journal is written before (<c>INTENT</c>) and after
    /// (<c>SUCCEEDED</c>) the remote call; a crash between the two leaves <c>INTENT</c>. The next attempt then looks the
    /// outcome up, runs again only when the call is idempotent, or reports outcome-unknown (<see cref="StepOptions{T}"/>),
    /// never re-issuing blindly. Default options are capability class (c).
    /// </para>
    /// A 409 STALE_LEASE or 410 from the journal marks the lease lost (<see cref="IsLeaseLost"/>) and throws
    /// <see cref="OperationCanceledException"/>.
    /// </summary>
    /// <param name="name">step name, unique within the work's operation (1-128 printable ASCII)</param>
    /// <param name="fn">the side-effecting call (<see cref="StepCall{T}"/>)</param>
    /// <param name="options">recovery options of the call's capability class; null = class (c)</param>
    /// <param name="cancellationToken">token for the journal calls and <paramref name="fn"/>; none = <see cref="CancellationToken"/></param>
    public Task<T> StepAsync<T>(string name, StepCall<T> fn, StepOptions<T>? options = null, CancellationToken cancellationToken = default) =>
        DurableSteps.RunAsync(this, name, fn, options, cancellationToken);

    /// <summary>Logger whose lines always carry workerId, workId, topic, operationId, attemptId, lockVersion, businessKey.</summary>
    public ISangamLogger Logger { get; }
    public IReadOnlyDictionary<string, object?> LogFields { get; }

    private void TryCancel()
    {
        try { Cts.Cancel(); } catch (ObjectDisposedException) { }
    }

    private sealed class FieldLogger : ISangamLogger
    {
        private readonly ISangamLogger _inner;
        private readonly IReadOnlyDictionary<string, object?> _fields;
        public FieldLogger(ISangamLogger inner, IReadOnlyDictionary<string, object?> fields) { _inner = inner; _fields = fields; }

        public void Log(SangamLogLevel level, string @event, string message, IReadOnlyDictionary<string, object?>? fields, Exception? error)
        {
            var merged = new Dictionary<string, object?>(_fields);
            if (fields is not null) foreach (var kv in fields) merged[kv.Key] = kv.Value;
            _inner.Log(level, @event, message, merged, error);
        }
    }
}
