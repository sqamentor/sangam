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


edit('Worker/TaskContext.cs', [
("""    public string OperationId => Work.OperationId ?? Work.Id;""", """    public string OperationId => Work.OperationId ?? Work.Id!;"""),
])
edit('Worker/SangamWorker.cs', [
("""                if (w.AttemptId is null || w.Id is null)""", """                if (w.AttemptId is null || w.Id is null || w.LockVersion is null)"""),
("""        _inFlight[work.Id] = ctx;""", """        _inFlight[ctx.WorkId] = ctx;"""),
("""            _inFlight.TryRemove(work.Id, out _);""", """            _inFlight.TryRemove(ctx.WorkId, out _);"""),
("""                    if (r.CancelRequested && !ctx.IsCancelRequested)""", """                    if (r.CancelRequested == true && !ctx.IsCancelRequested)"""),
("""            var op = w.OperationId ?? w.Id;
            await _client.Work.UnlockAsync(w.Id, new UnlockRequest(WorkerId, w.LockVersion, w.AttemptId), $"{op}:unlock:{w.LockVersion}").ConfigureAwait(false);""",
 """            if (w.Id is null || w.LockVersion is not { } lockVersion)
            {
                // without the work id and the fencing token the server cannot accept an unlock; the lease expires
                _log.Warn("work.unlock.skipped", "unlock impossible without id and lockVersion; lease will expire", F(("workerId", WorkerId), ("workId", w.Id)));
                return;
            }
            var op = w.OperationId ?? w.Id;
            await _client.Work.UnlockAsync(w.Id, new UnlockRequest(WorkerId, lockVersion, w.AttemptId), $"{op}:unlock:{lockVersion}").ConfigureAwait(false);"""),
])
print('ok')
