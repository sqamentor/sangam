// SPDX-License-Identifier: Apache-2.0
using System.Collections.Concurrent;
using Sangam.Sdk.Errors;
using Sangam.Sdk.Logging;
using Sangam.Sdk.Models;
using static Sangam.Sdk.Logging.SangamLoggers;

namespace Sangam.Sdk.Worker;

/// <summary>Options for <see cref="SangamWorker"/>.</summary>
public sealed class SangamWorkerOptions
{
    public string? WorkerId { get; init; }
    /// <summary>Maximum handlers running at once (default 8).</summary>
    public int MaxConcurrency { get; init; } = 8;
    public int MaxTasksPerFetch { get; init; } = 8;
    /// <summary>Long-poll wait on the server (default 30 s).</summary>
    public TimeSpan AsyncResponseTimeout { get; init; } = TimeSpan.FromSeconds(30);
    /// <summary>Heartbeat interval as a fraction of the lease (VI.3: 1/3).</summary>
    public double HeartbeatFraction { get; init; } = 1.0 / 3.0;
    public Backoff FetchBackoff { get; init; } = Backoff.FetchDefaults;
    public Backoff ReportBackoff { get; init; } = Backoff.ReportDefaults;
    public TimeSpan ShutdownTimeout { get; init; } = TimeSpan.FromSeconds(30);
    /// <summary>Ask the server to hand out higher-priority work first (FetchAndLockRequest.usePriority).</summary>
    public bool? UsePriority { get; init; }
    public ISangamLogger? Logger { get; init; }
    public Func<DateTimeOffset> Clock { get; init; } = () => DateTimeOffset.UtcNow;
}

/// <summary>
/// External-worker runtime for the Sangam worker API (VI.3, A-1, A-2, R9, R20, R36).
/// <list type="bullet">
/// <item>Long-poll fetch-and-lock for all topics, asking only for free slots; fetch errors back off with jitter.</item>
/// <item>A-1: a handler starts only for items that came back LOCKED with a lockVersion and attemptId.</item>
/// <item>Heartbeat (<c>extend-lock</c>) every lease/3; the answer carries <c>cancelRequested</c>.</item>
/// <item>Fencing: every call carries (workerId, lockVersion, attemptId); 409 stale-lease / 410 gone means the lease is
/// lost: the handler token is cancelled and its result discarded.</item>
/// <item>Idempotency-Key per call <c>&lt;operationId&gt;:&lt;action&gt;:&lt;lockVersion&gt;</c> (R41).</item>
/// <item>Result calls are retried until the lease expires; failures report decreasing retries and growing back-off.</item>
/// <item>Graceful shutdown: <see cref="StopAsync"/> stops fetching, waits for in-flight handlers, then cancels the
/// rest and unlocks their items.</item>
/// </list>
/// </summary>
public sealed class SangamWorker : IAsyncDisposable
{
    private readonly SangamClient _client;
    private readonly SangamWorkerOptions _o;
    private readonly ISangamLogger _log;
    private readonly ConcurrentDictionary<string, TopicSubscription> _subs = new();
    private readonly ConcurrentDictionary<string, TaskContext> _inFlight = new();
    private readonly ConcurrentDictionary<Task, byte> _running = new();
    private readonly SemaphoreSlim _slots;
    private readonly int _maxPerFetch;
    private long _heartbeatSeq;
    private CancellationTokenSource? _stopFetch;
    private CancellationTokenSource? _force;
    private Task? _poller;
    private int _state; // 0 idle, 1 running, 2 stopping

    public SangamWorker(SangamClient client, SangamWorkerOptions? options = null, IEnumerable<TopicSubscription>? subscriptions = null)
    {
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _o = options ?? new SangamWorkerOptions();
        if (_o.MaxConcurrency < 1) throw new ArgumentOutOfRangeException(nameof(options), "MaxConcurrency must be >= 1");
        if (_o.HeartbeatFraction is <= 0 or >= 1) throw new ArgumentOutOfRangeException(nameof(options), "HeartbeatFraction must be in (0,1)");
        _log = _o.Logger ?? client.Logger;
        WorkerId = _o.WorkerId ?? $"{Environment.MachineName}-{Environment.ProcessId}-{Guid.NewGuid().ToString("N")[..8]}";
        _slots = new SemaphoreSlim(_o.MaxConcurrency, _o.MaxConcurrency);
        _maxPerFetch = Math.Max(1, Math.Min(_o.MaxTasksPerFetch, _o.MaxConcurrency));
        if (subscriptions is not null) foreach (var s in subscriptions) _subs[s.Topic] = s;
    }

    public string WorkerId { get; }
    public int InFlightCount => _inFlight.Count;

    /// <summary>Registers (or replaces) a handler; takes effect on the next fetch (Flowable-client parity).</summary>
    public SangamWorker Subscribe(TopicSubscription subscription)
    {
        _subs[subscription.Topic] = subscription;
        _log.Info("worker.subscribed", "subscribed", F(("workerId", WorkerId), ("topic", subscription.Topic)));
        return this;
    }

    public SangamWorker Subscribe(string topic, TaskHandler handler) => Subscribe(new TopicSubscription(topic, handler));

    /// <summary>Stops fetching a topic; running items finish normally.</summary>
    public SangamWorker Unsubscribe(string topic)
    {
        _subs.TryRemove(topic, out _);
        _log.Info("worker.unsubscribed", "unsubscribed", F(("workerId", WorkerId), ("topic", topic)));
        return this;
    }

    /// <summary>Starts the poll loop and returns.</summary>
    public void Start()
    {
        if (Interlocked.CompareExchange(ref _state, 1, 0) != 0) return;
        _stopFetch = new CancellationTokenSource();
        _force = new CancellationTokenSource();
        _poller = Task.Run(() => PollLoopAsync(_stopFetch.Token));
        _log.Info("worker.started", "worker started", F(("workerId", WorkerId), ("maxConcurrency", _o.MaxConcurrency),
            ("topics", string.Join(",", _subs.Keys))));
    }

    /// <summary>Starts and runs until <paramref name="ct"/> is cancelled (for example Ctrl+C / SIGTERM), then stops gracefully.</summary>
    public async Task RunAsync(CancellationToken ct)
    {
        Start();
        try { await Task.Delay(Timeout.Infinite, ct).ConfigureAwait(false); }
        catch (OperationCanceledException) { }
        await StopAsync().ConfigureAwait(false);
    }

    /// <summary>Stops fetching, waits up to ShutdownTimeout for in-flight handlers, then cancels the rest (they are unlocked).</summary>
    public async Task StopAsync()
    {
        if (Interlocked.CompareExchange(ref _state, 2, 1) != 1) return;
        _log.Info("worker.stopping", "stopping: no new fetches", F(("workerId", WorkerId), ("inFlight", _inFlight.Count)));
        _stopFetch!.Cancel();
        try { if (_poller is not null) await _poller.ConfigureAwait(false); } catch { /* logged in loop */ }
        var all = Task.WhenAll(_running.Keys);
        if (await Task.WhenAny(all, Task.Delay(_o.ShutdownTimeout)).ConfigureAwait(false) != all)
        {
            _log.Warn("worker.shutdown.timeout", "cancelling handlers still running", F(("workerId", WorkerId), ("inFlight", _inFlight.Count)));
            foreach (var c in _inFlight.Values) c.MarkShuttingDown();
            _force!.Cancel();
            await Task.WhenAny(all, Task.Delay(TimeSpan.FromSeconds(10))).ConfigureAwait(false);
        }
        Interlocked.Exchange(ref _state, 0);
        _log.Info("worker.stopped", "worker stopped", F(("workerId", WorkerId)));
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        _stopFetch?.Dispose();
        _force?.Dispose();
    }

    // ------------------------------------------------------------------ fetch loop

    private async Task PollLoopAsync(CancellationToken stop)
    {
        var errors = 0;
        while (!stop.IsCancellationRequested)
        {
            try { await _slots.WaitAsync(stop).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }
            var permits = 1;
            while (permits < _maxPerFetch && _slots.Wait(0)) permits++;

            var subs = _subs.Values.ToList();
            if (subs.Count == 0)
            {
                _slots.Release(permits);
                try { await Task.Delay(1000, stop).ConfigureAwait(false); } catch (OperationCanceledException) { break; }
                continue;
            }
            IReadOnlyList<ExternalWork> items;
            try
            {
                var topics = subs.Select(s => new FetchTopic(s.Topic, (long)s.LockDuration.TotalMilliseconds, s.Variables,
                    s.LocalVariables, s.ProcessDefinitionKey, s.BusinessKey)).ToList();
                items = await _client.Work.FetchAndLockAsync(new FetchAndLockRequest(WorkerId, topics, permits,
                    (long)_o.AsyncResponseTimeout.TotalMilliseconds, _o.UsePriority), stop).ConfigureAwait(false);
                errors = 0;
            }
            catch (Exception e)
            {
                _slots.Release(permits);
                if (stop.IsCancellationRequested) break;
                errors++;
                var d = _o.FetchBackoff.Delay(errors);
                _log.Warn("work.fetch.failed", "fetch-and-lock failed; backing off",
                    F(("workerId", WorkerId), ("attempt", errors), ("delayMs", (long)d.TotalMilliseconds)), e);
                try { await Task.Delay(d, stop).ConfigureAwait(false); } catch (OperationCanceledException) { break; }
                continue;
            }

            var used = 0;
            foreach (var w in items)
            {
                if (used >= permits)
                {
                    _log.Warn("work.overdelivered", "server returned more items than requested; unlocking", F(("workerId", WorkerId), ("workId", w.Id)));
                    await UnlockQuietlyAsync(w).ConfigureAwait(false);
                    continue;
                }
                if (!_subs.TryGetValue(w.Topic ?? "", out var sub))
                {
                    _log.Warn("work.unsubscribed", "no handler for topic; unlocking", F(("workerId", WorkerId), ("workId", w.Id), ("topic", w.Topic)));
                    await UnlockQuietlyAsync(w).ConfigureAwait(false);
                    continue;
                }
                if (w.AttemptId is null || w.Id is null)
                {
                    // A-1: run only after the LOCKED confirmation with lockVersion + attemptId
                    _log.Error("work.protocol.violation", "item without LOCKED confirmation (attemptId); not started", F(("workerId", WorkerId), ("workId", w.Id)));
                    continue;
                }
                used++;
                var t = Task.Run(() => RunItemAsync(w, sub));
                _running[t] = 0;
                _ = t.ContinueWith(x => _running.TryRemove(x, out _), TaskScheduler.Default);
            }
            if (permits - used > 0) _slots.Release(permits - used);
        }
        _log.Debug("worker.poller.exit", "poller exited", F(("workerId", WorkerId)));
    }

    // ------------------------------------------------------------------ one item

    private sealed record Outcome(string Kind, IDictionary<string, object?>? Variables, Exception? Error);

    private async Task RunItemAsync(ExternalWork work, TopicSubscription sub)
    {
        var ctx = new TaskContext(work, WorkerId, _client, _force!.Token);
        _inFlight[work.Id] = ctx;
        using var hbStop = new CancellationTokenSource();
        var heartbeat = Task.Run(() => HeartbeatLoopAsync(ctx, sub, hbStop.Token));
        try
        {
            ctx.Logger.Info("work.started", "handler started");
            var outcome = await InvokeAsync(ctx, sub).ConfigureAwait(false);
            hbStop.Cancel();
            try { await heartbeat.ConfigureAwait(false); } catch { /* heartbeat ends on cancel */ }
            if (ctx.IsLeaseLost)
            {
                ctx.Logger.Warn("work.abandoned", "lease lost; result discarded (another attempt owns it)", F(("outcome", outcome.Kind)));
                return;
            }
            await ReportAsync(ctx, sub, outcome).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            ctx.Logger.Error("work.internal.error", "unexpected worker error", null, e);
        }
        finally
        {
            hbStop.Cancel();
            _inFlight.TryRemove(work.Id, out _);
            ctx.Cts.Dispose();
            _slots.Release();
        }
    }

    private static async Task<Outcome> InvokeAsync(TaskContext ctx, TopicSubscription sub)
    {
        try
        {
            var vars = await sub.Handler(ctx, ctx.CancellationToken).ConfigureAwait(false);
            return new Outcome("complete", vars, null);
        }
        catch (BpmnErrorException e) { return new Outcome("bpmn-error", e.Variables, e); }
        catch (OutcomeUnknownException e) { return new Outcome("outcome-unknown", e.Variables, e); }
        catch (TaskFailedException e) { return new Outcome("fail", null, e); }
        catch (OperationCanceledException e) when (ctx.IsCancelRequested || ctx.IsShuttingDown || ctx.IsLeaseLost)
        {
            return new Outcome(ctx.IsCancelRequested ? "cancel-ack" : "unlock", null, e);
        }
        catch (Exception e)
        {
            return new Outcome(sub.Irreversible ? "outcome-unknown" : "fail", null, e);
        }
    }

    private async Task HeartbeatLoopAsync(TaskContext ctx, TopicSubscription sub, CancellationToken done)
    {
        var lease = (long)sub.LockDuration.TotalMilliseconds;
        var interval = TimeSpan.FromMilliseconds(Math.Max(1000, lease * _o.HeartbeatFraction));
        var failures = 0;
        while (!done.IsCancellationRequested)
        {
            var wait = failures == 0 ? interval : TimeSpan.FromMilliseconds(Math.Min(interval.TotalMilliseconds, _o.ReportBackoff.Delay(failures).TotalMilliseconds));
            try { await Task.Delay(wait, done).ConfigureAwait(false); } catch (OperationCanceledException) { return; }
            try
            {
                var key = $"{ctx.OperationId}:extend-lock:{ctx.LockVersion}:{Interlocked.Increment(ref _heartbeatSeq)}";
                var r = await _client.Work.ExtendLockAsync(ctx.WorkId,
                    new ExtendLockRequest(WorkerId, ctx.LockVersion, ctx.AttemptId, lease), key, done).ConfigureAwait(false);
                failures = 0;
                if (r is not null)
                {
                    if (r.LockExpiresAt is { } exp) ctx.SetLockExpiresAt(exp);
                    if (r.LockVersion is { } lv) ctx.SetLockVersion(lv);
                    if (r.CancelRequested && !ctx.IsCancelRequested)
                    {
                        ctx.Logger.Warn("work.cancel.requested", "server requested cancel");
                        ctx.MarkCancelRequested();
                    }
                }
                ctx.Logger.Debug("work.heartbeat", "lease extended", F(("lockExpiresAt", ctx.LockExpiresAt.ToString("O"))));
            }
            catch (Exception e) when (e is StaleLeaseException or GoneException)
            {
                LoseLease(ctx, e);
                return;
            }
            catch (OperationCanceledException) when (done.IsCancellationRequested) { return; }
            catch (Exception e)
            {
                failures++;
                if (_o.Clock() > ctx.LockExpiresAt)
                {
                    LoseLease(ctx, e);
                    return;
                }
                ctx.Logger.Warn("work.heartbeat.failed", "heartbeat failed; will retry before lease expiry", F(("failures", failures)), e);
            }
        }
    }

    private static void LoseLease(TaskContext ctx, Exception cause)
    {
        ctx.Logger.Warn("work.lease.lost", "lease lost; cancelling handler", null, cause);
        ctx.MarkLeaseLost();
    }

    // ------------------------------------------------------------------ reporting

    private async Task ReportAsync(TaskContext ctx, TopicSubscription sub, Outcome o)
    {
        var action = o.Kind;
        Func<Task> call = action switch
        {
            "complete" => () => _client.Work.CompleteAsync(ctx.WorkId,
                new CompleteWorkRequest(WorkerId, ctx.LockVersion, ctx.AttemptId, o.Variables), Key(ctx, action)),
            "bpmn-error" => () =>
            {
                var be = (BpmnErrorException)o.Error!;
                return _client.Work.BpmnErrorAsync(ctx.WorkId, new BpmnErrorRequest(WorkerId, ctx.LockVersion, ctx.AttemptId,
                    be.ErrorCode, be.Message, be.Variables), Key(ctx, action));
            },
            "outcome-unknown" => () =>
            {
                var ou = o.Error as OutcomeUnknownException;
                var reason = ou is not null ? ou.Message : "unexpected error on irreversible topic: " + o.Error?.Message;
                var details = ou is not null ? ou.Details : Trace(o.Error);
                var detail = details is null ? reason : reason + Environment.NewLine + details;
                if (detail.Length > 4000) detail = detail[..4000];
                return _client.Work.OutcomeUnknownAsync(ctx.WorkId, new OutcomeUnknownRequest(WorkerId, ctx.LockVersion,
                    ctx.AttemptId, detail, o.Variables), Key(ctx, action));
            },
            "fail" => () => _client.Work.FailAsync(ctx.WorkId, FailRequest(ctx, sub, o.Error), Key(ctx, action)),
            "cancel-ack" => () => _client.Work.CancelAckAsync(ctx.WorkId,
                new UnlockRequest(WorkerId, ctx.LockVersion, ctx.AttemptId), Key(ctx, action)),
            _ => () => _client.Work.UnlockAsync(ctx.WorkId,
                new UnlockRequest(WorkerId, ctx.LockVersion, ctx.AttemptId), Key(ctx, "unlock")),
        };
        var attempt = 0;
        while (true)
        {
            attempt++;
            try
            {
                await call().ConfigureAwait(false);
                var f = new Dictionary<string, object?> { ["attempts"] = attempt };
                if (o.Error is not null) f["error"] = o.Error.Message;
                ctx.Logger.Info("work." + action, "reported " + action, f);
                return;
            }
            catch (Exception e) when (e is StaleLeaseException or GoneException)
            {
                ctx.MarkLeaseLost();
                ctx.Logger.Warn("work.abandoned", action + " rejected: lease no longer ours", null, e);
                return;
            }
            catch (SangamApiException e)
            {
                if (!e.IsRetryable || !RetryAllowed(ctx))
                {
                    ctx.Logger.Error("work.report.failed", action + " rejected by server", F(("status", e.Status)), e);
                    return;
                }
            }
            catch (TransportException e)
            {
                if (!RetryAllowed(ctx))
                {
                    ctx.Logger.Error("work.report.gave-up",
                        action + " not delivered before lease expiry; the lease will expire and the same operationId is reused",
                        F(("attempts", attempt)), e);
                    return;
                }
            }
            await Task.Delay(_o.ReportBackoff.Delay(attempt)).ConfigureAwait(false);
        }
    }

    private bool RetryAllowed(TaskContext ctx) => _o.Clock() < ctx.LockExpiresAt;

    private FailWorkRequest FailRequest(TaskContext ctx, TopicSubscription sub, Exception? error)
    {
        var b = ctx.Work.Retries ?? sub.Retries;
        var remaining = Math.Max(0, b - 1);
        var timeout = sub.RetryBackoff.Delay(Math.Max(1, sub.Retries - remaining));
        var details = Trace(error);
        if (error is TaskFailedException tf)
        {
            if (tf.Retries is { } r) remaining = Math.Max(0, r);
            if (tf.RetryTimeout is { } rt) timeout = rt;
            if (tf.Details is not null) details = tf.Details;
        }
        var msg = error?.Message ?? "failed";
        if (msg.Length > 1000) msg = msg[..1000];
        return new FailWorkRequest(WorkerId, ctx.LockVersion, ctx.AttemptId, msg, details, remaining, (long)timeout.TotalMilliseconds);
    }

    private static string Key(TaskContext ctx, string action) => $"{ctx.OperationId}:{action}:{ctx.LockVersion}";

    private async Task UnlockQuietlyAsync(ExternalWork w)
    {
        try
        {
            var op = w.OperationId ?? w.Id;
            await _client.Work.UnlockAsync(w.Id, new UnlockRequest(WorkerId, w.LockVersion, w.AttemptId), $"{op}:unlock:{w.LockVersion}").ConfigureAwait(false);
        }
        catch (Exception e)
        {
            _log.Warn("work.unlock.failed", "unlock failed; lease will expire", F(("workerId", WorkerId), ("workId", w.Id)), e);
        }
    }

    private static string? Trace(Exception? e)
    {
        if (e is null) return null;
        var s = e.ToString();
        return s.Length > 4000 ? s[..4000] : s;
    }
}
