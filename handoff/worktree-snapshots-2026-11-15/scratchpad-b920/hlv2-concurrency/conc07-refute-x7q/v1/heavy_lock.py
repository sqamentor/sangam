"""Shared heavy-build lock for this machine (SD-361, REC-04 I-30).

Usage (PowerShell or bash), around every heavy step (Maven verify/lanes, Testcontainers, demo stack, docker scans):
    python heavy_lock.py acquire <owner> "<what>"   # waits until the lock is free, then takes it atomically
    ... heavy step ...
    python heavy_lock.py release <owner>            # only the owner can release
    python heavy_lock.py status

A lock older than 3 hours is reported as STALE on stderr every 5 minutes while waiting; it is never deleted
automatically. Only the main session removes a stale lock after checking that its owner is no longer running.

The lock is held by OWNER NAME until `release <owner>`, never by a process (REC-04 I-34). The recorded
acquirer_pid is the short-lived `acquire` process and exits at once; it says nothing about whether the
heavy step is still running. To judge a lock, check the owner's Maven/forked JVMs and containers.
"""
import json
import os
import sys
import time
from datetime import datetime, timezone

LOCK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "heavy-build.lock")
STALE_SECONDS = 3 * 3600
QUEUE = LOCK + ".queue"
TICKET_DEAD_SECONDS = 90


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def read():
    try:
        with open(LOCK, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None
    except (ValueError, OSError):
        return {"owner": "?", "what": "unreadable lock file", "since": "?", "epoch": 0}


def _live_tickets():
    """Waiting tickets, oldest first. A ticket whose waiter stopped refreshing it is dead and is dropped."""
    out = []
    for name in sorted(os.listdir(QUEUE)):
        path = os.path.join(QUEUE, name)
        try:
            if time.time() - os.path.getmtime(path) > TICKET_DEAD_SECONDS:
                os.remove(path)
                continue
        except OSError:
            continue
        out.append(name)
    return out


def acquire(owner, what):
    # First come, first served (REC-04 I-44): each waiter holds a ticket named by its arrival time and only the
    # oldest live ticket may take the lock, so a run of jobs from one project cannot starve another.
    os.makedirs(QUEUE, exist_ok=True)
    ticket = os.path.join(QUEUE, f"{time.time_ns():020d}-{owner}-{os.getpid()}")
    with open(ticket, "w", encoding="utf-8") as f:
        f.write(what)
    last_report = 0.0
    try:
        while True:
            os.utime(ticket)
            live = _live_tickets()
            if live and live[0] != os.path.basename(ticket):
                time.sleep(5)
                continue
            try:
                fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            except FileExistsError:
                held = read() or {}
                age = time.time() - float(held.get("epoch", 0) or 0)
                if age > STALE_SECONDS and time.time() - last_report > 300:
                    print(f"STALE lock held by {held.get('owner')} since {held.get('since')} ({held.get('what')}); "
                          "report to the main session, do not delete", file=sys.stderr, flush=True)
                    last_report = time.time()
                time.sleep(5)
                continue
            break
    finally:
        try:
            os.remove(ticket)
        except OSError:
            pass
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({"owner": owner, "what": what, "since": now(), "epoch": time.time(),
                   "held_by": "owner name until release (not a pid)",
                   "acquirer_pid": os.getpid(),
                   "acquirer_pid_note": "short-lived acquire process; exits at once (I-34)"}, f)
    try:
        arrived = datetime.fromtimestamp(int(os.path.basename(ticket).split("-")[0]) / 1e9, timezone.utc)
        with open(LOCK + ".served.log", "a", encoding="utf-8") as log:
            log.write(f"{now()} served {owner} arrived={arrived.isoformat(timespec='seconds')} what={what}\n")
    except (OSError, ValueError):
        pass
    print(f"acquired by {owner}: {what}", flush=True)
    return 0


def release(owner):
    held = read()
    if held is None:
        print("no lock held", flush=True)
        return 0
    if held.get("owner") != owner:
        print(f"refused: lock is held by {held.get('owner')}, not {owner}", file=sys.stderr, flush=True)
        return 2
    os.remove(LOCK)
    print(f"released by {owner}", flush=True)
    return 0


def main(argv):
    if len(argv) >= 2 and argv[1] == "status":
        print(json.dumps(read()), flush=True)
        if os.path.isdir(QUEUE):
            for name in sorted(os.listdir(QUEUE)):
                try:
                    t = datetime.fromtimestamp(int(name.split("-")[0]) / 1e9, timezone.utc)
                    print(f"waiting {name.split('-', 1)[1]} arrived={t.isoformat(timespec='seconds')}", flush=True)
                except ValueError:
                    pass
        try:
            with open(LOCK + ".served.log", encoding="utf-8") as log:
                for line in log.readlines()[-5:]:
                    print(line.rstrip(), flush=True)
        except OSError:
            pass
        return 0
    if len(argv) >= 4 and argv[1] == "acquire":
        return acquire(argv[2], argv[3])
    if len(argv) >= 3 and argv[1] == "release":
        return release(argv[2])
    print(__doc__, file=sys.stderr)
    return 64


if __name__ == "__main__":
    sys.exit(main(sys.argv))
