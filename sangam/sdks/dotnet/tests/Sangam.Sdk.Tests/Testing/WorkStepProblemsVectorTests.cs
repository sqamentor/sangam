// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using Sangam.Sdk.Errors;
using Sangam.Sdk.Models;
using Sangam.Sdk.Testing;
using Xunit;
using WorkStepRequest = Sangam.Sdk.Generated.Models.WorkStepRequest;

namespace Sangam.Sdk.Tests.Testing;

/// <summary>
/// SL-174: step routes and lease calls on work that is not open answer exactly what the server answers. The expected
/// problems come from <c>sdks/testing/work-step-problems.json</c>, which the server's <c>WorkStepJournalH2Test</c> and
/// <c>WorkCallProblemsVectorH2Test</c> check against the server work adapters too, so the engine and the server are
/// compared through one table.
/// </summary>
public sealed class WorkStepProblemsVectorTests
{
    private static readonly HashSet<string> Fixed = new() { "id", "scenario", "call", "status", "code" };

    private static JsonElement Vectors()
    {
        foreach (var root in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            for (var dir = new DirectoryInfo(root); dir is not null; dir = dir.Parent)
            {
                var p = Path.Combine(dir.FullName, "testing", "work-step-problems.json");
                if (File.Exists(p))
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(p));
                    return doc.RootElement.Clone();
                }
            }
        }
        throw new FileNotFoundException("sdks/testing/work-step-problems.json not found above " + AppContext.BaseDirectory);
    }

    public static IEnumerable<object[]> Cases() =>
        Vectors().GetProperty("cases").EnumerateArray().Select(c => new object[] { c.GetProperty("id").GetString()! });

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task StepRoutesOnWorkThatIsNotOpenAnswerTheServersProblem(string caseId)
    {
        var file = Vectors();
        var c = file.GetProperty("cases").EnumerateArray().Single(x => x.GetProperty("id").GetString() == caseId);
        var step = file.GetProperty("stepName").GetString()!;
        var e = new SangamTestEngine();
        using var client = e.Client();
        var id = e.AddWork("pay");
        var w = Assert.Single(await client.Work.FetchAndLockAsync(
            new FetchAndLockRequest("w1", new[] { new FetchTopic("pay", 60_000) }, 1, 0)));
        var lv = Assert.IsType<long>(w.LockVersion);
        var target = id;
        switch (c.GetProperty("scenario").GetString())
        {
            case "UNKNOWN": target = file.GetProperty("unknownWorkId").GetString()!; break;
            case "COMPLETED": await client.Work.CompleteAsync(id, new CompleteWorkRequest("w1", lv, w.AttemptId, null), "k-c"); break;
            case "DELETED": e.DeleteInstance(id); break;
            case "RELEASED": await client.Work.UnlockAsync(id, new UnlockRequest("w1", lv, w.AttemptId), "k-u"); break;
            case "UNKNOWN_OUTCOME": await AwaitReconciliation(e, client, id, lv, w.AttemptId); break;
            default: throw new InvalidOperationException("unknown scenario " + c.GetProperty("scenario"));
        }
        Expect(c, "GET", await Assert.ThrowsAnyAsync<SangamApiException>(() =>
            client.Api.Work.GetWorkStepAsync(target, step, "w1", lv, w.AttemptId!)));
        Expect(c, "PUT", await Assert.ThrowsAnyAsync<SangamApiException>(() =>
            client.Api.Work.RecordWorkStepAsync(target, step, new WorkStepRequest
            {
                WorkerId = "w1", LockVersion = lv, AttemptId = w.AttemptId, State = "INTENT",
            })));
    }

    [Fact]
    public void EveryVectorIsRead()
    {
        Assert.True(Vectors().GetProperty("cases").GetArrayLength() >= 5);
        Assert.True(Vectors().GetProperty("workCalls").GetArrayLength() >= 12);
    }

    public static IEnumerable<object[]> WorkCalls() =>
        Vectors().GetProperty("workCalls").EnumerateArray().Select(c => new object[] { c.GetProperty("id").GetString()! });

    [Theory]
    [MemberData(nameof(WorkCalls))]
    public async Task LeaseCallsOnWorkThatIsNotOpenAnswerTheServersProblem(string caseId)
    {
        var c = Vectors().GetProperty("workCalls").EnumerateArray().Single(x => x.GetProperty("id").GetString() == caseId);
        var e = new SangamTestEngine();
        using var client = e.Client();
        var id = e.AddWork("pay");
        var w = Assert.Single(await client.Work.FetchAndLockAsync(
            new FetchAndLockRequest("w1", new[] { new FetchTopic("pay", 60_000) }, 1, 0)));
        var lv = Assert.IsType<long>(w.LockVersion);
        var at = w.AttemptId;
        switch (c.GetProperty("scenario").GetString())
        {
            case "INCIDENT":
                await client.Work.FailAsync(id, new FailWorkRequest("w1", lv, at, "no retries left", null, 0, 0), "k-f0");
                Assert.Equal("INCIDENT", e.State(id));
                break;
            case "UNKNOWN_OUTCOME": await AwaitReconciliation(e, client, id, lv, at); break;
            default: throw new InvalidOperationException("unknown scenario " + c.GetProperty("scenario"));
        }
        var call = c.GetProperty("call").GetString()!;
        Func<Task> run = call switch
        {
            "complete" => () => client.Work.CompleteAsync(id, new CompleteWorkRequest("w1", lv, at, null), "k1"),
            "fail" => () => client.Work.FailAsync(id, new FailWorkRequest("w1", lv, at, "again", null, 1, 0), "k2"),
            "bpmn-error" => () => client.Work.BpmnErrorAsync(id, new BpmnErrorRequest("w1", lv, at, "E1", null, null), "k3"),
            "unlock" => () => client.Work.UnlockAsync(id, new UnlockRequest("w1", lv, at), "k4"),
            "extend-lock" => () => client.Work.ExtendLockAsync(id, new ExtendLockRequest("w1", lv, at, 60_000), "k5"),
            "outcome-unknown" => () => client.Work.OutcomeUnknownAsync(id, new OutcomeUnknownRequest("w1", lv, at, "unsure", null), "k6"),
            _ => throw new InvalidOperationException("unknown call " + call),
        };
        Expect(c, call, await Assert.ThrowsAnyAsync<SangamApiException>(run));
    }

    /// <summary>The UNKNOWN_OUTCOME scenario: the worker reports an unknown outcome; the work awaits reconciliation.</summary>
    private static async Task AwaitReconciliation(SangamTestEngine e, SangamClient client, string id, long lv, string? at)
    {
        await client.Work.OutcomeUnknownAsync(id, new OutcomeUnknownRequest("w1", lv, at, "unsure", null), "k-o");
        Assert.Equal("UNKNOWN_OUTCOME", e.State(id));
    }

    private static void Expect(JsonElement c, string route, SangamApiException ex)
    {
        var what = c.GetProperty("id").GetString() + " " + route;
        Assert.True(c.GetProperty("status").GetInt32() == ex.Status, what + " status " + ex.Status);
        Assert.True(c.GetProperty("code").GetString() == ex.Problem.Code, what + " code " + ex.Problem.Code);
        foreach (var f in c.EnumerateObject().Where(f => !Fixed.Contains(f.Name)))
        {
            JsonElement v = default;
            var present = ex.Problem.Extensions is not null && ex.Problem.Extensions.TryGetValue(f.Name, out v);
            Assert.True(present && v.ToString() == f.Value.ToString(), what + " " + f.Name);
        }
    }
}
