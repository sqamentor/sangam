// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;

namespace Sangam.Sdk.Worker;

/// <summary>
/// What a saga's forward action leaves for its compensation (M-026, R36): the forward work's stable operationId, its
/// provider reference and its work id. Stored as the process variable <see cref="Saga.RecordVariable"/> when the forward
/// action completes.
/// </summary>
public sealed record SagaRecord(string? Step, string OperationId, string? ProviderRef, string? WorkId)
{
    internal Dictionary<string, object?> ToMap() => new()
    {
        ["step"] = Step,
        ["operationId"] = OperationId,
        ["providerRef"] = ProviderRef,
        ["workId"] = WorkId,
    };

    /// <summary>The record from its JSON object, or null when it carries no operationId.</summary>
    internal static SagaRecord? FromMap(IReadOnlyDictionary<string, JsonElement>? m)
    {
        if (m is null) return null;
        var op = Str(m, "operationId");
        return op is null ? null : new SagaRecord(Str(m, "step"), op, Str(m, "providerRef"), Str(m, "workId"));
    }

    private static string? Str(IReadOnlyDictionary<string, JsonElement> m, string key)
    {
        if (!m.TryGetValue(key, out var v)) return null;
        return v.ValueKind switch
        {
            JsonValueKind.Null or JsonValueKind.Undefined => null,
            JsonValueKind.String => v.GetString(),
            _ => v.GetRawText(),
        };
    }
}
