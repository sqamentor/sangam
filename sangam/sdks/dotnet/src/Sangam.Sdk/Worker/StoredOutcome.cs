// SPDX-License-Identifier: Apache-2.0
using System.Globalization;
using System.Text.Json;
using Sangam.Sdk.Models;

namespace Sangam.Sdk.Worker;

/// <summary>Variables and downstream reference to complete with, taken from the stored ledger outcome (SL-16).</summary>
public sealed record StoredOutcomeReplay(IDictionary<string, object?> Variables, string? Reference);

/// <summary>
/// SL-16 stored-outcome completion (same contract as every Sangam SDK): when the server reports the operation as
/// already SUCCEEDED and not dispatchable (<c>MayDispatch</c> false), the worker completes with
/// <c>storedOutcome.output</c> as variables and <c>storedOutcome.providerRef</c> as <c>outcome.reference</c>, without
/// calling the handler (the downstream effect already happened). Any other operation state runs the handler.
/// </summary>
public static class StoredOutcome
{
    /// <summary>The completion to send instead of running the handler, or null when the handler must run.</summary>
    public static StoredOutcomeReplay? Completion(WorkOperation? operation)
    {
        if (operation is null || operation.MayDispatch == true || !string.Equals(operation.State, "SUCCEEDED", StringComparison.Ordinal))
            return null;
        object? output = null;
        object? reference = null;
        if (operation.StoredOutcome is { } stored)
        {
            stored.TryGetValue("output", out output);
            stored.TryGetValue("providerRef", out reference);
        }
        return new StoredOutcomeReplay(AsVariables(output), AsText(reference));
    }

    private static IDictionary<string, object?> AsVariables(object? value)
    {
        var vars = new Dictionary<string, object?>();
        switch (value)
        {
            case IDictionary<string, object?> d:
                foreach (var kv in d) vars[kv.Key] = kv.Value;
                break;
            case JsonElement { ValueKind: JsonValueKind.Object } e:
                // wire values stay JsonElement (TypedValues are sent back exactly as the server stored them)
                foreach (var p in e.EnumerateObject()) vars[p.Name] = p.Value.Clone();
                break;
        }
        return vars;
    }

    private static string? AsText(object? value) => value switch
    {
        null => null,
        string s => s,
        JsonElement { ValueKind: JsonValueKind.Null or JsonValueKind.Undefined } => null,
        JsonElement { ValueKind: JsonValueKind.String } e => e.GetString(),
        JsonElement e => e.GetRawText(),
        _ => Convert.ToString(value, CultureInfo.InvariantCulture),
    };
}
