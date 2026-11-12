// SPDX-License-Identifier: Apache-2.0
using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Sangam.Sdk.Testing;

/// <summary>
/// The step journal of <see cref="SangamTestEngine"/> (M-025, R37): <c>GET/PUT /work/{id}/steps/{stepName}</c> and
/// <c>GET /work/{id}/steps</c>, keyed by (operationId, step name) and fenced by the lease like the server
/// (<c>WorkStepJournal</c> + <c>WorkPort.inFencedWork</c>). Same transitions: INTENT on a new, INTENT or FAILED step;
/// SUCCEEDED/FAILED need an INTENT (409 STEP_INTENT_REQUIRED); SUCCEEDED is final (409 STEP_ALREADY_RECORDED) unless
/// an identical repeat; an identical repeat of INTENT/FAILED by the same attempt is a no-op. The engine calls it while
/// it holds its lock, after its expiry sweep.
/// </summary>
internal sealed class EngineSteps
{
    private sealed class Row
    {
        public string OperationId = "";
        public string Name = "";
        public string WorkId = "";
        public string State = "";
        public JsonNode? Result;
        public string? ProviderRef;
        public string? Error;
        public string? AttemptId;
        public DateTimeOffset RecordedAt;
    }

    /// <summary>operationId -> rows in recording order.</summary>
    private readonly Dictionary<string, List<Row>> _steps = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Work> _works;
    private readonly Action<Work, string?, long, string?> _fence;
    private readonly Func<DateTimeOffset> _now;

    internal EngineSteps(Dictionary<string, Work> works, Action<Work, string?, long, string?> fence, Func<DateTimeOffset> now)
    {
        _works = works;
        _fence = fence;
        _now = now;
    }

    /// <summary>True for <c>/work/{id}/steps</c> and <c>/work/{id}/steps/{name}</c>.</summary>
    internal static bool IsStepPath(string apiPath)
    {
        if (!apiPath.StartsWith("/work/", StringComparison.Ordinal)) return false;
        var seg = apiPath.Split('/');   // "", "work", id, "steps"[, name]
        return (seg.Length == 4 || seg.Length == 5) && seg[2].Length > 0 && seg[3] == "steps";
    }

    /// <summary>Serves one journal request; <paramref name="query"/> is the raw query string (with or without '?').</summary>
    internal Answer Handle(string method, string apiPath, string? query, JsonNode? body)
    {
        var seg = apiPath.Split('/');
        var workId = Uri.UnescapeDataString(seg[2]);
        var name = seg.Length > 4 ? Uri.UnescapeDataString(seg[4]) : null;
        if (name is null && method == "GET") return new Answer(200, List(workId).ToJsonString(), "application/json");
        if (name is not null && method == "GET")
        {
            var q = Query(query);
            if (!q.TryGetValue("lockVersion", out var lvText)
                || !long.TryParse(lvText, NumberStyles.Integer, CultureInfo.InvariantCulture, out var lv))
                throw new ProblemException(400, "VALIDATION_FAILED", null, "lockVersion is required");
            q.TryGetValue("workerId", out var workerId);
            q.TryGetValue("attemptId", out var attemptId);
            return new Answer(200, Get(workId, name, workerId, lv, attemptId).ToJsonString(), "application/json");
        }
        if (name is not null && method == "PUT")
            return new Answer(200, Put(workId, name, body as JsonObject ?? new JsonObject()).ToJsonString(), "application/json");
        throw new ProblemException(405, "METHOD_NOT_ALLOWED", null, $"{method} {apiPath} is not served by the SDK test engine");
    }

    internal JsonObject Get(string workId, string name, string? workerId, long lockVersion, string? attemptId)
    {
        var w = Fenced(workId, workerId, lockVersion, attemptId, name);
        var r = Find(w.OperationId, name);
        if (r is null) throw SangamTestEngine.NotFound("work step", name);
        return View(r);
    }

    internal JsonObject Put(string workId, string name, JsonObject body)
    {
        var attemptId = Text(body, "attemptId");
        var w = Fenced(workId, Text(body, "workerId"), LongOf(body, "lockVersion"), attemptId, name);
        var state = Text(body, "state");
        if (state is not ("INTENT" or "SUCCEEDED" or "FAILED"))
            throw new ProblemException(400, "VALIDATION_FAILED", null, "state must be INTENT, SUCCEEDED or FAILED");
        var result = body.TryGetPropertyValue("result", out var res) && res is not null ? Clone(res) : null;
        var providerRef = Text(body, "providerRef");
        var error = Text(body, "error");
        if (!_steps.TryGetValue(w.OperationId, out var rows)) _steps[w.OperationId] = rows = new List<Row>();
        var r = rows.Find(x => x.Name == name);
        if (r is null)
        {
            if (state != "INTENT") throw IntentRequired(name, null);
            r = new Row { OperationId = w.OperationId, Name = name };
            rows.Add(r);
        }
        else
        {
            if (Identical(r, state, attemptId, result, providerRef, error)) return View(r);
            if (r.State == "SUCCEEDED")
                throw new ProblemException(409, "STEP_ALREADY_RECORDED", null, $"step {name} already SUCCEEDED");
            if (state != "INTENT" && r.State != "INTENT") throw IntentRequired(name, r.State);
        }
        r.WorkId = workId;
        r.State = state;
        r.Result = result;
        r.ProviderRef = providerRef;
        r.Error = error;
        r.AttemptId = attemptId;
        r.RecordedAt = _now();
        return View(r);
    }

    internal JsonArray List(string workId)
    {
        var rows = new List<Row>();
        // as WorkStepController.list: the operation of open work, else the rows this work id recorded
        if (_works.TryGetValue(workId, out var w) && w.Exists)
        {
            if (_steps.TryGetValue(w.OperationId, out var op)) rows.AddRange(op);
        }
        else
        {
            foreach (var op in _steps.Values) rows.AddRange(op.Where(r => r.WorkId == workId));
            if (rows.Count == 0) throw SangamTestEngine.NotFound("external work", workId);
        }
        var output = new JsonArray();
        foreach (var r in rows) output.Add(View(r));
        return output;
    }

    private Work Fenced(string workId, string? workerId, long lockVersion, string? attemptId, string name)
    {
        if (string.IsNullOrEmpty(name) || name.Length > 128 || name.Any(c => c < 0x20 || c > 0x7e))
            throw new ProblemException(400, "VALIDATION_FAILED", null, "stepName must be 1-128 printable ASCII characters");
        if (string.IsNullOrWhiteSpace(attemptId))
            throw new ProblemException(400, "VALIDATION_FAILED", null, "attemptId is required");
        if (!_works.TryGetValue(workId, out var w)) throw SangamTestEngine.NotFound("external work", workId);
        // as OperatonWorkAdapter.inFencedWork: a deleted instance (tombstone) is 410, any other ended work 404 (SL-174)
        if (!w.Exists && w.Tombstone is not null) throw SangamTestEngine.Gone(workId, null);
        if (!w.Exists) throw SangamTestEngine.NotFound("external work", workId);
        _fence(w, workerId, lockVersion, attemptId);
        return w;
    }

    private Row? Find(string operationId, string name) =>
        _steps.TryGetValue(operationId, out var rows) ? rows.Find(x => x.Name == name) : null;

    private static bool Identical(Row r, string state, string? attemptId, JsonNode? result, string? providerRef, string? error)
    {
        if (r.State != state) return false;
        if (state == "SUCCEEDED") return SameJson(r.Result, result) && r.ProviderRef == providerRef;
        return r.AttemptId == attemptId && SameJson(r.Result, result) && r.ProviderRef == providerRef && r.Error == error;
    }

    private static bool SameJson(JsonNode? a, JsonNode? b)
    {
        if (a is null || b is null) return a is null && b is null;
        return SangamTestEngine.JsonEquals(JsonSerializer.SerializeToElement(a), JsonSerializer.SerializeToElement(b));
    }

    private static ProblemException IntentRequired(string name, string? current) =>
        new(409, "STEP_INTENT_REQUIRED", null, $"step {name} has no INTENT" + (current is null ? "" : $" (it is {current})"));

    private static JsonObject View(Row r) => new()
    {
        ["operationId"] = r.OperationId,
        ["name"] = r.Name,
        ["state"] = r.State,
        ["result"] = Clone(r.Result),
        ["providerRef"] = r.ProviderRef,
        ["error"] = r.Error,
        ["attemptId"] = r.AttemptId,
        ["recordedAt"] = r.RecordedAt.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fffK", CultureInfo.InvariantCulture),
    };

    private static Dictionary<string, string> Query(string? query)
    {
        var q = new Dictionary<string, string>(StringComparer.Ordinal);
        if (string.IsNullOrEmpty(query)) return q;
        foreach (var kv in query.TrimStart('?').Split('&'))
        {
            var i = kv.IndexOf('=');
            if (i > 0) q[Uri.UnescapeDataString(kv[..i])] = Uri.UnescapeDataString(kv[(i + 1)..].Replace('+', ' '));
        }
        return q;
    }

    private static string? Text(JsonObject o, string field) =>
        o.TryGetPropertyValue(field, out var v) && v is JsonValue jv
            ? (jv.TryGetValue<string>(out var s) ? s : jv.ToJsonString())
            : null;

    private static long LongOf(JsonObject o, string field) =>
        o.TryGetPropertyValue(field, out var v) && v is JsonValue jv
            ? (jv.TryGetValue<long>(out var l) ? l : jv.TryGetValue<int>(out var i) ? i
                : long.TryParse(jv.ToJsonString().Trim('"'), NumberStyles.Integer, CultureInfo.InvariantCulture, out var p) ? p : 0)
            : 0;

    /// <summary>Deep copy (JsonNode.DeepClone needs .NET 8).</summary>
    private static JsonNode? Clone(JsonNode? n) => n is null ? null : JsonNode.Parse(n.ToJsonString());
}
