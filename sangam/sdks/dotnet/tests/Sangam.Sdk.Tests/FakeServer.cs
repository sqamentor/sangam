// SPDX-License-Identifier: Apache-2.0
using System.Net;
using System.Text;
using System.Text.Json;
using Sangam.Sdk.Http;
using Sangam.Sdk.Logging;
using Sangam.Sdk.Worker;

namespace Sangam.Sdk.Tests;

/// <summary>One request as the fake server received it.</summary>
public sealed record Recorded(string Method, string Path, string Action, string? IdempotencyKey, JsonElement? Body);

/// <summary>
/// In-process fake of the Sangam worker API, plugged in as the client's HttpMessageHandler: it records every request,
/// answers fetch-and-lock from a queue and the result calls from scripted statuses (default 204).
/// </summary>
public sealed class FakeServer : HttpMessageHandler
{
    public static readonly HashSet<string> ReportActions = new() { "complete", "fail", "bpmn-error", "outcome-unknown", "unlock", "cancel-ack" };

    private readonly object _gate = new();
    private readonly List<Recorded> _calls = new();
    private readonly Queue<string> _batches = new();
    private readonly Dictionary<string, Queue<(int Status, string? Body)>> _replies = new();
    private readonly List<(string Event, IReadOnlyDictionary<string, object?>? Fields)> _logs = new();

    public ISangamLogger Logger { get; }

    public FakeServer()
    {
        Logger = SangamLoggers.From((_, ev, _, fields, _) =>
        {
            lock (_gate) _logs.Add((ev, fields));
        });
    }

    public IReadOnlyList<Recorded> Calls { get { lock (_gate) return _calls.ToList(); } }

    public IReadOnlyList<Recorded> CallsOf(string action) => Calls.Where(c => c.Action == action).ToList();

    public bool Logged(string ev, Func<IReadOnlyDictionary<string, object?>?, bool>? pred = null)
    {
        lock (_gate) return _logs.Any(l => l.Event == ev && (pred is null || pred(l.Fields)));
    }

    /// <summary>The next fetch-and-lock answers with this JSON array of work items.</summary>
    public FakeServer Enqueue(params string[] items)
    {
        lock (_gate) _batches.Enqueue("[" + string.Join(",", items) + "]");
        return this;
    }

    /// <summary>Scripted answers for <c>POST /work/{id}/{action}</c>, used in order; afterwards 204.</summary>
    public FakeServer Reply(string action, params (int Status, string? Body)[] replies)
    {
        lock (_gate)
        {
            if (!_replies.TryGetValue(action, out var q)) _replies[action] = q = new Queue<(int, string?)>();
            foreach (var r in replies) q.Enqueue(r);
        }
        return this;
    }

    public async Task<Recorded> WaitForAsync(Func<Recorded, bool> pred, int timeoutMs = 5000)
    {
        var end = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (true)
        {
            var hit = Calls.FirstOrDefault(pred);
            if (hit is not null) return hit;
            if (DateTime.UtcNow > end) throw new TimeoutException("no matching call");
            await Task.Delay(5);
        }
    }

    public static async Task UntilAsync(Func<bool> cond, int timeoutMs = 5000)
    {
        var end = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (!cond())
        {
            if (DateTime.UtcNow > end) throw new TimeoutException("condition not met in time");
            await Task.Delay(5);
        }
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var path = request.RequestUri!.AbsolutePath.Replace("/api/v1", "");
        var action = path[(path.LastIndexOf('/') + 1)..];
        var text = request.Content is null ? null : await request.Content.ReadAsStringAsync(ct);
        JsonElement? body = string.IsNullOrEmpty(text) ? null : JsonDocument.Parse(text).RootElement.Clone();
        var key = request.Headers.TryGetValues("Idempotency-Key", out var v) ? v.FirstOrDefault() : null;
        string? batch = null;
        (int Status, string? Body) reply = (204, null);
        lock (_gate)
        {
            _calls.Add(new Recorded(request.Method.Method, path, action, key, body));
            if (action == "fetch-and-lock") batch = _batches.Count > 0 ? _batches.Dequeue() : null;
            else if (_replies.TryGetValue(action, out var q) && q.Count > 0) reply = q.Dequeue();
        }
        if (action == "fetch-and-lock")
        {
            if (batch is null)
            {
                await Task.Delay(25, ct);
                batch = "[]";
            }
            return Json(200, batch);
        }
        return Json(reply.Status, reply.Body);
    }

    private static HttpResponseMessage Json(int status, string? body)
    {
        var r = new HttpResponseMessage((HttpStatusCode)status);
        if (body is not null && status != 204)
        {
            r.Content = new StringContent(body, Encoding.UTF8, status >= 400 ? "application/problem+json" : "application/json");
        }
        return r;
    }

    /// <summary>A leased item as the server sends it after LOCKED (A-1); the lease runs one minute.</summary>
    public static string Work(string? operation = null, string topic = "pay", string? attemptId = "att-1", int retries = 3,
        DateTimeOffset? lockExpiresAt = null)
    {
        var w = new Dictionary<string, object?>
        {
            ["id"] = "wk-1",
            ["topic"] = topic,
            ["lockVersion"] = 3,
            ["attemptId"] = attemptId,
            ["operationId"] = "op-1",
            ["lockExpiresAt"] = (lockExpiresAt ?? DateTimeOffset.UtcNow.AddMinutes(1)).ToString("O"),
            ["retries"] = retries,
            ["variables"] = new Dictionary<string, object?>(),
        };
        var json = JsonSerializer.Serialize(w);
        return operation is null ? json : json[..^1] + ",\"operation\":" + operation + "}";
    }

    public SangamClient Client() => new(new SangamClientOptions
    {
        BaseUrl = new Uri("http://sangam.test/"),
        HttpClient = new HttpClient(this, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan },
        Logger = Logger,
        // the worker's own report retry is under test, so the transport does not retry
        RetryPolicy = RetryPolicy.None,
    });

    public SangamWorker Worker() => new(Client(), new SangamWorkerOptions
    {
        WorkerId = "worker-1",
        MaxConcurrency = 1,
        MaxTasksPerFetch = 1,
        AsyncResponseTimeout = TimeSpan.FromMilliseconds(50),
        FetchBackoff = new Backoff(TimeSpan.FromMilliseconds(1), TimeSpan.FromMilliseconds(2), 1, 0),
        ReportBackoff = new Backoff(TimeSpan.FromMilliseconds(1), TimeSpan.FromMilliseconds(2), 1, 0),
        ShutdownTimeout = TimeSpan.FromSeconds(2),
        Logger = Logger,
    });
}
