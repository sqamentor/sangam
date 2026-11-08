// SPDX-License-Identifier: Apache-2.0
// Wire models of /api/v1, reconciled with sangam-rest/api/sangam-api.yaml 0.1.0. JSON names are camelCase; null
// members are omitted on write. Variables are a VariableMap: plain JSON values or R33 TypedValue objects; on read,
// values deserialize as System.Text.Json.JsonElement (use TaskContext.Variable<T> or TypedValue.Unwrap<T>).
using System.Globalization;
using System.Numerics;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Sangam.Sdk.Models;

using Vars = IDictionary<string, object?>;
using VarMap = Dictionary<string, object?>;

/// <summary>
/// R33 typed value <c>{"type": ..., "value": ...}</c>. Money is never a binary float: Decimal, BigInteger, Long,
/// Date, LocalDate and Bytes (base64) travel as strings.
/// </summary>
public sealed record TypedValue(string Type, object? Value)
{
    public static readonly IReadOnlySet<string> Types = new HashSet<string>(StringComparer.Ordinal)
        { "Decimal", "BigInteger", "Long", "Integer", "Boolean", "String", "Date", "LocalDate", "Json", "Bytes" };

    public static TypedValue Decimal(decimal v) => new("Decimal", v.ToString(CultureInfo.InvariantCulture));
    public static TypedValue BigInteger(BigInteger v) => new("BigInteger", v.ToString(CultureInfo.InvariantCulture));
    public static TypedValue Long(long v) => new("Long", v.ToString(CultureInfo.InvariantCulture));
    public static TypedValue Integer(int v) => new("Integer", v);
    public static TypedValue Boolean(bool v) => new("Boolean", v);
    public static TypedValue String(string v) => new("String", v);
    /// <summary>Zoned instant, ISO-8601 UTC.</summary>
    public static TypedValue Date(DateTimeOffset v) => new("Date", v.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fffK", CultureInfo.InvariantCulture));
    /// <summary>Unzoned calendar date <c>yyyy-MM-dd</c>.</summary>
    public static TypedValue LocalDate(DateOnly v) => new("LocalDate", v.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
    public static TypedValue Json(object? v) => new("Json", v);
    public static TypedValue Bytes(byte[] v) => new("Bytes", Convert.ToBase64String(v));

    /// <summary>True when <paramref name="e"/> has the TypedValue shape with a known type.</summary>
    public static bool IsTyped(JsonElement e) =>
        e.ValueKind == JsonValueKind.Object
        && e.TryGetProperty("type", out var t) && t.ValueKind == JsonValueKind.String && Types.Contains(t.GetString()!)
        && e.TryGetProperty("value", out _);

    /// <summary>Converts a VariableMap value (plain JSON or TypedValue) to <typeparamref name="T"/>.</summary>
    public static T? Unwrap<T>(JsonElement e, JsonSerializerOptions json)
    {
        var target = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T);
        if (IsTyped(e))
        {
            var type = e.GetProperty("type").GetString();
            var v = e.GetProperty("value");
            if (v.ValueKind == JsonValueKind.Null) return default;
            if (target == typeof(TypedValue)) return (T)(object)new TypedValue(type!, v.Clone());
            if (target == typeof(BigInteger))
                return (T)(object)System.Numerics.BigInteger.Parse(v.ValueKind == JsonValueKind.String ? v.GetString()! : v.GetRawText(), CultureInfo.InvariantCulture);
            if (target == typeof(string) && v.ValueKind != JsonValueKind.String && type != "Json") return (T)(object)v.GetRawText();
            e = v;
        }
        if (e.ValueKind == JsonValueKind.Null || e.ValueKind == JsonValueKind.Undefined) return default;
        if (target == typeof(BigInteger))
            return (T)(object)System.Numerics.BigInteger.Parse(e.ValueKind == JsonValueKind.String ? e.GetString()! : e.GetRawText(), CultureInfo.InvariantCulture);
        return e.Deserialize<T>(json);
    }
}

/// <summary>
/// A locked unit of external work (VI.3). The handler runs only after this LOCKED confirmation with its
/// <c>lockVersion</c> (A-1). <c>OperationId</c> is stable across retries and lease expiry (R36) and is the
/// idempotency key for downstream calls; <c>AttemptId</c> changes per lease (A-2).
/// </summary>
public sealed record ExternalWork(
    string Id, string Topic, string? TenantId, string? ProcessInstanceId, string? ProcessDefinitionKey,
    string? ActivityId, string? ActivityInstanceId, string? BusinessKey, VarMap? Variables,
    long LockVersion, string? AttemptId, string? OperationId, DateTimeOffset? LockExpiresAt, int? Retries,
    long? Priority, string? ErrorMessage)
{
    /// <summary>Not in contract 0.1.0; read if a server sends it. Irreversibility comes from <c>TopicSubscription.Irreversible</c>.</summary>
    public string? Effect { get; init; }

    /// <summary>SL-16: server-recorded ledger entry of <c>OperationId</c>; null from older servers.</summary>
    public WorkOperation? Operation { get; init; }
}

/// <summary>
/// Operation-ledger entry of a leased work item (SL-16, R37). <c>MayDispatch</c> false with state SUCCEEDED: complete
/// from <c>StoredOutcome</c> ({output, providerRef, duplicate}) without calling the downstream again.
/// </summary>
public sealed record WorkOperation(string State, int Attempts, string? CapabilityClass, bool MayDispatch, Vars? StoredOutcome);

/// <summary>Downstream result recorded as the ledger outcome (<c>Reference</c> = providerRef).</summary>
public sealed record WorkOutcome(string? Reference, Vars? Details = null);

/// <summary>TopicRequest: one topic in fetch-and-lock (lease default 5 min, max 24 h).</summary>
public sealed record FetchTopic(string Name, long LockDurationMs, IReadOnlyList<string>? Variables = null,
    bool? LocalVariables = null, string? ProcessDefinitionKey = null, string? BusinessKey = null);

/// <summary>Body of <c>POST /work/fetch-and-lock</c> (long poll up to <c>AsyncResponseTimeoutMs</c>).</summary>
public sealed record FetchAndLockRequest(string WorkerId, IReadOnlyList<FetchTopic> Topics, int MaxTasks, long AsyncResponseTimeoutMs, bool? UsePriority = null);

/// <summary>Heartbeat body (VI.3.2 rule 3).</summary>
public sealed record ExtendLockRequest(string WorkerId, long LockVersion, string? AttemptId, long NewDurationMs);

/// <summary>LockState: heartbeat answer; <c>cancelRequested</c> tells the worker to stop and acknowledge (VI.3).</summary>
public sealed record LockState(string? Id, long? LockVersion, DateTimeOffset? LockExpiresAt, bool CancelRequested);

public sealed record CompleteWorkRequest(string WorkerId, long LockVersion, string? AttemptId, Vars? Variables, Vars? LocalVariables = null, WorkOutcome? Outcome = null);

/// <summary><c>Retries</c> left after this failure; 0 raises an incident.</summary>
public sealed record FailWorkRequest(string WorkerId, long LockVersion, string? AttemptId, string ErrorMessage, string? ErrorDetails, int Retries, long RetryTimeoutMs);

public sealed record BpmnErrorRequest(string WorkerId, long LockVersion, string? AttemptId, string ErrorCode, string? ErrorMessage, Vars? Variables);

/// <summary>Body of <c>/unlock</c> (explicit release) and <c>/cancel-ack</c>.</summary>
public sealed record UnlockRequest(string WorkerId, long LockVersion, string? AttemptId);

/// <summary>VI.3.2 rule 4: the downstream effect may or may not have happened.</summary>
public sealed record OutcomeUnknownRequest(string WorkerId, long LockVersion, string? AttemptId, string? Detail, Vars? Evidence);

/// <summary>202 answer: the work moved to UNKNOWN_OUTCOME and a reconciliation item was opened.</summary>
public sealed record OutcomeUnknownResult(string? WorkId, string? OperationId, string? ReconciliationItemId, string? State);

/// <summary>
/// One page of a list: <c>X-Total-Count</c>, and <c>X-Total-Count-Capped: true</c> when capped (A-16), in which case
/// <c>TotalCount</c> is a lower bound.
/// </summary>
public sealed record Page<T>(IReadOnlyList<T> Items, int PageNumber, int Size, long? TotalCount, bool TotalCountCapped);

/// <summary>A user task seen through the VI.4 policy. Variables: <c>GET /tasks/{id}/variables</c>.</summary>
public sealed record TaskView(
    string Id, string? Name, string? Description, string? TaskDefinitionKey, string? ProcessInstanceId,
    string? ProcessDefinitionKey, string? CaseInstanceId, string? BusinessKey, string? Assignee, string? Owner,
    string? SeatId, string? TenantId, DateTimeOffset? Created, DateTimeOffset? Due, DateTimeOffset? FollowUp,
    DateTimeOffset? LastUpdated, DateTimeOffset? ClaimTime, string? State, int? Priority, string? FormKey,
    string? DelegationState);

/// <summary><c>POST /tasks/{id}/complete</c>: <c>PayloadHash</c> (required) binds the approval to the payload (VI.4 rule 5).</summary>
public sealed record CompleteTaskRequest(string PayloadHash, Vars? Variables = null, string? Decision = null,
    string? Remarks = null, long? CaseVersion = null, string? FormVersion = null);

public sealed record CompleteTaskResult(string? TaskId, string? ProcessInstanceId, string? Decision, string? PayloadHash,
    bool? InstanceEnded, IReadOnlyList<string>? NextTaskIds);

public sealed record ReturnTaskRequest(string Reason, string? ReasonCode = null, string? TargetActivityId = null, Vars? Variables = null);
public sealed record DelegateTaskRequest(string Reason, string? ToUserId = null, string? ToSeatId = null);

/// <summary><c>POST /process-instances</c>; the tenant comes from the token.</summary>
public sealed record StartProcessRequest(string Key, string? BusinessKey = null, Vars? Variables = null, int? Version = null,
    string? PackageHash = null, string? FormVersion = null, IReadOnlyList<string>? StartBeforeActivityIds = null);

public sealed record ProcessInstance(
    string Id, string? ProcessDefinitionId, string? ProcessDefinitionKey, int? ProcessDefinitionVersion,
    string? BusinessKey, string? TenantId, string? State, bool? Ended, DateTimeOffset? StartTime, DateTimeOffset? EndTime,
    IReadOnlyList<string>? ActiveActivityIds, long? CaseVersion, string? StartedBy, string? BusinessStatus,
    string? SuperProcessInstanceId, string? RootProcessInstanceId);

/// <summary>Filters for <c>GET /process-instances</c>.</summary>
public sealed class ProcessInstanceQuery
{
    public string? Key { get; set; }
    public string? BusinessKey { get; set; }
    public string? State { get; set; }
    public DateTimeOffset? StartedAfter { get; set; }
    public DateTimeOffset? StartedBefore { get; set; }
    public bool? WithIncidents { get; set; }
    public string? SuperProcessInstanceId { get; set; }
    public int? Page { get; set; }
    public int? Size { get; set; }

    internal IEnumerable<KeyValuePair<string, object?>> ToQuery()
    {
        yield return new("key", Key);
        yield return new("businessKey", BusinessKey);
        yield return new("state", State);
        yield return new("startedAfter", StartedAfter?.ToUniversalTime().ToString("O", CultureInfo.InvariantCulture));
        yield return new("startedBefore", StartedBefore?.ToUniversalTime().ToString("O", CultureInfo.InvariantCulture));
        yield return new("withIncidents", WithIncidents);
        yield return new("superProcessInstanceId", SuperProcessInstanceId);
        yield return new("page", Page);
        yield return new("size", Size);
    }
}

/// <summary>Business cancel with a mandatory reason (VI.5, R23).</summary>
public sealed record TerminateRequest(string Reason, string? ReasonCode = null);

public sealed record TerminationResult(string? ProcessInstanceId, string? State, IReadOnlyList<string>? OpenOperationIds,
    int? CancelRequestedWork, int? Tombstones);

/// <summary><c>POST /messages</c>.</summary>
public sealed record MessageRequest(string MessageName, string? BusinessKey = null, string? ProcessInstanceId = null,
    Vars? CorrelationKeys = null, Vars? Variables = null, bool? All = null);

public sealed record MessageResult(string? MessageName, IReadOnlyList<string>? ProcessInstanceIds, IReadOnlyList<string>? ExecutionIds);

/// <summary><c>POST /signals</c> (204).</summary>
public sealed record SignalRequest(string Name, string? ExecutionId = null, Vars? Variables = null);

public sealed record StartCaseRequest(string Key, string? BusinessKey = null, Vars? Variables = null);

public sealed record CaseInstance(string Id, string? CaseDefinitionId, string? CaseDefinitionKey, string? BusinessKey,
    string? TenantId, string? State, DateTimeOffset? Created, JsonElement? Executions, string? ReactivatedFrom);

/// <summary><c>POST /decisions/{key}/evaluate</c>; <c>Purpose</c> = ROUTING | AUTHORITY | OTHER.</summary>
public sealed record DecisionEvaluateRequest(Vars? Variables, int? Version = null, string? Purpose = null, string? BusinessKey = null);

/// <summary>Decision output rows (VariableMaps), evaluated version, output digest and audit event (R33).</summary>
public sealed record DecisionEvaluateResult(string? DecisionDefinitionId, string? DecisionKey, int? Version,
    IReadOnlyList<VarMap>? Result, string? OutputDigest, string? AuditEventId);

/// <summary>One audit-chain entry (R42, AC-01).</summary>
public sealed record AuditEntry(string? EventId, string? EventClass, string? ChainId, long? Seq, string? Type,
    DateTimeOffset? OccurredAt, string? Actor, string? SeatId, string? ActedForSeatId, string? ProcessInstanceId,
    string? ActivityId, string? TaskId, JsonElement? Payload, bool? Erased, string? Hash, JsonElement? Evidence);

public sealed record AuditVerifyResult(string? TenantId, bool Ok, DateTimeOffset? VerifiedAt, IReadOnlyList<JsonElement>? Chains);

/// <summary>An UNKNOWN_OUTCOME / LATE_RESULT_AFTER_DELETE reconciliation item (VI.3, VI.5).</summary>
public sealed record ReconciliationItem(string Id, string? Kind, string? OperationId, string? RootId, string? TaskId,
    string? State, JsonElement? Details, string? Action, string? ResolvedBy, JsonElement? Resolution,
    DateTimeOffset? CreatedAt, DateTimeOffset? ResolvedAt);

/// <summary>Resolution with evidence: CONFIRM_SUCCESS (with result), RETRY_SAME_OPERATION or MARK_FAILED.</summary>
public sealed record ReconciliationResolveRequest(string Action, string Evidence, Vars? Result = null, string? Reason = null);

/// <summary>Reconciliation actions and kinds.</summary>
public static class Reconciliation
{
    public const string ConfirmSuccess = "CONFIRM_SUCCESS";
    public const string RetrySameOperation = "RETRY_SAME_OPERATION";
    public const string MarkFailed = "MARK_FAILED";
    public const string KindUnknownOutcome = "UNKNOWN_OUTCOME";
    public const string KindLateResultAfterDelete = "LATE_RESULT_AFTER_DELETE";
}

public sealed record Deployment(string Id, string? Name, string? Source, string? TenantId, DateTimeOffset? DeploymentTime,
    JsonElement? Resources, JsonElement? Definitions);

/// <summary>CloudEvents 1.0 envelope for <c>POST /events/{channel}</c> (required: id, source, type, specversion).</summary>
public sealed record CloudEvent
{
    public required string Id { get; init; }
    public required string Source { get; init; }
    public required string Type { get; init; }
    [JsonPropertyName("specversion")] public string SpecVersion { get; init; } = "1.0";
    public string? Subject { get; init; }
    public DateTimeOffset? Time { get; init; }
    [JsonPropertyName("datacontenttype")] public string? DataContentType { get; init; }
    [JsonPropertyName("dataschema")] public string? DataSchema { get; init; }
    public object? Data { get; init; }
    public IDictionary<string, object?>? Extensions { get; init; }

    /// <summary>A JSON event with a fresh id and the current time.</summary>
    public static CloudEvent Create(string source, string type, object? data, string? subject = null, string? id = null) => new()
    {
        Id = id ?? Guid.NewGuid().ToString(),
        Source = source,
        Type = type,
        Subject = subject,
        Time = DateTimeOffset.UtcNow,
        DataContentType = "application/json",
        Data = data,
    };
}

/// <summary>Status = ACCEPTED | DUPLICATE | BUFFERED.</summary>
public sealed record PublishResult(string? EventId, string? Status, string? EventDefinitionKey,
    IReadOnlyList<string>? StartedInstanceIds, IReadOnlyList<string>? CorrelatedInstanceIds);

/// <summary>Body of incident retry/resolve (reason mandatory).</summary>
public sealed record IncidentAction(string Reason, int? Retries = null, string? Annotation = null);

/// <summary>Body of outbox requeue/skip (audited).</summary>
public sealed record OutboxRepairRequest(string Reason, string? Destination = null);

/// <summary>Body of <c>POST /ops/jobs/{id}/retries</c>.</summary>
public sealed record JobRetriesRequest(int Retries, string Reason);
