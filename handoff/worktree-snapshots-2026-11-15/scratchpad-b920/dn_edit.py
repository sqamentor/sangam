import io
import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = io.open(p, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in s else '\n'
    for old, new in pairs:
        o = old.replace('\n', nl)
        n = new.replace('\n', nl)
        assert s.count(o) == 1, (p, old)
        s = s.replace(o, n)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


edit('Models.cs', [
("""public sealed record ExternalWork(
    string Id, string Topic, string? TenantId,""", """public sealed record ExternalWork(
    string? Id, string? Topic, string? TenantId,"""),
("""    long LockVersion, string? AttemptId, string? OperationId, DateTimeOffset? LockExpiresAt, int? Retries,
    long? Priority, string? ErrorMessage)
{
    /// <summary>Not in contract 0.1.0; read if a server sends it. Irreversibility comes from <c>TopicSubscription.Irreversible</c>.</summary>
    public string? Effect { get; init; }

""", """    long? LockVersion, string? AttemptId, string? OperationId, DateTimeOffset? LockExpiresAt, int? Retries,
    long? Priority, string? ErrorMessage)
{
"""),
("""public sealed record WorkOperation(string State, int Attempts, string? CapabilityClass, bool MayDispatch, Vars? StoredOutcome);""",
 """public sealed record WorkOperation(string? State, int? Attempts, string? CapabilityClass, bool? MayDispatch, Vars? StoredOutcome);"""),
("""public sealed record FetchTopic(string Name, long LockDurationMs,""", """public sealed record FetchTopic(string Name, long? LockDurationMs,"""),
("""public sealed record FetchAndLockRequest(string WorkerId, IReadOnlyList<FetchTopic> Topics, int MaxTasks, long AsyncResponseTimeoutMs, bool? UsePriority = null);""",
 """public sealed record FetchAndLockRequest(string WorkerId, IReadOnlyList<FetchTopic> Topics, int? MaxTasks = null, long? AsyncResponseTimeoutMs = null, bool? UsePriority = null);"""),
("""public sealed record LockState(string? Id, long? LockVersion, DateTimeOffset? LockExpiresAt, bool CancelRequested);""",
 """public sealed record LockState(string? Id, long? LockVersion, DateTimeOffset? LockExpiresAt, bool? CancelRequested);"""),
("""/// <summary><c>Retries</c> left after this failure; 0 raises an incident.</summary>
public sealed record FailWorkRequest(string WorkerId, long LockVersion, string? AttemptId, string ErrorMessage, string? ErrorDetails, int Retries, long RetryTimeoutMs);""",
 """/// <summary><c>Retries</c> left after this failure; 0 raises an incident. Absent (null) members are not sent.</summary>
public sealed record FailWorkRequest(string WorkerId, long LockVersion, string? AttemptId, string? ErrorMessage = null, string? ErrorDetails = null, int? Retries = null, long? RetryTimeoutMs = null);"""),
("""public sealed record TaskView(
    string Id, string? Name,""", """public sealed record TaskView(
    string? Id, string? Name,"""),
("""    DateTimeOffset? LastUpdated, DateTimeOffset? ClaimTime, string? State, int? Priority, string? FormKey,
    string? DelegationState);""", """    DateTimeOffset? LastUpdated, DateTimeOffset? ClaimTime, string? State, int? Priority, string? FormKey,
    string? DelegationState, VarMap? Variables = null);"""),
("""public sealed record CompleteTaskRequest(string PayloadHash, Vars? Variables = null, string? Decision = null,
    string? Remarks = null, long? CaseVersion = null, string? FormVersion = null);""", """public sealed record CompleteTaskRequest(string PayloadHash, Vars? Variables = null, string? Decision = null,
    string? Remarks = null, long? CaseVersion = null, string? FormVersion = null, string? ReasonCode = null,
    string? NextSeatId = null, string? NextSeatReason = null);"""),
("""public sealed record ReturnTaskRequest(string Reason, string? ReasonCode = null, string? TargetActivityId = null, Vars? Variables = null);""",
 """public sealed record ReturnTaskRequest(string Reason, string? ReasonCode = null, string? TargetActivityId = null, Vars? Variables = null,
    string? TargetSeatId = null, int? TargetLevel = null, string? PayloadHash = null, long? CaseVersion = null);"""),
("""    string? ActivityId, string? TaskId, JsonElement? Payload, bool? Erased, string? Hash, JsonElement? Evidence);""",
 """    string? ActivityId, string? TaskId, JsonElement? Payload, bool? Erased, string? Hash, bool? Evidence,
    string? PrevHash = null, string? EntryHash = null, string? Envelope = null);"""),
])
edit('Apis.cs', [
("""        var t = TimeSpan.FromMilliseconds(request.AsyncResponseTimeoutMs) + TimeSpan.FromSeconds(15);""",
 """        var t = TimeSpan.FromMilliseconds(request.AsyncResponseTimeoutMs ?? 0) + TimeSpan.FromSeconds(15);"""),
("""    public Task ReturnAsync(string taskId, ReturnTaskRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync(ApiRequest.Post("/tasks/" + Seg(taskId) + "/return").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);""",
 """    /// <summary><c>POST /tasks/{id}/return</c>; answers the CompleteTaskResult (200).</summary>
    public Task<CompleteTaskResult?> ReturnAsync(string taskId, ReturnTaskRequest request, string? idempotencyKey = null, CancellationToken ct = default) =>
        CallAsync<CompleteTaskResult>(ApiRequest.Post("/tasks/" + Seg(taskId) + "/return").WithBody(request).WithIdempotencyKey(idempotencyKey), ct);"""),
])
edit('Worker/StoredOutcome.cs', [
("""if (operation is null || operation.MayDispatch ||""", """if (operation is null || operation.MayDispatch == true ||"""),
])
edit('Worker/TaskContext.cs', [
("""        _lockVersion = work.LockVersion;""", """        if (work.Id is null || work.AttemptId is null || work.LockVersion is null)
            // A-1: a handler runs only after the LOCKED confirmation (work id, lockVersion and attemptId)
            throw new ArgumentException("work item without its LOCKED confirmation (id, lockVersion, attemptId)", nameof(work));
        _lockVersion = work.LockVersion.Value;"""),
("""    public string WorkId => Work.Id;
    public string Topic => Work.Topic;""", """    public string WorkId => Work.Id!;
    /// <summary>The topic (the worker starts only items whose topic matches a subscription).</summary>
    public string Topic => Work.Topic ?? "";"""),
("""            ["lockVersion"] = work.LockVersion,""", """            ["lockVersion"] = _lockVersion,"""),
])
print('ok')
