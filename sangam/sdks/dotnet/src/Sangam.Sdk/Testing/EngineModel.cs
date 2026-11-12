// SPDX-License-Identifier: Apache-2.0
using System.Text.Json.Nodes;

namespace Sangam.Sdk.Testing;

// State of SangamTestEngine (M-021): the work item with its lease row, operation ledger entry and tombstone, the
// reconciliation items and the RFC 9457 problem, as internal types next to the engine (M-098 class-size limit).

internal sealed class Work
{
    public string Id = "";
    public string Topic = "";
    public string OperationId = "";
    public string ProcessInstanceId = "";
    public string ActivityInstanceId = "";
    public JsonNode Variables = new JsonObject();
    public int? Retries;
    public long Priority;
    public string? ErrorMessage;
    public string State = "AVAILABLE";
    public DateTimeOffset AvailableAt = DateTimeOffset.MinValue;
    // lease row (LeaseTable): null LeaseVersion = no row
    public long? LeaseVersion;
    public string? AttemptId;
    public string? WorkerId;
    public DateTimeOffset? ExpiresAt;
    // operation ledger (R37)
    public string OpState = "INTENT";
    public int Attempts;
    public JsonNode? StoredOutcome;
    public long? CompletedLockVersion;
    public JsonNode? CompletedOutcome;
    public JsonNode? CompletedVariables;
    public int Completions;
    public int Grants;
    // tombstone (VI.5)
    public string? Tombstone;
    public long? TombstoneVersion;

    public bool Exists => State is "AVAILABLE" or "LOCKED" or "INCIDENT" or "UNKNOWN_OUTCOME";
}

internal sealed class Recon
{
    public string Id = "";
    public string Kind = "";
    public string WorkId = "";
    public string OperationId = "";
    public string State = "OPEN";
}

internal sealed class ProblemException : Exception
{
    public int Status { get; }
    public string Code { get; }
    public string? Title { get; }
    public Dictionary<string, object?> Ext { get; } = new();

    public ProblemException(int status, string code, string? title, string detail) : base(detail)
    {
        Status = status;
        Code = code;
        Title = title;
    }

    public ProblemException With(string k, object? v)
    {
        if (v is not null) Ext[k] = v;
        return this;
    }
}

internal sealed record Answer(int Status, string? Body, string? ContentType, Dictionary<string, string>? Headers = null);
