// SPDX-License-Identifier: Apache-2.0
using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Sangam.Sdk.Http;
using Sangam.Sdk.Logging;

namespace Sangam.Sdk.Testing;

/// <summary>One request as the test engine received it.</summary>
public sealed record EngineCall(string Method, string Path, string Action, string? IdempotencyKey, JsonNode? Body);

/// <summary>A reconciliation item opened by the test engine (VI.3 rule 4, VI.5).</summary>
public sealed record EngineReconciliationItem(string Id, string Kind, string WorkId, string OperationId, string State);

/// <summary>A process instance started through <c>POST /process-instances</c>.</summary>
public sealed record EngineInstance(string Id, string Key, string? BusinessKey, JsonNode? Variables, DateTimeOffset StartTime);

/// <summary>
/// R18 test engine of the .NET SDK (M-021): an in-process fake of the Sangam worker API (<c>/api/v1/work/**</c>) and of
/// <c>POST /api/v1/process-instances</c>, plugged in as the client's <see cref="HttpMessageHandler"/> (no sockets, no
/// package). Workers and clients under test run unchanged on top of it, so transport, fencing, Idempotency-Key and error
/// mapping are exercised end to end.
/// <para>
/// The state machine follows the server (VI.3, VI.5; OperatonWorkAdapter, LeaseTable, Tombstones, IdempotencyStore,
/// ApiProblem): a grant sets LOCKED, lockVersion + 1 (1 for new work) and a new attemptId, with a lease of
/// lockDurationMs (default 5 min, at most 24 h); worker calls are fenced in the server's order NO_LEASE, STALE_VERSION,
/// NOT_OWNER, EXPIRED, ATTEMPT_MISMATCH (409 STALE_LEASE, nothing changes); expired leases end before every request
/// (lockVersion + 1; AVAILABLE again, or UNKNOWN_OUTCOME with a reconciliation item for an irreversible topic or an
/// unacknowledged deleted instance); fail with retries &gt; 0 re-offers after retryTimeoutMs, retries = 0 is an
/// INCIDENT, and an irreversible topic with retries left goes to UNKNOWN_OUTCOME; a repeated identical completion for
/// the same lockVersion is answered 204; results for a deleted instance get 410 GONE_INSTANCE_DELETED while heartbeats
/// report cancelRequested; non-fenced mutations use the R41 store (replay with <c>Idempotent-Replayed: true</c>, or
/// 422 IDEMPOTENCY_KEY_REUSED for another body).
/// </para>
/// <para>
/// Differences by design: one tenant, no authentication; time is the real clock plus <see cref="Advance"/>; a
/// fetch-and-lock waits at most min(asyncResponseTimeoutMs, <see cref="LongPollCap"/>) for work; any other path answers
/// 501 CAPABILITY_NOT_INSTALLED.
/// </para>
/// <example><code>
/// var engine = new SangamTestEngine();
/// using var client = engine.Client();
/// var id = engine.AddWork("pay", new Dictionary&lt;string, object?&gt; { ["amount"] = TypedValue.Decimal(12.50m) });
/// await using var worker = new SangamWorker(client, new SangamWorkerOptions { Clock = engine.Now });
/// worker.Subscribe("pay", (ctx, ct) =&gt; Task.FromResult&lt;IDictionary&lt;string, object?&gt;?&gt;(null));
/// worker.Start();
/// await engine.WaitForStateAsync(id, "COMPLETED", TimeSpan.FromSeconds(5));
/// </code></example>
/// </summary>
public sealed class SangamTestEngine : HttpMessageHandler
{
    public static readonly TimeSpan DefaultLock = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan MaxLock = TimeSpan.FromHours(24);
    private const string TypeBase = "https://sangam.dev/problems/";

    private readonly object _gate = new();
    private readonly Dictionary<string, Work> _works = new();
    private readonly List<string> _order = new();
    private readonly Dictionary<string, Recon> _recon = new();
    private readonly Dictionary<string, EngineInstance> _instances = new();
    private readonly List<string> _instanceOrder = new();
    private readonly Dictionary<string, (JsonElement Fingerprint, int Status, string? Body)> _idem = new();
    private readonly HashSet<string> _irreversible = new(StringComparer.Ordinal);
    private readonly Dictionary<string, int> _lose = new(StringComparer.Ordinal);
    private readonly List<EngineCall> _calls = new();
    private readonly EngineSteps _journal;
    private long _seq;
    private long _offsetTicks;

    public SangamTestEngine() => _journal = new EngineSteps(_works, Fence, Now);

    /// <summary>Upper bound of a long-poll wait inside the engine (default 1 s).</summary>
    public TimeSpan LongPollCap { get; set; } = TimeSpan.FromSeconds(1);

    /// <summary>Engine time: real time plus <see cref="Advance"/>. Pass it as <c>SangamWorkerOptions.Clock</c>.</summary>
    public DateTimeOffset Now() => DateTimeOffset.UtcNow + TimeSpan.FromTicks(Interlocked.Read(ref _offsetTicks));

    /// <summary>A client on this engine without transport retries (the worker's own report retries stay visible).</summary>
    public SangamClient Client(ISangamLogger? logger = null) => new(new SangamClientOptions
    {
        BaseUrl = new Uri("http://sangam.test/"),
        HttpClient = new HttpClient(this, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan },
        Logger = logger ?? SangamLoggers.None,
        RetryPolicy = RetryPolicy.None,
    });

    /// <summary>Moves the engine clock forward; leases that end by then expire at once.</summary>
    public SangamTestEngine Advance(TimeSpan d)
    {
        Interlocked.Add(ref _offsetTicks, d.Ticks);
        Sweep();
        return this;
    }

    /// <summary>Marks a topic <c>effect=irreversible</c> (server property irreversibleTopics).</summary>
    public SangamTestEngine IrreversibleTopic(string topic)
    {
        lock (_gate) _irreversible.Add(topic);
        return this;
    }

    /// <summary>Adds AVAILABLE work; variables are a VariableMap (plain JSON or R33 TypedValues). Returns the work id.</summary>
    public string AddWork(string topic, IDictionary<string, object?>? variables = null, int? retries = null, long priority = 0)
    {
        var w = new Work
        {
            Id = "work-" + Interlocked.Increment(ref _seq),
            Topic = topic,
            OperationId = Guid.NewGuid().ToString(),
            ProcessInstanceId = "pi-" + Interlocked.Increment(ref _seq),
            ActivityInstanceId = "act-" + Interlocked.Increment(ref _seq),
            Variables = variables is null ? new JsonObject() : JsonSerializer.SerializeToNode(variables, HttpTransport.DefaultJson) ?? new JsonObject(),
            Retries = retries,
            Priority = priority,
        };
        lock (_gate)
        {
            _works[w.Id] = w;
            _order.Add(w.Id);
        }
        return w.Id;
    }

    /// <summary>
    /// Deletes the instance of a work item (VI.5): a leased item gets a CANCEL_REQUESTED tombstone, so the next heartbeat
    /// answers <c>cancelRequested: true</c>, results get 410 and cancel-ack closes it. This is the server's only source
    /// of cancelRequested.
    /// </summary>
    public SangamTestEngine DeleteInstance(string workId)
    {
        lock (_gate)
        {
            var w = Require(workId);
            if (w.State == "LOCKED" && w.LeaseVersion is not null)
            {
                w.Tombstone = "CANCEL_REQUESTED";
                w.TombstoneVersion = w.LeaseVersion;
            }
            w.State = "DELETED";
        }
        return this;
    }

    /// <summary>Same as <see cref="DeleteInstance"/>: a cooperative cancel request for leased work.</summary>
    public SangamTestEngine RequestCancel(string workId) => DeleteInstance(workId);

    /// <summary>Resolves a reconciliation item: CONFIRM_SUCCESS, RETRY (AVAILABLE, same operationId) or MARK_FAILED (VI.3.1).</summary>
    public SangamTestEngine ResolveReconciliation(string itemId, string action)
    {
        lock (_gate)
        {
            if (!_recon.TryGetValue(itemId, out var r) || r.State != "OPEN") throw new ArgumentException("no open reconciliation item " + itemId);
            _works.TryGetValue(r.WorkId, out var w);
            var unknown = w is not null && w.State == "UNKNOWN_OUTCOME";
            switch (action)
            {
                case "CONFIRM_SUCCESS": if (unknown) { w!.State = "COMPLETED"; w.OpState = "SUCCEEDED"; } break;
                case "RETRY": if (unknown) { w!.State = "AVAILABLE"; w.AvailableAt = DateTimeOffset.MinValue; w.OpState = "INTENT"; } break;
                case "MARK_FAILED": if (unknown) { w!.State = "FAILED"; w.OpState = "FAILED"; } break;
                default: throw new ArgumentException("action must be CONFIRM_SUCCESS, RETRY or MARK_FAILED");
            }
            r.State = "RESOLVED";
        }
        return this;
    }

    /// <summary>
    /// The next request whose last path segment is <paramref name="action"/> (complete, fetch-and-lock,
    /// process-instances, ...) is applied and then its response is lost: the client sees an HttpRequestException.
    /// </summary>
    public SangamTestEngine LoseNextResponse(string action)
    {
        lock (_gate) _lose[action] = _lose.TryGetValue(action, out var n) ? n + 1 : 1;
        return this;
    }

    /// <summary>Ends expired leases now (the engine also does this before every request).</summary>
    public void Sweep()
    {
        lock (_gate) SweepLocked(Now());
    }

    // ------------------------------------------------------------------ inspection

    /// <summary>AVAILABLE, LOCKED, COMPLETED, COMPLETED_WITH_ERROR, INCIDENT, UNKNOWN_OUTCOME, CANCELLED, FAILED or DELETED.</summary>
    public string State(string workId) { lock (_gate) return Require(workId).State; }
    public long? LockVersion(string workId) { lock (_gate) return Require(workId).LeaseVersion; }
    public string? LeaseOwner(string workId) { lock (_gate) return Require(workId).WorkerId; }
    public int Grants(string workId) { lock (_gate) return Require(workId).Grants; }
    public int Completions(string workId) { lock (_gate) return Require(workId).Completions; }
    public string OperationState(string workId) { lock (_gate) return Require(workId).OpState; }
    public string OperationId(string workId) { lock (_gate) return Require(workId).OperationId; }

    /// <summary>Variables of the accepted completion exactly as received, or null.</summary>
    public JsonObject? CompletedVariables(string workId)
    {
        lock (_gate) return Clone(Require(workId).CompletedVariables) as JsonObject;
    }

    public IReadOnlyList<EngineReconciliationItem> ReconciliationItems()
    {
        lock (_gate) return _recon.Values.Select(r => new EngineReconciliationItem(r.Id, r.Kind, r.WorkId, r.OperationId, r.State)).ToList();
    }

    public IReadOnlyList<EngineInstance> Instances()
    {
        lock (_gate) return _instanceOrder.Select(i => _instances[i]).ToList();
    }

    public IReadOnlyList<EngineCall> Calls() { lock (_gate) return _calls.ToList(); }

    public IReadOnlyList<EngineCall> CallsOf(string action) => Calls().Where(c => c.Action == action).ToList();

    /// <summary>Waits (real time) until the item reaches <paramref name="state"/>; throws TimeoutException otherwise.</summary>
    public async Task WaitForStateAsync(string workId, string state, TimeSpan timeout)
    {
        var end = DateTime.UtcNow + timeout;
        while (State(workId) != state)
        {
            if (DateTime.UtcNow > end) throw new TimeoutException($"work {workId} is {State(workId)}, expected {state}");
            await Task.Delay(5).ConfigureAwait(false);
        }
    }

    // ------------------------------------------------------------------ HTTP

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var path = request.RequestUri!.AbsolutePath;
        var apiPath = path.StartsWith("/api/v1", StringComparison.Ordinal) ? path["/api/v1".Length..] : path;
        var action = apiPath[(apiPath.LastIndexOf('/') + 1)..];
        var text = request.Content is null ? null : await request.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        JsonNode? body = null;
        try { body = string.IsNullOrEmpty(text) ? null : JsonNode.Parse(text); } catch (JsonException) { body = null; }
        var key = request.Headers.TryGetValues("Idempotency-Key", out var v) ? v.FirstOrDefault() : null;
        lock (_gate) _calls.Add(new EngineCall(request.Method.Method, apiPath, action, key, Clone(body)));
        Answer a;
        try
        {
            a = EngineSteps.IsStepPath(apiPath)
                ? Steps(request.Method.Method, apiPath, request.RequestUri.Query, body)
                : await DispatchAsync(request.Method.Method, apiPath, action, key, body, cancellationToken).ConfigureAwait(false);
        }
        catch (ProblemException p)
        {
            a = Problem(p, path);
        }
        bool drop;
        lock (_gate)
        {
            drop = _lose.TryGetValue(action, out var n) && n > 0;
            if (drop)
            {
                if (n == 1) _lose.Remove(action); else _lose[action] = n - 1;
            }
        }
        // the effect is applied; the answer is lost
        if (drop) throw new HttpRequestException("connection closed before the response (SangamTestEngine.LoseNextResponse)");
        var r = new HttpResponseMessage((HttpStatusCode)a.Status) { RequestMessage = request };
        if (a.Body is not null) r.Content = new StringContent(a.Body, Encoding.UTF8, a.ContentType ?? "application/json");
        if (a.Headers is not null) foreach (var h in a.Headers) r.Headers.TryAddWithoutValidation(h.Key, h.Value);
        return r;
    }

    private async Task<Answer> DispatchAsync(string method, string apiPath, string action, string? key, JsonNode? body, CancellationToken ct)
    {
        if (method == "POST" && apiPath == "/work/fetch-and-lock") return await FetchAndLockAsync(body, ct).ConfigureAwait(false);
        if (method == "POST" && apiPath.StartsWith("/work/", StringComparison.Ordinal) && apiPath.Count(c => c == '/') == 3)
        {
            var workId = Uri.UnescapeDataString(apiPath["/work/".Length..apiPath.LastIndexOf('/')]);
            lock (_gate) return WorkCall(workId, action, body as JsonObject ?? new JsonObject());
        }
        if (method == "POST" && apiPath == "/process-instances") return Idempotent(method, apiPath, key, body, () => StartInstance(body));
        if (method == "GET" && apiPath.StartsWith("/process-instances/", StringComparison.Ordinal) && apiPath.IndexOf('/', 19) < 0)
        {
            lock (_gate)
            {
                var id = Uri.UnescapeDataString(apiPath[19..]);
                if (!_instances.TryGetValue(id, out var i)) throw NotFound("process instance", id);
                return Ok(200, InstanceJson(i));
            }
        }
        throw new ProblemException(501, "CAPABILITY_NOT_INSTALLED", null, $"{method} {apiPath} is not served by the SDK test engine")
            .With("capability", apiPath).With("module", "sangam-server");
    }

    /// <summary>Step journal routes (M-025, R37), served by <see cref="EngineSteps"/> under the lock after the expiry sweep.</summary>
    private Answer Steps(string method, string apiPath, string? query, JsonNode? body)
    {
        lock (_gate)
        {
            SweepLocked(Now());
            return _journal.Handle(method, apiPath, query, body);
        }
    }

    // ------------------------------------------------------------------ R41 store (non-fenced mutations)

    private Answer Idempotent(string method, string path, string? key, JsonNode? body, Func<Answer> op)
    {
        if (key is null) return op();
        var scope = method + " " + path + " " + key;
        var fp = JsonSerializer.SerializeToElement(body);
        lock (_gate)
        {
            if (_idem.TryGetValue(scope, out var s))
            {
                if (!JsonEquals(s.Fingerprint, fp))
                    throw new ProblemException(422, "IDEMPOTENCY_KEY_REUSED", null, $"Idempotency-Key {key} was used with a different request");
                return new Answer(s.Status, s.Body, "application/json", new Dictionary<string, string> { ["Idempotent-Replayed"] = "true" });
            }
            var a = op();
            if (a.Status < 500) _idem[scope] = (fp, a.Status, a.Body);
            return a;
        }
    }

    private Answer StartInstance(JsonNode? body)
    {
        var key = Text(body, "key");
        if (string.IsNullOrWhiteSpace(key)) throw new ProblemException(400, "VALIDATION_FAILED", null, "key is required");
        var i = new EngineInstance("pi-" + Interlocked.Increment(ref _seq), key, Text(body, "businessKey"),
            Clone(body?["variables"]), Now());
        lock (_gate)
        {
            _instances[i.Id] = i;
            _instanceOrder.Add(i.Id);
        }
        return Ok(201, InstanceJson(i));
    }

    private static JsonObject InstanceJson(EngineInstance i)
    {
        var n = new JsonObject
        {
            ["id"] = i.Id,
            ["processDefinitionKey"] = i.Key,
            ["state"] = "ACTIVE",
            ["ended"] = false,
            ["startTime"] = Iso(i.StartTime),
            ["rootProcessInstanceId"] = i.Id,
        };
        if (i.BusinessKey is not null) n["businessKey"] = i.BusinessKey;
        return n;
    }

    // ------------------------------------------------------------------ fetch-and-lock

    private async Task<Answer> FetchAndLockAsync(JsonNode? r, CancellationToken ct)
    {
        var workerId = Text(r, "workerId");
        if (string.IsNullOrWhiteSpace(workerId)) throw new ProblemException(400, "VALIDATION_FAILED", null, "workerId is required");
        if (r?["topics"] is not JsonArray topics || topics.Count == 0)
            throw new ProblemException(400, "VALIDATION_FAILED", null, "at least one topic is required");
        var byName = new Dictionary<string, JsonNode?>(StringComparer.Ordinal);
        foreach (var t in topics)
        {
            var name = Text(t, "name");
            if (string.IsNullOrWhiteSpace(name)) throw new ProblemException(400, "TOPIC_NAME_REQUIRED", null, "every topic needs a name");
            byName[name] = t;
        }
        var max = Long(r, "maxTasks") is { } m ? (int)Math.Max(1, Math.Min(100, m)) : 1;
        var usePriority = r?["usePriority"]?.GetValue<bool>() ?? false;
        var waitMs = Math.Min(Math.Max(0, Long(r, "asyncResponseTimeoutMs") ?? 0), (long)LongPollCap.TotalMilliseconds);
        var end = DateTime.UtcNow.AddMilliseconds(waitMs);
        while (true)
        {
            JsonArray out_;
            lock (_gate)
            {
                SweepLocked(Now());
                out_ = Grant(workerId!, byName, max, usePriority);
            }
            var left = end - DateTime.UtcNow;
            if (out_.Count > 0 || left <= TimeSpan.Zero) return Ok(200, out_);
            try { await Task.Delay(TimeSpan.FromMilliseconds(Math.Min(20, Math.Max(1, left.TotalMilliseconds))), ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { return Ok(200, new JsonArray()); }
        }
    }

    private JsonArray Grant(string workerId, Dictionary<string, JsonNode?> topics, int max, bool usePriority)
    {
        var now = Now();
        var candidates = _order.Select(id => _works[id])
            .Where(w => topics.ContainsKey(w.Topic) && w.State == "AVAILABLE" && w.AvailableAt <= now).ToList();
        if (usePriority) candidates = candidates.OrderByDescending(w => w.Priority).ToList();
        var out_ = new JsonArray();
        foreach (var w in candidates)
        {
            if (out_.Count >= max) break;
            var t = topics[w.Topic];
            var d = LockDuration(Long(t, "lockDurationMs"));
            w.LeaseVersion = w.LeaseVersion is null ? 1 : w.LeaseVersion + 1;
            w.AttemptId = Guid.NewGuid().ToString();
            w.WorkerId = workerId;
            w.ExpiresAt = now + d;
            w.State = "LOCKED";
            w.Attempts++;
            w.Grants++;
            out_.Add(ExternalWork(w, t));
        }
        return out_;
    }

    private static JsonObject ExternalWork(Work w, JsonNode? topic)
    {
        var vars = w.Variables;
        if (topic?["variables"] is JsonArray names && vars is JsonObject all)
        {
            var filtered = new JsonObject();
            foreach (var n in names)
            {
                var name = n?.GetValue<string>();
                if (name is not null && all.TryGetPropertyValue(name, out var val)) filtered[name] = Clone(val);
            }
            vars = filtered;
        }
        var o = new JsonObject
        {
            ["id"] = w.Id,
            ["topic"] = w.Topic,
            ["processInstanceId"] = w.ProcessInstanceId,
            ["activityInstanceId"] = w.ActivityInstanceId,
            ["variables"] = Clone(vars),
            ["lockVersion"] = w.LeaseVersion,
            ["attemptId"] = w.AttemptId,
            ["operationId"] = w.OperationId,
            ["lockExpiresAt"] = Iso(w.ExpiresAt!.Value),
            ["priority"] = w.Priority,
        };
        if (w.Retries is not null) o["retries"] = w.Retries;
        if (w.ErrorMessage is not null) o["errorMessage"] = w.ErrorMessage;
        var op = new JsonObject
        {
            ["state"] = w.OpState,
            ["attempts"] = w.Attempts,
            ["capabilityClass"] = "C_NEITHER",
            ["mayDispatch"] = w.OpState == "INTENT",
        };
        if (w.StoredOutcome is not null) op["storedOutcome"] = Clone(w.StoredOutcome);
        o["operation"] = op;
        return o;
    }

    private static TimeSpan LockDuration(long? ms)
    {
        var d = ms is null or <= 0 ? DefaultLock : TimeSpan.FromMilliseconds(ms.Value);
        return d > MaxLock ? MaxLock : d;
    }

    // ------------------------------------------------------------------ expiry sweep

    private void SweepLocked(DateTimeOffset now)
    {
        foreach (var w in _works.Values)
        {
            if (w.LeaseVersion is null || w.WorkerId is null || w.ExpiresAt is null || w.ExpiresAt > now) continue;
            if (w.Tombstone == "CANCEL_REQUESTED")
            {
                // VI.5 rule 4: a deleted instance whose lease expired without a cancel ack
                w.Tombstone = "UNKNOWN_OUTCOME";
                OpenRecon("UNKNOWN_OUTCOME", w);
                EndLease(w);
            }
            else if (w.State == "LOCKED")
            {
                if (_irreversible.Contains(w.Topic))
                {
                    // VI.3 rule 5: no blind re-offer of an irreversible effect
                    w.State = "UNKNOWN_OUTCOME";
                    w.OpState = "UNKNOWN_OUTCOME";
                    OpenRecon("UNKNOWN_OUTCOME", w);
                }
                else
                {
                    w.State = "AVAILABLE";
                    w.AvailableAt = now;
                }
                EndLease(w);
            }
        }
    }

    /// <summary>LeaseTable.end: lock_version + 1, no owner.</summary>
    private static void EndLease(Work w)
    {
        w.LeaseVersion = w.LeaseVersion is null ? 1 : w.LeaseVersion + 1;
        w.AttemptId = null;
        w.WorkerId = null;
        w.ExpiresAt = null;
    }

    /// <summary>LeaseTable.remove: the lease row is gone (completion).</summary>
    private static void RemoveLease(Work w)
    {
        w.LeaseVersion = null;
        w.AttemptId = null;
        w.WorkerId = null;
        w.ExpiresAt = null;
    }

    private string OpenRecon(string kind, Work w)
    {
        var r = new Recon { Id = Interlocked.Increment(ref _seq).ToString(CultureInfo.InvariantCulture), Kind = kind, WorkId = w.Id, OperationId = w.OperationId };
        _recon[r.Id] = r;
        return r.Id;
    }

    // ------------------------------------------------------------------ worker calls (under _gate)

    private Answer WorkCall(string workId, string action, JsonObject r)
    {
        SweepLocked(Now());
        switch (action)
        {
            case "extend-lock": return Ok(200, ExtendLock(workId, r));
            case "complete": Complete(workId, r); return NoContent();
            case "fail": Fail(workId, r); return NoContent();
            case "bpmn-error": BpmnError(workId, r); return NoContent();
            case "unlock": Unlock(workId, r); return NoContent();
            case "outcome-unknown": return Ok(202, OutcomeUnknown(workId, r, Text(r, "detail")));
            case "cancel-ack": CancelAck(workId, r); return NoContent();
            default:
                throw new ProblemException(501, "CAPABILITY_NOT_INSTALLED", null, $"POST /work/{{id}}/{action} is not served by the SDK test engine");
        }
    }

    private JsonObject ExtendLock(string workId, JsonObject r)
    {
        _works.TryGetValue(workId, out var w);
        var cancel = w?.Tombstone == "CANCEL_REQUESTED";
        var lv = Long(r, "lockVersion") ?? 0;
        if (w is null || !w.Exists)
        {
            if (cancel) return LockState(workId, lv, null, true);
            throw NotFound("external work", workId);
        }
        Fence(w, Text(r, "workerId"), lv, Text(r, "attemptId"));
        w.ExpiresAt = Now() + LockDuration(Long(r, "newDurationMs"));
        return LockState(workId, lv, w.ExpiresAt, cancel);
    }

    private static JsonObject LockState(string id, long lv, DateTimeOffset? expires, bool cancel)
    {
        var n = new JsonObject { ["id"] = id, ["lockVersion"] = lv, ["cancelRequested"] = cancel };
        if (expires is { } e) n["lockExpiresAt"] = Iso(e);
        return n;
    }

    private void Complete(string workId, JsonObject r)
    {
        var lv = Long(r, "lockVersion") ?? 0;
        _works.TryGetValue(workId, out var w);
        RejectIfTombstoned(w, workId, true);
        var vars = Clone(r["variables"]) ?? new JsonObject();
        var outcome = new JsonObject
        {
            ["output"] = Clone(vars),
            ["providerRef"] = Clone(r["outcome"]?["reference"]),
            ["duplicate"] = false,
        };
        if (r["outcome"]?["details"] is { } details) outcome["details"] = Clone(details);
        if (w is not null && !w.Exists && w.OpState == "SUCCEEDED" && w.CompletedLockVersion == lv
            && JsonEquals(JsonSerializer.SerializeToElement(outcome), JsonSerializer.SerializeToElement(w.CompletedOutcome)))
            return; // the same completion again (a retried request): answered as the first one
        if (w is null || !w.Exists) throw NotFound("external work", workId);
        Fence(w, Text(r, "workerId"), lv, Text(r, "attemptId"));
        w.State = "COMPLETED";
        w.OpState = "SUCCEEDED";
        w.StoredOutcome = Clone(outcome);
        w.CompletedOutcome = outcome;
        w.CompletedLockVersion = lv;
        w.CompletedVariables = vars;
        w.Completions++;
        RemoveLease(w);
    }

    private void Fail(string workId, JsonObject r)
    {
        _works.TryGetValue(workId, out var w);
        var retries = Long(r, "retries") is { } rt ? (int)rt : (int?)null;
        if (w is not null && w.Exists && _irreversible.Contains(w.Topic) && (retries is null || retries > 0))
        {
            // VI.3 rule 5: no blind automatic retry of an irreversible effect
            OutcomeUnknown(workId, r, "failure on irreversible topic: " + Text(r, "errorMessage"));
            return;
        }
        RejectIfTombstoned(w, workId, false);
        if (w is null || !w.Exists) throw NotFound("external work", workId);
        Fence(w, Text(r, "workerId"), Long(r, "lockVersion") ?? 0, Text(r, "attemptId"));
        var left = retries is null ? Math.Max(0, (w.Retries ?? 1) - 1) : Math.Max(0, retries.Value);
        var timeout = Math.Max(0, Long(r, "retryTimeoutMs") ?? 0);
        w.Retries = left;
        w.ErrorMessage = Text(r, "errorMessage");
        if (left > 0)
        {
            w.State = "AVAILABLE";
            w.AvailableAt = Now().AddMilliseconds(timeout);
        }
        else
        {
            w.State = "INCIDENT";
            w.OpState = "FAILED";
        }
        EndLease(w);
    }

    private void BpmnError(string workId, JsonObject r)
    {
        var code = Text(r, "errorCode");
        if (string.IsNullOrWhiteSpace(code)) throw new ProblemException(400, "VALIDATION_FAILED", null, "errorCode is required");
        _works.TryGetValue(workId, out var w);
        RejectIfTombstoned(w, workId, false);
        if (w is null || !w.Exists) throw NotFound("external work", workId);
        Fence(w, Text(r, "workerId"), Long(r, "lockVersion") ?? 0, Text(r, "attemptId"));
        w.State = "COMPLETED_WITH_ERROR";
        w.OpState = "FAILED";
        w.ErrorMessage = code;
        RemoveLease(w);
    }

    private void Unlock(string workId, JsonObject r)
    {
        _works.TryGetValue(workId, out var w);
        if (w is null || !w.Exists) throw NotFound("external work", workId);
        Fence(w, Text(r, "workerId"), Long(r, "lockVersion") ?? 0, Text(r, "attemptId"));
        w.State = "AVAILABLE";
        w.AvailableAt = DateTimeOffset.MinValue;
        EndLease(w);
    }

    private JsonObject OutcomeUnknown(string workId, JsonObject r, string? detail)
    {
        _works.TryGetValue(workId, out var w);
        if (w?.Tombstone is not null) throw Gone(workId, OpenRecon("LATE_RESULT_AFTER_DELETE", w));
        if (w is null || !w.Exists) throw NotFound("external work", workId);
        Fence(w, Text(r, "workerId"), Long(r, "lockVersion") ?? 0, Text(r, "attemptId"));
        var item = OpenRecon("UNKNOWN_OUTCOME", w);
        w.State = "UNKNOWN_OUTCOME";
        w.OpState = "UNKNOWN_OUTCOME";
        w.ErrorMessage = detail;
        EndLease(w);
        return new JsonObject { ["workId"] = workId, ["operationId"] = w.OperationId, ["reconciliationItemId"] = item, ["state"] = "UNKNOWN_OUTCOME" };
    }

    private void CancelAck(string workId, JsonObject r)
    {
        _works.TryGetValue(workId, out var w);
        if (w?.Tombstone is null) throw NotFound("cancel request for work", workId);
        var lv = Long(r, "lockVersion") ?? 0;
        if (w.Tombstone != "CANCEL_REQUESTED" || w.TombstoneVersion != lv)
            throw StaleLease(workId, "STALE_VERSION", $"no CANCEL_REQUESTED tombstone with lockVersion {lv}");
        w.Tombstone = "CANCELLED";
        w.State = "CANCELLED";
        RemoveLease(w);
    }

    /// <summary>Tombstones.onLateResult: 410, and a reconciliation item when the result says an effect happened.</summary>
    private void RejectIfTombstoned(Work? w, string workId, bool effectHappened)
    {
        if (w?.Tombstone is null) return;
        throw Gone(workId, effectHappened ? OpenRecon("LATE_RESULT_AFTER_DELETE", w) : null);
    }

    /// <summary>LeaseTable.check order (NO_LEASE, STALE_VERSION, NOT_OWNER, EXPIRED), then the attempt id.</summary>
    private void Fence(Work w, string? workerId, long lockVersion, string? attemptId)
    {
        if (string.IsNullOrWhiteSpace(workerId)) throw new ProblemException(400, "WORKER_ID_REQUIRED", null, "workerId is required");
        if (w.LeaseVersion is null) throw StaleLease(w.Id, "NO_LEASE", "no lease was granted for this work");
        if (w.LeaseVersion != lockVersion) throw StaleLease(w.Id, "STALE_VERSION", $"lockVersion {lockVersion} is not the current version {w.LeaseVersion}");
        if (w.WorkerId is null || w.WorkerId != workerId) throw StaleLease(w.Id, "NOT_OWNER", $"{workerId} does not hold the lease (owner: {w.WorkerId})");
        if (w.ExpiresAt is null || w.ExpiresAt <= Now()) throw StaleLease(w.Id, "EXPIRED", $"the lease of {workerId} expired at {w.ExpiresAt}");
        if (attemptId is not null && w.AttemptId is not null && attemptId != w.AttemptId)
            throw StaleLease(w.Id, "ATTEMPT_MISMATCH", $"attemptId {attemptId} is not the current attempt");
    }

    private static ProblemException StaleLease(string workId, string reason, string detail) =>
        new ProblemException(409, "STALE_LEASE", "Stale lease", detail).With("workId", workId).With("reason", reason);

    internal static ProblemException Gone(string workId, string? item) =>
        new ProblemException(410, "GONE_INSTANCE_DELETED", null, "The instance of this work was deleted; the result was recorded")
            .With("workId", workId).With("reconciliationItemId", item);

    internal static ProblemException NotFound(string what, string id) =>
        new ProblemException(404, "NOT_FOUND", null, $"{what} {id} was not found or is not visible to you").With("resource", what);

    private Work Require(string workId) =>
        _works.TryGetValue(workId, out var w) ? w : throw new ArgumentException("unknown work " + workId);

    /// <summary>Deep copy (JsonNode.DeepClone needs .NET 8; this also compiles on the older SDKs used for checks).</summary>
    private static JsonNode? Clone(JsonNode? n) => n is null ? null : JsonNode.Parse(n.ToJsonString());

    private static Answer Ok(int status, JsonNode body) => new(status, body.ToJsonString(), "application/json");

    private static Answer NoContent() => new(204, null, null);

    private static Answer Problem(ProblemException p, string instance)
    {
        var m = new JsonObject
        {
            ["type"] = TypeBase + p.Code.ToLowerInvariant().Replace('_', '-'),
            ["title"] = p.Title ?? DefaultTitle(p.Status),
            ["status"] = p.Status,
            ["detail"] = p.Message,
            ["instance"] = instance,
            ["code"] = p.Code,
        };
        foreach (var e in p.Ext)
            if (!m.ContainsKey(e.Key)) m[e.Key] = JsonValue.Create(e.Value?.ToString());
        return new Answer(p.Status, m.ToJsonString(), "application/problem+json");
    }

    private static string DefaultTitle(int status) => status switch
    {
        400 => "Bad request",
        404 => "Not found",
        409 => "Conflict",
        410 => "Gone",
        422 => "Unprocessable content",
        501 => "Not implemented",
        _ => status >= 500 ? "Internal error" : "Error",
    };

    private static string Iso(DateTimeOffset t) => t.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fffK", CultureInfo.InvariantCulture);

    private static string? Text(JsonNode? n, string field) =>
        n is JsonObject o && o.TryGetPropertyValue(field, out var v) && v is JsonValue jv
            ? (jv.TryGetValue<string>(out var s) ? s : jv.ToJsonString())
            : null;

    private static long? Long(JsonNode? n, string field) =>
        n is JsonObject o && o.TryGetPropertyValue(field, out var v) && v is JsonValue jv
            ? (jv.TryGetValue<long>(out var l) ? l : jv.TryGetValue<int>(out var i) ? i
                : long.TryParse(jv.ToJsonString().Trim('"'), NumberStyles.Integer, CultureInfo.InvariantCulture, out var p) ? p : null)
            : null;

    /// <summary>Structural JSON equality (object members unordered; numbers compared by their text).</summary>
    internal static bool JsonEquals(JsonElement a, JsonElement b)
    {
        if (a.ValueKind != b.ValueKind) return false;
        switch (a.ValueKind)
        {
            case JsonValueKind.Object:
                var pa = a.EnumerateObject().ToDictionary(p => p.Name, p => p.Value);
                var pb = b.EnumerateObject().ToDictionary(p => p.Name, p => p.Value);
                return pa.Count == pb.Count && pa.All(kv => pb.TryGetValue(kv.Key, out var o) && JsonEquals(kv.Value, o));
            case JsonValueKind.Array:
                var la = a.EnumerateArray().ToList();
                var lb = b.EnumerateArray().ToList();
                return la.Count == lb.Count && la.Zip(lb).All(x => JsonEquals(x.First, x.Second));
            case JsonValueKind.String: return a.GetString() == b.GetString();
            case JsonValueKind.Number: return a.GetRawText() == b.GetRawText();
            default: return true;
        }
    }
}
