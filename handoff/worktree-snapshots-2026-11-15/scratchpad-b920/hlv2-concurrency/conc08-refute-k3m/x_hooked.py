"""Waiter X for the CONC-08 check: the UNMODIFIED copy of shared_lock.py, driven through its own main().

Two pure-delay hooks force one legal interleaving; neither changes a decision, an argument or a return value:
  hook 1 (_live_tickets, only when called from _auto_release): after the real snapshot is taken (X is the oldest
          ticket at that moment), X makes no progress and does not refresh its ticket until <ctrl>/GO1 exists.
          This stands in for X being suspended between shared_lock.py:334 and :340.
  hook 2 (_rename_noreplace): before the real put-back, X waits until <ctrl>/GO2 exists.
          This stands in for X being descheduled between shared_lock.py:340 and :355.
MODE "control" turns both hooks into plain pass-throughs.

argv: <code_dir> <lock_dir> <ctrl_dir> <race|control> acquire <owner> <what> [...]
"""
import importlib.util
import os
import sys
import time

CODE, LOCKDIR, CTRL, MODE = sys.argv[1:5]
REST = sys.argv[5:]
assert "ff9f2caf" not in os.path.abspath(LOCKDIR), "refusing to touch the live lock folder"
assert os.environ.get("SHARED_LOCK_DIR") == LOCKDIR, "SHARED_LOCK_DIR must point at the temp lock folder"

spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)


def log(msg):
    with open(os.path.join(CTRL, "x_hook.log"), "a", encoding="utf-8") as f:
        f.write(f"{time.time():.3f} {msg}\n")


def wait_for(name, timeout):
    path = os.path.join(CTRL, name)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.exists(path):
            return True
        time.sleep(0.05)
    return False


orig_live = sl._live_tickets
orig_rename = sl._rename_noreplace
state = {"stalled": False}


def live_hook(cfg):
    out = orig_live(cfg)
    caller = sys._getframe(1).f_code.co_name
    if MODE == "race" and caller == "_auto_release" and not state["stalled"]:
        state["stalled"] = True
        log(f"STALL-START snapshot inside _auto_release = {out}; X now makes no progress (ticket not refreshed)")
        ok = wait_for("GO1", 400)
        log(f"STALL-END GO1 seen={ok}; lock file exists={os.path.exists(cfg.lock)}; X continues to os.replace")
    return out


def rename_hook(src, dst):
    if MODE == "race":
        log(f"PUTBACK-PENDING displaced={os.path.basename(src)}; lock file exists={os.path.exists(dst)}; waiting GO2")
        ok = wait_for("GO2", 200)
        log(f"PUTBACK-NOW GO2 seen={ok}; lock file exists={os.path.exists(dst)}; calling the real _rename_noreplace")
    try:
        result = orig_rename(src, dst)
        log("PUTBACK-RESULT succeeded")
        return result
    except Exception as exc:
        log(f"PUTBACK-RESULT raised {exc.__class__.__name__}: {exc}")
        raise


sl._live_tickets = live_hook
sl._rename_noreplace = rename_hook
sys.exit(sl.main(["heavy_lock.py"] + REST, lock_name="heavy-build.lock", default_dir=LOCKDIR))
