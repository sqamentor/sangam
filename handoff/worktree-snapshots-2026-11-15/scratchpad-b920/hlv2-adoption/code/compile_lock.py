"""Shared COMPILE lock (code-complete builders, SD-438): one Maven compile at a time; separate from the heavy-build
lock for this machine (SD-361, REC-04 I-30) -- v2 wrapper (P-458, I-44/I-45/I-59).

Usage (PowerShell or bash), around every compile:
    python -I compile_lock.py acquire <owner> "<what>" [--pid N] [--lease SECONDS]
                                                      # waits until the lock is free, then takes it atomically (FIFO)
    python -I compile_lock.py heartbeat <owner>       # renews the lease (lease_until = now + lease_seconds)
    ... compile ...
    python -I compile_lock.py release <owner>         # only the owner can release
    python -I compile_lock.py status

--pid N (or env HEAVY_LOCK_PID) is the long-lived pid of the job that holds the lock, e.g. $PID in PowerShell or $$
in bash. With it the lock carries holder_pid, holder_start, lease_until and lease_seconds (default 1800) and a
waiter may remove the lock only when the holder pid is dead (or reused) AND the lease has expired. Without a pid the
lock is old-style and behaves exactly as v1: reported STALE on stderr after 3 hours, never removed automatically.

The lock is held by OWNER NAME until `release <owner>`, never by a process (REC-04 I-34). The recorded
acquirer_pid is the short-lived `acquire` process and exits at once; holder_pid is the job to judge liveness by.
Implementation: shared_lock.py next to this file (also used by heavy_lock.py). Lock file: compile.lock in this
folder, or in $SHARED_LOCK_DIR when set.
"""
import importlib.util
import os
import sys

LOCK_NAME = "compile.lock"
HERE = os.path.dirname(os.path.abspath(__file__))


def _shared_lock():
    sys.dont_write_bytecode = True   # no __pycache__ next to the live lock files
    path = os.path.join(HERE, "shared_lock.py")
    spec = importlib.util.spec_from_file_location("shared_lock", path)
    if spec is None or spec.loader is None or not os.path.isfile(path):
        print(f"{os.path.basename(__file__)}: shared_lock.py not found next to this script ({path})", file=sys.stderr)
        sys.exit(70)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


if __name__ == "__main__":
    sys.exit(_shared_lock().main(sys.argv, lock_name=LOCK_NAME, default_dir=HERE, doc=__doc__))
