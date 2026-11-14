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


edit('WorkerTests.cs', [
("""        Assert.Equal("SUCCEEDED", w.Operation!.State);
        Assert.False(w.Operation.MayDispatch);""", """        Assert.Equal("SUCCEEDED", w.Operation!.State);
        Assert.True(w.Operation.MayDispatch == false);"""),
("""    // ---------------------------------------------------------------- loop
""", """    // ---------------------------------------------------------------- irreversibility (subscription flag only)

    [Fact]
    public async Task AStrayEffectMemberDoesNotMakeAnItemIrreversible()
    {
        // the server sends no effect (WorkPort.ExternalWork); irreversibility is the subscription flag only
        var server = new FakeServer();
        var item = FakeServer.Work()[..^1] + ",\\"effect\\":\\"irreversible\\"}";
        var r = await RunOne(server, Sub(Throws(new InvalidOperationException("boom"))), item);
        Assert.Equal("fail", r.Action);
    }

    [Fact]
    public async Task ItemWithoutLockVersionNeverStarts()
    {
        var server = new FakeServer().Enqueue(FakeServer.Work().Replace("\\"lockVersion\\":3,", ""));
        var calls = 0;
        var worker = server.Worker().Subscribe(Sub((_, _) =>
        {
            calls++;
            return Task.FromResult<IDictionary<string, object?>?>(null);
        }));
        worker.Start();
        await FakeServer.UntilAsync(() => server.Logged("work.protocol.violation"));
        await worker.StopAsync();
        Assert.Equal(0, calls);
    }

    // ---------------------------------------------------------------- loop
"""),
])
print('ok')
