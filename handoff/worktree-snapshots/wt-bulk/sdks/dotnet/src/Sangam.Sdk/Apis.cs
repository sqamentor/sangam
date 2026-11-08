// SPDX-License-Identifier: Apache-2.0
// Typed API groups for /api/v1, reconciled with sangam-rest/api/sangam-api.yaml 0.1.0.
using System.Net.Http.Headers;
using System.Text.Json;
using Sangam.Sdk.Http;
using Sangam.Sdk.Models;

namespace Sangam.Sdk.Api;

using Vars = IDictionary<string, object?>;
using JsonObject = Dictionary<string, object?>;

/// <summary>Shared plumbing for the typed API groups.</summary>
public abstract class ApiBase
{
    protected HttpTransport Http { get; }
    protected ApiBase(HttpTransport http) => Http = http;

    protected static string Seg(string s) => HttpTransport.Seg(s);

    protected async Task<T?> CallAsync<T>(ApiRequest req, CancellationToken ct) =>
        (await Http.ExecuteAsync<T>(req, ct).ConfigureAwait(false)).Body;

    protected Task CallAsync(ApiRequest req, CancellationToken ct) => Http.ExecuteAsync<object>(req, ct);

    /// <summary>Reads a list endpoint: bare JSON array or object with <c>items</c>; A-16 count headers.</summary>
    protected async Task<Page<T>> ReadPageAsync<T>(ApiRequest req, CancellationToken ct)
    {
        var r = await Http.ExecuteAsync<JsonElement>(req, ct).ConfigureAwait(false);
        var body = r.Body;
        var items = new List<T>();
        JsonElement? arr = body.ValueKind == JsonValueKind.Array ? body
            : body.ValueKind == JsonValueKind.Object && body.TryGetProperty("items", out var it) ? it : null;
        if (arr is { ValueKind: JsonValueKind.Array } a)
            foreach (var e in a.EnumerateArray())
                items.Add(e.Deserialize<T>(Http.Json)!);
        long? total = long.TryParse(r.Header("X-Total-Count"), out var t) ? t : null;
        if (total is null && body.ValueKind == JsonValueKind.Object && body.TryGetProperty("totalCount", out var tc) && tc.TryGetInt64(out var tv)) total = tv;
        var capped = string.Equals(r.Header("X-Total-Count-Capped"), "true", StringComparison.OrdinalIgnoreCase)
            || (body.ValueKind == JsonValueKind.Object && body.TryGetProperty("totalCountCapped", out var cp) && cp.ValueKind == JsonValueKind.True);
        var page = body.ValueKind == JsonValueKind.Object && body.TryGetProperty("page", out var pg) && pg.TryGetInt32(out var pv) ? pv : 0;
        var size = body.ValueKind == JsonValueKind.Object && body.TryGetProperty("size", out var sz) && sz.TryGetInt32(out var sv) ? sv : items.Count;
        return new Page<T>(items, page, size, total, capped);
    }
}

/// <summary>
/// Worker API <c>/api/v1/work</c> (VI.3). Every call after fetch carries (workerId, lockVersion, attemptId); a stale
/// lockVersion raises <see cref="Errors.StaleLeaseException"/>. The endpoints are fenced; the derived
/// Idempotency-Key the worker sends is harmless and kept. Most applications use <see cref="Worker.SangamWorker"/>.
/// </summary>
public sealed class WorkApi : ApiBase
{
    public WorkApi(HttpTransport http) : base(http) { }

    /// <summary>Long-poll fetch-and-lock; HTTP timeout = async timeout + 15 s; not retried by the transport.</summary>
    public async Task<IReadOnlyList<ExternalWork>> FetchAndLockAsync(FetchAndLockRequest request, CancellationToken ct = default)
    {
        var t = TimeSpan.FromMilliseconds(request.AsyncResponseTimeoutMs) + TimeSpan.FromSeconds(15);
        var l = await CallAsync<List<ExternalWork>>(ApiRequest.Post("/work/fetch-and-lock").WithBody(request).WithTimeout(t).NoRetry(), ct).ConfigureAwait(false);
        return (IReadOnlyList<ExternalWork>?)l ?? Array.Empty<ExternalWork>();
    }

    /// <summary>Heartbeat; returns the LockState (new expiry, lockVersion, cancelRequested).</summary>
    public Task<LockState?> ExtendLockAsync(string workId, ExtendLockRequest request, string? idempotencyKey, CancellationToken ct = default) =>
        CallAsync<LockState>(Post(workId, "extend-lock", request, idempotencyKey), ct);

    public Task CompleteAsync(string workId, CompleteWorkRequest request, string? idempotencyKey, CancellationToken ct = default) =>
        CallAsync(Post(workId, "complete", request, idempotencyKey), ct);

    public Task FailAsync(string workId, FailWorkRequest request, string? idempotencyKey, CancellationToken ct = default) =>
        CallAsync(Post(workId, "fail", request, idempotencyKey), ct);

    public Task BpmnErrorAsync(string workId, BpmnErrorRequest request, string? idempotencyKey, CancellationToken ct = default) =>
        CallAsync(Post(workId, "bpmn-error", request, idempotencyKey), ct);

    public Task UnlockAsync(string workId, UnlockRequest request, string? idempotencyKey, CancellationToken ct = default) =>
        CallAsync(Post(workId, "unlock", request, idempotencyKey), ct);

    /// <summary>VI.3.2 rule 4: 202 with the opened reconciliation item.</summary>
    public Task<OutcomeUnknownResult?> OutcomeUnknownAsync(string workId, OutcomeUnknownRequest request, string? idempotencyKey, CancellationToken ct = default) =>
        CallAsync<OutcomeUnknownResult>(Post(workId, "outcome-unknown", request, idempotencyKey), ct);

    /// <summary>VI.3.1 / VI.5: CANCEL_REQUESTED --worker ack--&gt; CANCELLED.</summary>
    public Task CancelAckAsync(string workId, UnlockRequest request, string? idempotencyKey, CancellationToken ct = default) =>
        CallAsync(Post(workId, "cancel-ack", request, idempotencyKey), ct);

    private static ApiRequest Post(string workId, string action, object body, string? key) =>
        ApiRequest.Post("/work/" + Seg(workId) + "/" + action).WithBody(body).WithIdempotencyKey(key);
}

/// <summary>Filters for <c>GET /tasks</c>; results are always filtered by the caller's VI.4 policy.</summary>
public sealed class TaskQuery
{
    public string? Assignee { get; set; }
    /// <summary>Tasks assigned to the caller.</summary>
    public bool? Mine { get; set; }
    public bool? Unassigned { get; set; }
    public string? CandidateGroup { get; set; }
    public string? SeatId { get; set; }
    public string? ProcessInstanceId { get; set; }
    public string? CaseInstanceId { get; set; }
    public string? BusinessKey { get; set; }
    public string? ProcessDefinitionKey { get; set; }
    public string? TaskDefinitionKey { get; set; }
    public string? State { get; set; }
    public string? SortBy { get; set; }
    /// <summary><c>asc</c> or <c>desc</c>.</summary>
    public string? SortOrder { get; set; }
    public int? Page { get; set; }
    public int? Size { get; set; }
    /// <summary>Any other server-side filter.</summary>
    public Dictionary<string, object?> Extra { get; } = new();

    internal IEnumerable<KeyValuePair<string, object?>> ToQuery()
    {
        yield return new("assignee", Assignee);
        yield return new("mine", Mine);
        yield return new("unassigned", Unassigned);
        yield return new("candidateGroup", CandidateGroup);
        yield return new("seatId", SeatId);
        yield return new("processInstanceId", ProcessInstanceId);
        yield return new("caseInstanceId", CaseInstanceId);
        yield return new("businessKey", BusinessKey);
        yield return new("processDefinitionKey", ProcessDefinitionKey);
        yield return new("taskDefinitionKey", TaskDefinitionKey);
        yield return new("state", State);
        yield return new("sortBy", SortBy);
        yield return new("sortOrder", SortOrder);
        yield return new("page", Page);
        yield return new("size", Size);
        foreach (var kv in Extra) yield return kv;
    }
}

/// <summary>User tasks <c>/api/v1/tasks</c>.</summary>
public sealed class TasksApi : ApiBase
{
    public TasksApi(HttpTransport http) : base(http) { }

    public Task<Page<TaskView>> ListAsync(TaskQuery? query = null, CancellationToken ct = default) =>
        ReadPageAsync<TaskView>(ApiRequest.Get("/tasks").WithQuery(query?.ToQuery()), ct);

    public Task<TaskView?> GetAsync(string taskId, CancellationToken ct = default) =>
        CallAsync<TaskView>(ApiRequest.Get("/tasks/" + Seg(taskId)), ct);

    /// <summary><c>GET /tasks/{id}/variables</c> (VariableMap).</summary>
    public Task<JsonObject?> VariablesAsync(string taskId, CancellationToken ct = default) =>
        CallAsync<JsonObject>(ApiRequest.Get("/tasks/" + Seg(taskId) + "/variables"), ct);

    /// <summary>Claim for the caller (no body, 204). The seat context comes from <c>X-Sangam-Seat</c>.</summary>
    public Task ClaimAsync(string taskId, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync(ApiRequest.Post("/tasks/" + Seg(taskId) + "/claim").WithIdempotencyKey(idempotencyKey), ct);

    public Task UnclaimAsync(string taskId, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync(ApiRequest.Post("/tasks/" + Seg(taskId) + "/unclaim").WithIdempotencyKey(idempotencyKey), ct);

    /// <summary>Completes the task; <c>PayloadHash</c> must equal what the user approved (VI.4 rule 5).</summary>
    public Task<CompleteTaskResult?> CompleteAsync(string taskId, CompleteTaskRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<CompleteTaskResult>(ApiRequest.Post("/tasks/" + Seg(taskId) + "/complete").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);

    /// <summary>Same as <see cref="CompleteAsync"/> but exposes headers (for example <c>Idempotent-Replayed</c>).</summary>
    public Task<ApiResponse<CompleteTaskResult>> CompleteWithResponseAsync(string taskId, CompleteTaskRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        Http.ExecuteAsync<CompleteTaskResult>(ApiRequest.Post("/tasks/" + Seg(taskId) + "/complete").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);

    public Task ReturnAsync(string taskId, ReturnTaskRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync(ApiRequest.Post("/tasks/" + Seg(taskId) + "/return").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);

    public Task DelegateAsync(string taskId, DelegateTaskRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync(ApiRequest.Post("/tasks/" + Seg(taskId) + "/delegate").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);
}

/// <summary>Process and case instances, messages and signals.</summary>
public sealed class ProcessesApi : ApiBase
{
    public ProcessesApi(HttpTransport http) : base(http) { }

    /// <summary><c>POST /process-instances</c> (201). Pass a business-derived Idempotency-Key to make "start once" safe.</summary>
    public Task<ProcessInstance?> StartAsync(StartProcessRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<ProcessInstance>(StartRequest(request, idempotencyKey), ct);

    /// <summary>Same as <see cref="StartAsync"/> but exposes headers (<c>Idempotent-Replayed</c>, <c>Location</c>).</summary>
    public Task<ApiResponse<ProcessInstance>> StartWithResponseAsync(StartProcessRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        Http.ExecuteAsync<ProcessInstance>(StartRequest(request, idempotencyKey), ct);

    private static ApiRequest StartRequest(StartProcessRequest r, string? key) =>
        ApiRequest.Post("/process-instances").WithBody(r).WithIdempotencyKey(key);

    public Task<Page<ProcessInstance>> ListAsync(ProcessInstanceQuery? query = null, CancellationToken ct = default) =>
        ReadPageAsync<ProcessInstance>(ApiRequest.Get("/process-instances").WithQuery(query?.ToQuery()), ct);

    public Task<ProcessInstance?> GetAsync(string processInstanceId, CancellationToken ct = default) =>
        CallAsync<ProcessInstance>(ApiRequest.Get("/process-instances/" + Seg(processInstanceId)), ct);

    /// <summary><c>GET /process-instances/{id}/variables</c> (VariableMap).</summary>
    public Task<JsonObject?> VariablesAsync(string processInstanceId, CancellationToken ct = default) =>
        CallAsync<JsonObject>(ApiRequest.Get("/process-instances/" + Seg(processInstanceId) + "/variables"), ct);

    /// <summary>Business cancel (VI.5): needs CANCEL_INSTANCE and a reason; returns open operation ids and tombstones.</summary>
    public Task<TerminationResult?> CancelAsync(string processInstanceId, TerminateRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<TerminationResult>(ApiRequest.Post("/process-instances/" + Seg(processInstanceId) + "/cancel").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);

    public Task<MessageResult?> CorrelateMessageAsync(MessageRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<MessageResult>(ApiRequest.Post("/messages").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);

    /// <summary><c>POST /signals</c> (204).</summary>
    public Task SignalAsync(SignalRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync(ApiRequest.Post("/signals").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);

    /// <summary><c>POST /case-instances</c> (CMMN 1.1).</summary>
    public Task<CaseInstance?> StartCaseAsync(StartCaseRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<CaseInstance>(ApiRequest.Post("/case-instances").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);

    public Task<CaseInstance?> GetCaseAsync(string caseInstanceId, CancellationToken ct = default) =>
        CallAsync<CaseInstance>(ApiRequest.Get("/case-instances/" + Seg(caseInstanceId)), ct);
}

/// <summary>DMN decisions; evaluation is side-effect free (no Idempotency-Key needed).</summary>
public sealed class DecisionsApi : ApiBase
{
    public DecisionsApi(HttpTransport http) : base(http) { }

    public Task<DecisionEvaluateResult?> EvaluateAsync(string decisionKey, DecisionEvaluateRequest request, CancellationToken ct = default) =>
        CallAsync<DecisionEvaluateResult>(ApiRequest.Post("/decisions/" + Seg(decisionKey) + "/evaluate").WithBody(request), ct);
}

/// <summary>Audit timeline and hash-chain verification (R42, AC-01).</summary>
public sealed class AuditApi : ApiBase
{
    public AuditApi(HttpTransport http) : base(http) { }

    /// <summary><c>GET /audit/{businessKey}/timeline</c>: paged audit entries.</summary>
    public Task<Page<AuditEntry>> TimelineAsync(string businessKey, bool? includeHistory = null, int? page = null, int? size = null, CancellationToken ct = default) =>
        ReadPageAsync<AuditEntry>(ApiRequest.Get("/audit/" + Seg(businessKey) + "/timeline")
            .WithQuery("includeHistory", includeHistory).WithQuery("page", page).WithQuery("size", size), ct);

    public Task<AuditVerifyResult?> VerifyAsync(string? chainId = null, string? businessKey = null, CancellationToken ct = default) =>
        CallAsync<AuditVerifyResult>(ApiRequest.Get("/audit/verify").WithQuery("chainId", chainId).WithQuery("businessKey", businessKey), ct);
}

/// <summary>Generic CRUD over one admin/ops collection; bodies are JSON objects.</summary>
public sealed class ResourceApi : ApiBase
{
    public string Path { get; }
    internal ResourceApi(HttpTransport http, string path) : base(http) => Path = path;

    public Task<Page<JsonObject>> ListAsync(IEnumerable<KeyValuePair<string, object?>>? filters = null, int? page = null, int? size = null, CancellationToken ct = default) =>
        ReadPageAsync<JsonObject>(ApiRequest.Get(Path).WithQuery(filters).WithQuery("page", page).WithQuery("size", size), ct);

    public Task<JsonObject?> GetAsync(string id, CancellationToken ct = default) =>
        CallAsync<JsonObject>(ApiRequest.Get(Path + "/" + Seg(id)), ct);

    public Task<JsonObject?> CreateAsync(object body, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<JsonObject>(ApiRequest.Post(Path).WithBody(body).WithIdempotencyKey(idempotencyKey), ct);

    public Task<JsonObject?> UpdateAsync(string id, object body, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<JsonObject>(ApiRequest.Put(Path + "/" + Seg(id)).WithBody(body).WithIdempotencyKey(idempotencyKey), ct);

    public Task DeleteAsync(string id, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync(ApiRequest.Delete(Path + "/" + Seg(id)).WithIdempotencyKey(idempotencyKey), ct);

    /// <summary>
    /// <c>POST {path}/{id}/{action}</c>, for example <c>/ops/incidents/{id}/resolve</c>, <c>/ops/outbox/{id}/skip</c>,
    /// <c>/ops/jobs/{id}/execute</c>, <c>/ops/jobs/{id}/suspension</c>, <c>/admin/assignments/{id}/end</c>,
    /// <c>/admin/delegations/{id}/revoke</c>.
    /// </summary>
    public Task<JsonObject?> ActionAsync(string id, string action, object? body = null, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<JsonObject>(ApiRequest.Post(Path + "/" + Seg(id) + "/" + Seg(action)).WithBody(body).WithIdempotencyKey(idempotencyKey), ct);

    /// <summary><c>GET {path}/{sub}</c>, for example <c>/ops/outbox/stats</c>.</summary>
    public Task<JsonObject?> GetSubAsync(string sub, CancellationToken ct = default) =>
        CallAsync<JsonObject>(ApiRequest.Get(Path + "/" + Seg(sub)), ct);
}

/// <summary>Admin basics <c>/api/v1/admin/**</c>.</summary>
public sealed class AdminApi
{
    private readonly HttpTransport _http;
    public AdminApi(HttpTransport http) => _http = http;

    public ResourceApi OrgUnits => new(_http, "/admin/org-units");
    public ResourceApi Seats => new(_http, "/admin/seats");
    /// <summary>End an assignment with <c>ActionAsync(id, "end", body)</c>.</summary>
    public ResourceApi Assignments => new(_http, "/admin/assignments");
    /// <summary>Revoke a delegation with <c>ActionAsync(id, "revoke", body)</c>.</summary>
    public ResourceApi Delegations => new(_http, "/admin/delegations");
    public ResourceApi Policies => new(_http, "/admin/policies");
    public ResourceApi Tenants => new(_http, "/admin/tenants");
    public ResourceApi Resource(string name) => new(_http, "/admin/" + HttpTransport.Seg(name));
}

/// <summary>Operations: incidents, reconciliation workbench (VI.3 UNKNOWN_OUTCOME), outbox, jobs.</summary>
public sealed class OpsApi : ApiBase
{
    public OpsApi(HttpTransport http) : base(http) { }

    public ResourceApi Incidents => new(Http, "/ops/incidents");
    public ResourceApi Outbox => new(Http, "/ops/outbox");
    public ResourceApi Jobs => new(Http, "/ops/jobs");

    /// <summary><c>POST /ops/incidents/{id}/retry</c> (reason mandatory).</summary>
    public Task<JsonObject?> RetryIncidentAsync(string incidentId, IncidentAction request, string? idempotencyKey = null, CancellationToken ct = default) =>
        Incidents.ActionAsync(incidentId, "retry", request, idempotencyKey, ct);

    /// <summary><c>POST /ops/jobs/{id}/retries</c>.</summary>
    public Task<JsonObject?> SetJobRetriesAsync(string jobId, JobRetriesRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        Jobs.ActionAsync(jobId, "retries", request, idempotencyKey, ct);

    /// <summary><c>POST /ops/outbox/{outboxId}/requeue</c> (audited OUTBOX_REQUEUED).</summary>
    public Task<JsonObject?> RequeueOutboxAsync(string outboxId, OutboxRepairRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        Outbox.ActionAsync(outboxId, "requeue", request, idempotencyKey, ct);

    /// <summary><c>GET /ops/outbox/stats</c>.</summary>
    public Task<JsonObject?> OutboxStatsAsync(CancellationToken ct = default) => Outbox.GetSubAsync("stats", ct);

    /// <summary>List reconciliation items; <c>state</c> = OPEN | RESOLVED, <c>kind</c> = UNKNOWN_OUTCOME | LATE_RESULT_AFTER_DELETE.</summary>
    public Task<Page<ReconciliationItem>> ReconciliationAsync(string? state = null, string? kind = null, int? page = null, int? size = null, CancellationToken ct = default) =>
        ReadPageAsync<ReconciliationItem>(ApiRequest.Get("/ops/reconciliation").WithQuery("state", state).WithQuery("kind", kind)
            .WithQuery("page", page).WithQuery("size", size), ct);

    public Task<ReconciliationItem?> ReconciliationItemAsync(string id, CancellationToken ct = default) =>
        CallAsync<ReconciliationItem>(ApiRequest.Get("/ops/reconciliation/" + Seg(id)), ct);

    /// <summary>Resolve with CONFIRM_SUCCESS, RETRY_SAME_OPERATION or MARK_FAILED, plus mandatory evidence.</summary>
    public Task<ReconciliationItem?> ResolveAsync(string id, ReconciliationResolveRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<ReconciliationItem>(ApiRequest.Post("/ops/reconciliation/" + Seg(id) + "/resolve").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);
}

/// <summary>Deployments, event registry and forms.</summary>
public sealed class RepositoryApi : ApiBase
{
    public RepositoryApi(HttpTransport http) : base(http) { }

    /// <summary>
    /// <c>POST /deployments</c> (201) as multipart: fields <c>name</c>, <c>source</c>, <c>duplicateFiltering</c> and one
    /// part named <c>files</c> per resource (file name = dictionary key, for example <c>bill.bpmn</c>).
    /// </summary>
    public Task<Deployment?> DeployAsync(string name, IReadOnlyDictionary<string, byte[]> resources, string? source = null,
        bool? duplicateFiltering = null, string? idempotencyKey = null, CancellationToken ct = default)
    {
        var form = new MultipartFormDataContent("----sangam" + Guid.NewGuid().ToString("N"));
        form.Add(new StringContent(name), "name");
        if (source is not null) form.Add(new StringContent(source), "source");
        if (duplicateFiltering is { } df) form.Add(new StringContent(df ? "true" : "false"), "duplicateFiltering");
        foreach (var (fileName, bytes) in resources)
        {
            var part = new ByteArrayContent(bytes);
            part.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            form.Add(part, "files", fileName);
        }
        return CallAsync<Deployment>(ApiRequest.Post("/deployments").WithContent(form).WithIdempotencyKey(idempotencyKey), ct);
    }

    public Task<Page<Deployment>> DeploymentsAsync(int? page = null, int? size = null, CancellationToken ct = default) =>
        ReadPageAsync<Deployment>(ApiRequest.Get("/deployments").WithQuery("page", page).WithQuery("size", size), ct);

    public async Task<IReadOnlyList<JsonObject>> EventChannelsAsync(CancellationToken ct = default) =>
        (await ReadPageAsync<JsonObject>(ApiRequest.Get("/events/channels"), ct).ConfigureAwait(false)).Items;

    public async Task<IReadOnlyList<JsonObject>> EventDefinitionsAsync(CancellationToken ct = default) =>
        (await ReadPageAsync<JsonObject>(ApiRequest.Get("/events/definitions"), ct).ConfigureAwait(false)).Items;

    /// <summary><c>POST /events/{channel}</c> with a CloudEvent (202). Status ACCEPTED, DUPLICATE or BUFFERED.</summary>
    public Task<PublishResult?> PublishEventAsync(string channel, CloudEvent @event, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<PublishResult>(ApiRequest.Post("/events/" + Seg(channel)).WithBody(@event).WithIdempotencyKey(idempotencyKey ?? @event.Id), ct);

    /// <summary><c>GET /forms/{key}</c>.</summary>
    public Task<JsonObject?> FormAsync(string formKey, CancellationToken ct = default) =>
        CallAsync<JsonObject>(ApiRequest.Get("/forms/" + Seg(formKey)), ct);
}
