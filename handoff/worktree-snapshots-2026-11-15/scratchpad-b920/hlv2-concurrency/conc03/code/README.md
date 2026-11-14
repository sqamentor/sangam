---
title: Shared machine locks v2 (heavy_lock.py, compile_lock.py, shared_lock.py)
version: 2.0
date: 2026-11-10
owner: IFMS master session (tooling built by a sub-agent on its instruction)
record_ids: P-458; incidents I-44, I-45, I-59; contract [CONTRACT] HEAVY-LOCK-V2 (sent to the Sangam session)
stage: tooling candidate, not yet rolled out (the IFMS master swaps it into the live scratchpad and commits)
evidence_basis: v1 sources heavy_lock.py / compile_lock.py (scratchpad of session ff9f2caf, tracked copy in session_tools/scratch_A); selftest.py in this folder
---

# Shared machine locks v2

One implementation (`shared_lock.py`), two thin wrappers: `heavy_lock.py` (lock file `heavy-build.lock`, one heavy
step at a time on this machine: Maven verify/lanes, Testcontainers, demo stack, docker scans) and `compile_lock.py`
(lock file `compile.lock`, one Maven compile at a time). Python 3.13 standard library only, no psutil. The lock folder
is the folder the wrapper lives in, unless `SHARED_LOCK_DIR` is set (used by the self-test).

What v2 adds over v1 (I-45, I-59: locks left behind by holders that had died, waiting hours for a human):
the lock record can carry the pid of the long-lived job that holds it, that process's creation time (so a reused
Windows pid is not mistaken for the holder) and a lease that the job renews with `heartbeat`. A waiter removes a
lock only when the holder is provably dead **and** the lease has run out. Everything else is v1: by-name ownership,
`O_EXCL` creation, FIFO ticket queue with the 90 s dead-ticket rule (I-44), the 3-hour STALE warning for locks
without a pid, and every line that scripts grep for.

## Files

| File | Role |
|---|---|
| `shared_lock.py` | the implementation; `main(argv, lock_name, default_dir, doc)` |
| `heavy_lock.py` | wrapper, lock `heavy-build.lock` |
| `compile_lock.py` | wrapper, lock `compile.lock` |
| `selftest.py` | planted-case proof (see below) |
| `<lock>` | JSON record of the holder; the file's existence is the lock |
| `<lock>.queue/` | one ticket per waiter, `<arrival_ns>-<owner>-<pid>`; oldest live ticket goes first |
| `<lock>.served.log` | one line per grant, one line per auto-release |

File names and formats are those of v1, so v1 and v2 callers share one queue and one lock during the rollout.

## Lock record

| Field | v1 | Meaning |
|---|---|---|
| `owner` | yes | name that holds the lock until `release <owner>` |
| `what` | yes | free text |
| `since`, `epoch` | yes | grant time (ISO UTC, epoch seconds) |
| `held_by` | yes | `owner name until release (not a pid)` |
| `acquirer_pid`, `acquirer_pid_note` | yes | the short-lived `acquire` process (I-34); says nothing about liveness |
| `holder_pid` | new | long-lived job pid given with `--pid` or `HEAVY_LOCK_PID` |
| `holder_start` | new | creation time of that process, epoch seconds; `null` when unreadable. Windows: `OpenProcess` + `GetProcessTimes` via ctypes. Linux: `/proc/<pid>/stat` field 22 + `btime`. Other POSIX: `null` |
| `lease_until` | new | epoch; renewed by `heartbeat` |
| `lease_seconds` | new | default 1800 |

Without a pid the record has none of the four new fields (old-style lock, exact v1 behaviour).

## CLI

```
python -I heavy_lock.py acquire <owner> "<what>" [--pid N] [--lease SECONDS]
python -I heavy_lock.py heartbeat <owner>
python -I heavy_lock.py release <owner>
python -I heavy_lock.py status
```
(`compile_lock.py` takes the same commands.)

- `acquire` waits in the FIFO queue until the lock is free, then takes it atomically. The pid also comes from env
  `HEAVY_LOCK_PID` when `--pid` is absent. A dead or unqueryable pid is accepted with a warning on stderr.
- `heartbeat` renews the lease: `lease_until = now + lease_seconds` (the lease is re-armed for a full period from
  now, not stacked). Exit 2 with `refused: lock is held by <o>, not <owner>` or `refused: no lock held`. On an
  old-style lock held by the owner it prints `heartbeat ignored: ...` and exits 0. The record is rewritten in place
  through the existing file, never re-created, so a heartbeat that races a release cannot resurrect a released lock.
- `release` as v1: only the owner; `released by <owner>` / `no lock held` (exit 0) / `refused: ...` (exit 2).
- `status` prints the JSON record (first line, `null` when free), then `holder pid: <n> alive=yes|no|unknown (...)`
  and `lease remaining: <seconds> s (...)` (negative when expired), or `holder pid: none (old-style lock, ...)` and
  `lease: none`; then `waiting <ticket> arrived=<iso>` per ticket and the last 5 served-log lines.
- Exit codes: 0 ok; 2 refused; 64 usage; 70 `shared_lock.py` missing next to the wrapper; 1 I/O failure on release.

Alive means: the pid is running **and**, when both are known, its start time matches `holder_start` (tolerance 2 s).
`unknown` (the pid exists but cannot be queried, for example an elevated process) counts as alive.

## Runner usage

PowerShell (`$PID` is the long-lived PowerShell host that runs the step):

```powershell
$lock  = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$owner = "sangam"
python -I $lock acquire $owner "Trial 2 retest" --pid $PID
if ($LASTEXITCODE -ne 0) { throw "heavy lock not acquired (rc=$LASTEXITCODE)" }
$hb = Start-Job -ArgumentList $lock, $owner -ScriptBlock {
    param($lock, $owner)
    while ($true) { Start-Sleep -Seconds 600; python -I $lock heartbeat $owner | Out-Null }
}
try {
    # ... the heavy step (mvn verify, Testcontainers, demo stack) ...
}
finally {
    Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue
    python -I $lock release $owner
}
```

bash:

```bash
python -I "$LOCK" acquire sangam "Trial 2 retest" --pid $$ || exit 1
( while sleep 600; do python -I "$LOCK" heartbeat sangam >/dev/null; done ) & HB=$!
trap 'kill $HB 2>/dev/null; python -I "$LOCK" release sangam' EXIT
# ... heavy step ...
```

Heartbeat every 600 s against the default 1800 s lease leaves two missed beats of margin. A job that needs no
liveness (short, attended) may still call `acquire` without `--pid` and gets a v1 lock.

## Stale rule (inside `acquire`'s wait loop only)

| `holder_pid` | holder alive? | lease | waiter does |
|---|---|---|---|
| none (old-style) | n/a | n/a | waits; after 3 h (`SHARED_LOCK_STALE_SECONDS`) prints the v1 `STALE lock held by ...; report to the main session, do not delete` on stderr every 5 min; never removes |
| recorded | no (not running, or running with a different start time = pid reused) | expired | removes the lock, appends `<now> auto-released stale lock owner=<o> pid=<p> since=<t> reason=dead-holder+expired-lease` to the served log, prints `auto-released stale lock ...` on stderr, continues under the FIFO rule |
| recorded | no | not expired | waits for the lease (`holder of lock <o> is not running ...; lease expires in Ns` every 5 min) |
| recorded | yes or unknown | expired | never removes; `MISSED HEARTBEAT: lock held by <o> ...; not auto-released, report to the main session` every 5 min |
| recorded | yes or unknown | ok | waits quietly; after 3 h a `LONG-HELD lock ...; not stale` note every 5 min |

Only the oldest live ticket evaluates the rule, and the removal moves the lock file aside and re-reads it before
deleting, so a fresh lock written in the same instant is put back rather than destroyed. The removal is logged in
the served log as above; `status` shows it in the last 5 lines. `SHARED_LOCK_NO_AUTORELEASE=1` disables the rule
(test-only; the waiter then reports `auto-release disabled ...`).

## Environment variables

| Variable | Use |
|---|---|
| `SHARED_LOCK_DIR` | lock folder instead of the wrapper's folder (self-test) |
| `HEAVY_LOCK_PID` | holder pid when `--pid` is absent (both wrappers) |
| `SHARED_LOCK_STALE_SECONDS` | STALE / LONG-HELD threshold, default 10800 (test-only) |
| `SHARED_LOCK_POLL_SECONDS` | wait-loop poll, default 5 (test-only) |
| `SHARED_LOCK_NO_AUTORELEASE` | `1` disables the stale rule (negative control) |

## Self-test

```powershell
python -I selftest.py                      # all cases; exit 0 only when every case passes
python -I selftest.py --negative-control   # case (a) with SHARED_LOCK_NO_AUTORELEASE=1: must come out failing, exit 1
```

Cases, each in its own temporary lock folder with real child processes: (a) dead pid + expired lease is
auto-released with the served-log line and the next acquire succeeds; (b) dead pid + unexpired 3 s lease is kept
until the lease, then released; (c) live pid (`python -c "import time; time.sleep(30)"`) + expired lease is kept
and `MISSED HEARTBEAT` is printed; (d) old-style lock is kept and the v1 STALE path fires with
`SHARED_LOCK_STALE_SECONDS=1`; (e) a live pid with a wrong `holder_start` is treated as dead; (f) FIFO, the older
ticket wins; (g) heartbeat moves the lease and is refused for a non-owner and with no lock; (h) release by a
non-owner is refused; (w) the wrappers use their own lock files, `HEAVY_LOCK_PID`, usage exit 64; (nc) the built-in
negative control re-runs (a) with the stale rule disabled and passes only if (a) then fails.

## Rollout (done by the IFMS master, when both locks are free)

1. `python -I heavy_lock.py status` and `python -I compile_lock.py status` on the live copies print `null` and no
   `waiting` lines.
2. Copy `shared_lock.py` into the live scratchpad first (write to `shared_lock.py.new`, then `os.replace` to
   `shared_lock.py`), so no wrapper ever runs without it.
3. For each wrapper: keep the old file as `heavy_lock.py.v1.bak` / `compile_lock.py.v1.bak`, write the v2 wrapper to
   `<name>.new`, then `os.replace("<name>.new", "<name>")` (atomic on NTFS). Lock state lives in the lock files, not
   in the scripts, so a v1 waiter already in its loop is unaffected and keeps its FIFO place.
4. Re-run `status` on both; then record the swap (commit, event row).

```python
import os, shutil
live = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad"
src = os.path.dirname(os.path.abspath(__file__))  # this folder
for lock in ("heavy-build.lock", "compile.lock"):
    assert not os.path.exists(os.path.join(live, lock)), f"{lock} is held; wait"
    q = os.path.join(live, lock + ".queue")
    assert not (os.path.isdir(q) and os.listdir(q)), f"{lock} has waiters; wait"
for name in ("shared_lock.py", "heavy_lock.py", "compile_lock.py"):   # shared_lock.py first
    old = os.path.join(live, name)
    if name != "shared_lock.py" and os.path.exists(old):
        shutil.copy2(old, old.replace(".py", ".py.v1.bak"))
    shutil.copyfile(os.path.join(src, name), old + ".new")
    os.replace(old + ".new", old)
```

Rollback: `os.replace("<name>.py.v1.bak", "<name>.py")` for both wrappers; v2 lock records are read by v1 (extra
fields ignored) and a v1 waiter simply never auto-releases.

## Wording versus v1

Unchanged, byte for byte (scripts grep for them): `acquired by <owner>: <what>` (stdout), `released by <owner>`
(stdout), `no lock held` (stdout, exit 0), `refused: lock is held by <o>, not <owner>` (stderr, exit 2),
`STALE lock held by <o> since <t> (<what>); report to the main session, do not delete` (stderr, old-style locks),
`waiting <ticket> arrived=<iso>` and the served line `<now> served <owner> arrived=<iso> what=<what>` for locks
without a pid; the first line of `status` is still the JSON record (`null` when free); usage exits 64.

New lines (all additive): served line for pid locks `<now> served <owner> arrived=<iso> pid=<n> lease=<s>s what=<what>`
and the auto-release line; stderr `holder pid <n> (started ...), lease <s>s until ...` after a pid acquire;
`auto-released stale lock held by ...`; `MISSED HEARTBEAT: ...`; `LONG-HELD lock ...`; `holder of lock <o> is not
running ...`; `warning: --pid ...`; `note: --lease ignored ...`; `ticket expired while waiting ...`; stdout
`heartbeat by <owner>: lease extended to <iso>`; stderr `refused: no lock held` (heartbeat only); `status` lines
`holder pid: ...`, `lease remaining: ...`, `lease: none`. The module docstrings (printed on bad usage) are rewritten.

Behaviour differences besides liveness: `compile_lock.py` now writes the served log and prints the queue and log in
`status` (v1 compile_lock did neither); `release` retries a Windows sharing violation ten times instead of
crashing; a waiter whose ticket was dropped while it was stalled re-queues at the back instead of crashing on
`os.utime`; an unreadable lock file is re-read once after 50 ms before the v1 placeholder is used.

## Limitations

- Start-time retrieval is tested on Windows only here; the Linux `/proc` path and the POSIX `os.kill(pid, 0)` path
  are written from the documented interfaces and untested on this machine. On other POSIX systems `holder_start`
  is `null`, so only the running check guards against pid reuse there.
- A holder pid that exists but cannot be opened (another user, elevated) is `unknown` and is treated as alive: the
  lock is never auto-released; `status` says so.
- A corrupt or empty lock file (placeholder `unreadable lock file`) is treated as an old-style lock: never removed,
  STALE warning after 3 h, manual removal as in v1.
- The holder pid is only as good as what the runner passes. `$PID` of a long-lived interactive shell keeps a lock
  alive after the job ended until `release` runs; the `finally` block in the runner is still the primary release.
- Start-time tolerance is 2 s (covers the whole-second `btime` on Linux); a reused pid created within 2 s of the
  original would be taken for the holder, which errs towards keeping the lock.
