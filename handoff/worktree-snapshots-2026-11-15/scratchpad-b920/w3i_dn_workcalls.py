import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def rep(a, b):
    global s
    assert s.count(a) == 1, a
    s = s.replace(a, b)


rep('/// SL-174: step routes on work that is not open answer exactly what the server answers. The expected problems come\n'
    '/// from <c>sdks/testing/work-step-problems.json</c>, which the server\'s <c>WorkStepJournalH2Test</c> checks against\n'
    '/// <c>OperatonWorkAdapter</c> too, so the engine and the server are compared through one table.',
    '/// SL-174: step routes and lease calls on work that is not open answer exactly what the server answers. The expected\n'
    '/// problems come from <c>sdks/testing/work-step-problems.json</c>, which the server\'s <c>WorkStepJournalH2Test</c> and\n'
    '/// <c>WorkCallProblemsVectorH2Test</c> check against the server work adapters too, so the engine and the server are\n'
    '/// compared through one table.')
rep('new() { "id", "scenario", "status", "code" };', 'new() { "id", "scenario", "call", "status", "code" };')
rep('''    public void EveryVectorIsRead() => Assert.True(Vectors().GetProperty("cases").GetArrayLength() >= 4);''',
'''    public void EveryVectorIsRead()
    {
        Assert.True(Vectors().GetProperty("cases").GetArrayLength() >= 4);
        Assert.True(Vectors().GetProperty("workCalls").GetArrayLength() >= 6);
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
        Assert.Equal("INCIDENT", c.GetProperty("scenario").GetString());
        await client.Work.FailAsync(id, new FailWorkRequest("w1", lv, at, "no retries left", null, 0, 0), "k-f0");
        Assert.Equal("INCIDENT", e.State(id));
        var call = c.GetProperty("call").GetString()!;
        Func<Task> run = call switch
        {
            "complete" => () => client.Work.CompleteAsync(id, new CompleteWorkRequest("w1", lv, at, null), "k1"),
            "fail" => () => client.Work.FailAsync(id, new FailWorkRequest("w1", lv, at, "again", null, 0, 0), "k2"),
            "bpmn-error" => () => client.Work.BpmnErrorAsync(id, new BpmnErrorRequest("w1", lv, at, "E1", null, null), "k3"),
            "unlock" => () => client.Work.UnlockAsync(id, new UnlockRequest("w1", lv, at), "k4"),
            "extend-lock" => () => client.Work.ExtendLockAsync(id, new ExtendLockRequest("w1", lv, at, 60_000), "k5"),
            "outcome-unknown" => () => client.Work.OutcomeUnknownAsync(id, new OutcomeUnknownRequest("w1", lv, at, "unsure", null), "k6"),
            _ => throw new InvalidOperationException("unknown call " + call),
        };
        Expect(c, call, await Assert.ThrowsAnyAsync<SangamApiException>(run));
    }''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
