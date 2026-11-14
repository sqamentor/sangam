"""shared_lock.py -- the one implementation behind heavy_lock.py and compile_lock.py (shared machine lock v2).

IFMS P-458; incidents I-44 (starvation, FIFO), I-45 and I-59 (locks left behind by dead holders). Contract
[CONTRACT] HEAVY-LOCK-V2. Python 3.13 standard library only. Not run directly: a thin wrapper calls
main(argv, lock_name=..., default_dir=..., doc=...).

Files -- unchanged from v1 so v1 and v2 callers coexist during the rollout:
    <dir>/<lock>              JSON record of the holder; the file's existence IS the lock (created with O_EXCL)
    <dir>/<lock>.queue/       one ticket per waiter, named <arrival_ns>-<owner>-<pid>; the oldest live ticket goes first
    <dir>/<lock>.served.log   one line per grant and one line per auto-release
<dir> is the folder the wrapper lives in, or $SHARED_LOCK_DIR when set.

Record fields: v1 (owner, what, since, epoch, held_by, acquirer_pid, acquirer_pid_note) plus, only when acquire was
given a holder pid: holder_pid (long-lived job pid), holder_start (that process's creation time, epoch seconds,
null when unreadable; guards against pid reuse), lease_until (epoch) and lease_seconds (default 1800).
A record without holder_pid is an old-style lock and keeps the v1 behaviour exactly.

Stale rule, applied only inside acquire's wait loop: a lock is removed only when ALL hold --
    1. holder_pid is recorded;
    2. that pid is not running, or it runs with a start time different from holder_start (pid reused);
    3. lease_until has passed.
A dead pid with an unexpired lease waits for the lease. A live pid with an expired lease is never released
(MISSED HEARTBEAT warning every 5 minutes). An old-style lock is never released (v1 STALE warning after 3 h).
Liveness "unknown" (pid exists but cannot be queried) counts as alive.

Environment: SHARED_LOCK_DIR (lock folder), HEAVY_LOCK_PID (holder pid when --pid is absent). Test-only overrides:
SHARED_LOCK_STALE_SECONDS, SHARED_LOCK_POLL_SECONDS, SHARED_LOCK_NO_AUTORELEASE=1 (disables the stale rule).
"""
import json
import os
import sys
import time
from datetime import datetime, timezone

DEFAULT_STALE_SECONDS = 3 * 3600      # v1: STALE warning threshold for locks without liveness fields
TICKET_DEAD_SECONDS = 90              # v1: a ticket not refreshed for 90 s belongs to a dead waiter and is dropped
DEFAULT_LEASE_SECONDS = 1800
DEFAULT_POLL_SECONDS = 5.0            # v1 slept 5 s between polls
REPORT_EVERY_SECONDS = 300            # v1: at most one stderr report per 5 minutes while waiting
START_TOLERANCE_SECONDS = 2.0         # start times within this are the same process (Linux btime is whole seconds)
AUTO_RELEASE_REASON = "dead-holder+expired-lease"
HELD_BY_NOTE = "owner name until release (not a pid)"
ACQUIRER_PID_NOTE = "short-lived acquire process; exits at once (I-34)"

ENV_DIR = "SHARED_LOCK_DIR"
ENV_PID = "HEAVY_LOCK_PID"
ENV_STALE = "SHARED_LOCK_STALE_SECONDS"
ENV_POLL = "SHARED_LOCK_POLL_SECONDS"
ENV_NO_AUTORELEASE = "SHARED_LOCK_NO_AUTORELEASE"

WINDOWS_EPOCH_OFFSET_100NS = 116444736000000000  # 1601-01-01 to 1970-01-01 in 100 ns units


def _env_float(name, default):
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw)
    except ValueError:
        print(f"warning: ignoring non-numeric {name}={raw!r}", file=sys.stderr, flush=True)
        return default
    return value if value > 0 else default


class Config:
    """Paths and tunables of one lock. default_dir is the wrapper's folder; SHARED_LOCK_DIR overrides it."""

    def __init__(self, lock_name, default_dir):
        self.dir = os.path.abspath(os.environ.get(ENV_DIR) or default_dir)
        self.lock = os.path.join(self.dir, lock_name)
        self.queue = self.lock + ".queue"
        self.served_log = self.lock + ".served.log"
        self.stale_seconds = _env_float(ENV_STALE, DEFAULT_STALE_SECONDS)
        self.poll = _env_float(ENV_POLL, DEFAULT_POLL_SECONDS)
        self.no_autorelease = os.environ.get(ENV_NO_AUTORELEASE, "").strip() not in ("", "0")


# --------------------------------------------------------------------------------------------- time helpers

def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def iso(epoch):
    try:
        return datetime.fromtimestamp(float(epoch), timezone.utc).isoformat(timespec="seconds")
    except (TypeError, ValueError, OverflowError, OSError):
        return "?"


# --------------------------------------------------------------------------------------------- lock record

def read(cfg):
    """The lock record, None when no lock is held, or a v1-style placeholder when the file is unreadable.
    One short retry covers a reader that lands between another process's create/rewrite and its write."""
    for attempt in range(2):
        try:
            with open(cfg.lock, encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return None
        except (ValueError, OSError):
            if attempt == 0:
                time.sleep(0.05)
    return {"owner": "?", "what": "unreadable lock file", "since": "?", "epoch": 0}


def _same_record(a, b):
    return all(a.get(k) == b.get(k) for k in ("owner", "since", "epoch", "holder_pid"))


def _append_served(cfg, line):
    try:
        with open(cfg.served_log, "a", encoding="utf-8") as log:
            log.write(line + "\n")
    except OSError:
        pass


# --------------------------------------------------------------------------------------------- process probes

def _probe_windows(pid):
    import ctypes
    from ctypes import wintypes

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.OpenProcess.restype = wintypes.HANDLE
    k32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    k32.CloseHandle.restype = wintypes.BOOL
    k32.CloseHandle.argtypes = (wintypes.HANDLE,)
    k32.WaitForSingleObject.restype = wintypes.DWORD
    k32.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
    k32.GetExitCodeProcess.restype = wintypes.BOOL
    k32.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
    k32.GetProcessTimes.restype = wintypes.BOOL
    k32.GetProcessTimes.argtypes = (wintypes.HANDLE,) + (ctypes.POINTER(wintypes.FILETIME),) * 4

    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    SYNCHRONIZE = 0x00100000
    ERROR_ACCESS_DENIED = 5
    ERROR_INVALID_PARAMETER = 87
    WAIT_OBJECT_0 = 0x0
    WAIT_TIMEOUT = 0x102
    STILL_ACTIVE = 259

    can_wait = True
    handle = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, False, pid)
    if not handle:
        err = ctypes.get_last_error()
        if err == ERROR_INVALID_PARAMETER:
            return False, None, "no such process"
        if err != ERROR_ACCESS_DENIED:
            return None, None, f"OpenProcess failed (error {err})"
        can_wait = False
        handle = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not handle:
            return None, None, "process exists but access is denied"
    try:
        if can_wait:
            state = k32.WaitForSingleObject(handle, 0)
            alive = True if state == WAIT_TIMEOUT else (False if state == WAIT_OBJECT_0 else None)
        else:
            code = wintypes.DWORD()
            alive = (code.value == STILL_ACTIVE) if k32.GetExitCodeProcess(handle, ctypes.byref(code)) else None
        start = None
        created, exited, kernel, user = (wintypes.FILETIME() for _ in range(4))
        if k32.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited),
                               ctypes.byref(kernel), ctypes.byref(user)):
            filetime = (created.dwHighDateTime << 32) | created.dwLowDateTime
            start = (filetime - WINDOWS_EPOCH_OFFSET_100NS) / 1e7
        detail = "running" if alive else ("terminated" if alive is False else "state unknown")
        return alive, start, detail
    finally:
        k32.CloseHandle(handle)


def _probe_posix(pid):
    try:
        os.kill(pid, 0)          # signal 0: existence check only (never used on Windows, where it would terminate)
        alive = True
    except ProcessLookupError:
        return False, None, "no such process"
    except PermissionError:
        alive = True             # exists, belongs to another user
    except OSError as exc:
        return None, None, f"kill(0) failed ({exc})"
    start = None
    detail = "running"
    if sys.platform.startswith("linux"):
        try:
            with open(f"/proc/{pid}/stat", encoding="ascii", errors="replace") as f:
                stat = f.read()
            fields = stat[stat.rindex(")") + 2:].split()   # fields after "(comm)": fields[0] is the state (field 3)
            if fields[0] == "Z":
                return False, None, "zombie"
            ticks = int(fields[19])                          # field 22: starttime in clock ticks since boot
            btime = None
            with open("/proc/stat", encoding="ascii", errors="replace") as f:
                for line in f:
                    if line.startswith("btime "):
                        btime = int(line.split()[1])
                        break
            if btime is not None:
                start = btime + ticks / os.sysconf("SC_CLK_TCK")
        except (OSError, ValueError, IndexError):
            detail = "running, start time unreadable"
    else:
        detail = "running, start time not available on this platform"
    return alive, start, detail


def probe_process(pid):
    """(alive, start, detail): alive is True / False / None (unknown); start is the creation time in epoch seconds
    or None when unavailable."""
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return None, None, "pid is not a number"
    if pid <= 0:
        return False, None, "pid is not a valid process id"
    try:
        if sys.platform == "win32":
            return _probe_windows(pid)
        return _probe_posix(pid)
    except Exception as exc:  # a probe failure must never break the lock; it only means "unknown"
        return None, None, f"probe failed ({exc.__class__.__name__}: {exc})"


def liveness(record):
    """('yes' | 'no' | 'unknown' | 'none', detail) for the holder of a record. 'none' = old-style lock.
    'yes' needs the pid running AND, when both are known, a matching start time."""
    pid = record.get("holder_pid")
    if pid is None:
        return "none", "old-style lock, no liveness fields"
    alive, start, detail = probe_process(pid)
    recorded = record.get("holder_start")
    if alive is None:
        return "unknown", f"pid {pid}: {detail}"
    if alive is False:
        return "no", f"pid {pid} not running ({detail})"
    if recorded is None:
        return "yes", f"pid {pid} running; start time not recorded, so pid reuse cannot be detected"
    if start is None:
        return "unknown", f"pid {pid} running; start time unreadable, cannot confirm it is the holder"
    if abs(float(recorded) - start) <= START_TOLERANCE_SECONDS:
        return "yes", f"pid {pid} running, start time matches ({iso(start)})"
    return "no", f"pid {pid} running but started {iso(start)}, holder started {iso(recorded)} (pid reused)"


# --------------------------------------------------------------------------------------------- FIFO queue (v1)

def _live_tickets(cfg):
    """Waiting tickets, oldest first. A ticket whose waiter stopped refreshing it is dead and is dropped."""
    out = []
    try:
        names = sorted(os.listdir(cfg.queue))
    except FileNotFoundError:
        os.makedirs(cfg.queue, exist_ok=True)
        return out
    for name in names:
        path = os.path.join(cfg.queue, name)
        try:
            if time.time() - os.path.getmtime(path) > TICKET_DEAD_SECONDS:
                os.remove(path)
                continue
        except OSError:
            continue
        out.append(name)
    return out


def _new_ticket(cfg, owner, what):
    os.makedirs(cfg.queue, exist_ok=True)
    ticket = os.path.join(cfg.queue, f"{time.time_ns():020d}-{owner}-{os.getpid()}")
    with open(ticket, "w", encoding="utf-8") as f:
        f.write(what)
    return ticket


# --------------------------------------------------------------------------------------------- stale rule

def evaluate(cfg, held, at=None):
    """Decide what the wait loop does about a held lock: ('stale', None) or ('wait', message-or-None).
    The message is the periodic stderr report (at most one per REPORT_EVERY_SECONDS)."""
    at = time.time() if at is None else at
    owner, since, what = held.get("owner"), held.get("since"), held.get("what")
    try:
        age = at - float(held.get("epoch", 0) or 0)
    except (TypeError, ValueError):
        age = float("inf")
    if held.get("holder_pid") is None:
        # old-style lock: exactly the v1 behaviour, never auto-released
        if age > cfg.stale_seconds:
            return "wait", (f"STALE lock held by {owner} since {since} ({what}); "
                            "report to the main session, do not delete")
        return "wait", None
    pid = held.get("holder_pid")
    state, detail = liveness(held)
    try:
        lease_left = float(held.get("lease_until") or 0) - at
    except (TypeError, ValueError):
        lease_left = float("-inf")
    if state == "no" and lease_left <= 0:
        if cfg.no_autorelease:
            return "wait", (f"auto-release disabled ({ENV_NO_AUTORELEASE}); stale lock held by {owner} since {since} "
                            f"({what}) kept: {detail}, lease expired {int(-lease_left)}s ago")
        return "stale", None
    if state == "no":
        return "wait", (f"holder of lock {owner} is not running ({detail}); lease expires in {int(lease_left)}s, "
                        "then the lock is auto-released")
    if lease_left <= 0:
        return "wait", (f"MISSED HEARTBEAT: lock held by {owner} since {since} ({what}); holder pid {pid} "
                        f"alive={state} ({detail}), lease expired {int(-lease_left)}s ago; not auto-released, "
                        "report to the main session")
    if age > cfg.stale_seconds:
        return "wait", (f"LONG-HELD lock held by {owner} since {since} ({what}); holder pid {pid} alive={state}, "
                        f"lease ok ({int(lease_left)}s left); not stale, still waiting")
    return "wait", None


def _rename_noreplace(src, dst):
    """Rename that fails with FileExistsError when dst exists, on every platform."""
    if sys.platform == "win32":
        os.rename(src, dst)      # Windows: always raises FileExistsError when dst exists
    else:
        os.link(src, dst)        # POSIX: link() is the no-replace primitive
        os.unlink(src)


def _auto_release(cfg, judged, ticket):
    """Remove a lock judged stale. The file is first moved aside and re-read, so a fresh lock written in the
    meantime is never destroyed by mistake; it is put back if that happens. Returns True when released."""
    live = _live_tickets(cfg)
    if not live or live[0] != os.path.basename(ticket):
        return False                           # we are no longer the oldest waiter (stalled); re-evaluate first
    aside = f"{cfg.lock}.stale-{os.getpid()}-{time.time_ns()}"
    for _ in range(10):
        try:
            os.replace(cfg.lock, aside)
            break
        except FileNotFoundError:
            return False                       # released or removed by someone else meanwhile
        except PermissionError:
            time.sleep(0.05)                   # a reader has it open (Windows sharing violation); retry
    else:
        return False
    try:
        with open(aside, encoding="utf-8") as f:
            taken = json.load(f)
    except (OSError, ValueError):
        taken = None
    if taken is None or not _same_record(taken, judged):
        try:
            _rename_noreplace(aside, cfg.lock)
        except FileExistsError:
            print(f"WARNING: a new lock appeared while a stale lock was being removed; the displaced record is kept "
                  f"at {aside}; report to the main session", file=sys.stderr, flush=True)
        except OSError:
            pass
        return False
    try:
        os.remove(aside)
    except OSError:
        pass
    _append_served(cfg, f"{now()} auto-released stale lock owner={judged.get('owner')} pid={judged.get('holder_pid')} "
                        f"since={judged.get('since')} reason={AUTO_RELEASE_REASON}")
    print(f"auto-released stale lock held by {judged.get('owner')} since {judged.get('since')} "
          f"(holder pid {judged.get('holder_pid')} not running, lease expired {iso(judged.get('lease_until'))}); "
          "continuing", file=sys.stderr, flush=True)
    return True


# --------------------------------------------------------------------------------------------- commands

def acquire(cfg, owner, what, holder_pid=None, lease_seconds=None):
    if holder_pid is not None:
        lease_seconds = DEFAULT_LEASE_SECONDS if lease_seconds is None else lease_seconds
        alive, start, detail = probe_process(holder_pid)
        if alive is False:
            print(f"warning: --pid {holder_pid} is not a running process ({detail}); the lock will be auto-released "
                  "once its lease expires", file=sys.stderr, flush=True)
        elif start is None:
            print(f"warning: start time of pid {holder_pid} is unavailable ({detail}); pid reuse cannot be detected",
                  file=sys.stderr, flush=True)
    elif lease_seconds is not None:
        print("note: --lease ignored without a holder pid (old-style lock, no liveness fields)",
              file=sys.stderr, flush=True)
        lease_seconds = None
    # First come, first served (REC-04 I-44): each waiter holds a ticket named by its arrival time and only the
    # oldest live ticket may take the lock, so a run of jobs from one project cannot starve another.
    ticket = _new_ticket(cfg, owner, what)
    last_report = 0.0
    try:
        while True:
            try:
                os.utime(ticket)
            except FileNotFoundError:
                # our ticket was dropped as dead (this process stalled > TICKET_DEAD_SECONDS): rejoin at the back
                ticket = _new_ticket(cfg, owner, what)
                print("ticket expired while waiting (stalled); re-queued at the back", file=sys.stderr, flush=True)
            live = _live_tickets(cfg)
            if live and live[0] != os.path.basename(ticket):
                time.sleep(cfg.poll)
                continue
            try:
                fd = os.open(cfg.lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            except FileExistsError:
                held = read(cfg) or {}
                action, message = evaluate(cfg, held)
                if action == "stale" and _auto_release(cfg, held, ticket):
                    continue                   # lock gone; the FIFO check runs again before we take it
                if message and time.time() - last_report > REPORT_EVERY_SECONDS:
                    print(message, file=sys.stderr, flush=True)
                    last_report = time.time()
                time.sleep(cfg.poll)
                continue
            break
    finally:
        try:
            os.remove(ticket)
        except OSError:
            pass
    epoch = time.time()
    record = {"owner": owner, "what": what, "since": now(), "epoch": epoch,
              "held_by": HELD_BY_NOTE,
              "acquirer_pid": os.getpid(),
              "acquirer_pid_note": ACQUIRER_PID_NOTE}
    if holder_pid is not None:
        _, start, _ = probe_process(holder_pid)
        lease = int(lease_seconds) if float(lease_seconds).is_integer() else lease_seconds
        record.update({"holder_pid": holder_pid, "holder_start": start,
                       "lease_until": epoch + lease_seconds, "lease_seconds": lease})
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(record, f)
    try:
        arrived = datetime.fromtimestamp(int(os.path.basename(ticket).split("-")[0]) / 1e9, timezone.utc)
        extra = (f"pid={holder_pid} lease={record['lease_seconds']}s " if holder_pid is not None else "")
        _append_served(cfg, f"{now()} served {owner} arrived={arrived.isoformat(timespec='seconds')} {extra}what={what}")
    except (OSError, ValueError):
        pass
    print(f"acquired by {owner}: {what}", flush=True)
    if holder_pid is not None:
        print(f"holder pid {holder_pid} (started {iso(record['holder_start'])}), lease {record['lease_seconds']}s "
              f"until {iso(record['lease_until'])}; run `heartbeat {owner}` well inside every lease",
              file=sys.stderr, flush=True)
    return 0


def release(cfg, owner):
    held = read(cfg)
    if held is None:
        print("no lock held", flush=True)
        return 0
    if held.get("owner") != owner:
        print(f"refused: lock is held by {held.get('owner')}, not {owner}", file=sys.stderr, flush=True)
        return 2
    for attempt in range(10):
        try:
            os.remove(cfg.lock)
            break
        except FileNotFoundError:
            break
        except PermissionError:
            if attempt == 9:
                print("release failed: lock file is busy (another process has it open); retry", file=sys.stderr,
                      flush=True)
                return 1
            time.sleep(0.05)
    print(f"released by {owner}", flush=True)
    return 0


def heartbeat(cfg, owner):
    """Renew the lease: lease_until = now + lease_seconds. The record is rewritten in place through the existing
    file (never re-created), so a heartbeat that races a release can never resurrect a released lock."""
    held = read(cfg)
    if held is None:
        print("refused: no lock held", file=sys.stderr, flush=True)
        return 2
    time.sleep(float(os.environ.get('HB_DELAY', '0')))   # REVIEW INJECTION: widen the read->open gap
    if held.get("owner") != owner:
        print(f"refused: lock is held by {held.get('owner')}, not {owner}", file=sys.stderr, flush=True)
        return 2
    if held.get("holder_pid") is None:
        print(f"heartbeat ignored: lock held by {owner} is old-style (no holder pid, no lease)",
              file=sys.stderr, flush=True)
        return 0
    try:
        lease_seconds = float(held.get("lease_seconds") or DEFAULT_LEASE_SECONDS)
    except (TypeError, ValueError):
        lease_seconds = float(DEFAULT_LEASE_SECONDS)
    held["lease_until"] = time.time() + lease_seconds
    data = json.dumps(held).encode("utf-8")
    try:
        # no O_CREAT: if the lock was released meanwhile this fails instead of re-creating the lock
        fd = os.open(cfg.lock, os.O_WRONLY | getattr(os, "O_BINARY", 0))
    except FileNotFoundError:
        print("refused: no lock held", file=sys.stderr, flush=True)
        return 2
    try:
        os.write(fd, data)
        os.ftruncate(fd, len(data))
    finally:
        os.close(fd)
    print(f"heartbeat by {owner}: lease extended to {iso(held['lease_until'])}", flush=True)
    return 0


def status(cfg):
    held = read(cfg)
    print(json.dumps(held), flush=True)
    if held is not None:
        if held.get("holder_pid") is None:
            print("holder pid: none (old-style lock, no liveness fields)", flush=True)
            print("lease: none", flush=True)
        else:
            state, detail = liveness(held)
            print(f"holder pid: {held.get('holder_pid')} alive={state} ({detail})", flush=True)
            try:
                lease_until = float(held.get("lease_until") or 0)
                left = int(lease_until - time.time())
                print(f"lease remaining: {left} s ({'until' if left >= 0 else 'expired at'} {iso(lease_until)}, "
                      f"lease_seconds={held.get('lease_seconds')})", flush=True)
            except (TypeError, ValueError):
                print("lease remaining: ? (lease_until unreadable)", flush=True)
    if os.path.isdir(cfg.queue):
        for name in sorted(os.listdir(cfg.queue)):
            try:
                t = datetime.fromtimestamp(int(name.split("-")[0]) / 1e9, timezone.utc)
                print(f"waiting {name.split('-', 1)[1]} arrived={t.isoformat(timespec='seconds')}", flush=True)
            except ValueError:
                pass
    try:
        with open(cfg.served_log, encoding="utf-8") as log:
            for line in log.readlines()[-5:]:
                print(line.rstrip(), flush=True)
    except OSError:
        pass
    return 0


# --------------------------------------------------------------------------------------------- CLI

def _parse_acquire(args):
    """acquire <owner> "<what>" [--pid N] [--lease SECONDS]; returns (owner, what, pid, lease) or an error string."""
    positional, pid, lease = [], None, None
    i = 0
    while i < len(args):
        arg = args[i]
        if arg.startswith("--pid") or arg.startswith("--lease"):
            name, _, value = arg.partition("=")
            if not value:
                i += 1
                if i >= len(args):
                    return f"{name} needs a value"
                value = args[i]
            if name == "--pid":
                pid = value
            elif name == "--lease":
                lease = value
            else:
                return f"unknown option {arg}"
        elif arg.startswith("--"):
            return f"unknown option {arg}"
        else:
            positional.append(arg)
        i += 1
    if len(positional) < 2:
        return "acquire needs <owner> and \"<what>\""
    if pid is None and os.environ.get(ENV_PID, "").strip():
        pid = os.environ[ENV_PID].strip()
    if pid is not None:
        try:
            pid = int(pid)
        except ValueError:
            return f"--pid must be an integer, got {pid!r}"
        if pid <= 0:
            return f"--pid must be positive, got {pid}"
    if lease is not None:
        try:
            lease = float(lease)
        except ValueError:
            return f"--lease must be a number of seconds, got {lease!r}"
        if lease <= 0:
            return f"--lease must be positive, got {lease}"
    return positional[0], positional[1], pid, lease


def main(argv, lock_name, default_dir, doc=None):
    cfg = Config(lock_name, default_dir)
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "status":
        return status(cfg)
    if cmd == "acquire":
        parsed = _parse_acquire(argv[2:])
        if isinstance(parsed, str):
            print(f"error: {parsed}", file=sys.stderr, flush=True)
            print(doc or __doc__, file=sys.stderr)
            return 64
        owner, what, pid, lease = parsed
        return acquire(cfg, owner, what, pid, lease)
    if cmd == "release" and len(argv) >= 3:
        return release(cfg, argv[2])
    if cmd == "heartbeat" and len(argv) >= 3:
        return heartbeat(cfg, argv[2])
    print(doc or __doc__, file=sys.stderr)
    return 64
