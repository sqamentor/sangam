"""AD-03 likelihood probe: after a pid is freed, how often is it re-occupied by a process that the v2 probe cannot
open (probe_process -> alive None -> liveness 'unknown')? Watches natural process exits plus N artificial frees for
DURATION seconds with a handle-free NtQuerySystemInformation snapshot every second. No lock files are touched.

    python reuse_watch.py [DURATION_SECONDS=600] [ARTIFICIAL_FREES=100]
"""
import importlib.util
import json
import os
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sl = load("shared_lock_copy", os.path.join(BASE, "code", "shared_lock.py"))
nt = load("ntproc", os.path.join(BASE, "ntproc.py"))

DURATION = float(sys.argv[1]) if len(sys.argv) > 1 else 600.0
ARTIFICIAL = int(sys.argv[2]) if len(sys.argv) > 2 else 100
LOG = os.path.join(BASE, "reuse_watch.log")
OUT = os.path.join(BASE, "reuse_watch.json")
CREATE_NO_WINDOW = 0x08000000


def log(msg):
    line = f"{time.strftime('%H:%M:%S')} {msg}"
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


events = []          # free events: dict(pid, free_t, orig_create, orig_image, kind, occ=[...])
latest = {}          # pid -> index of latest free event
probe_cache = {}     # (pid, create) -> (alive, detail)


def add_free(pid, free_t, orig_create, orig_image, kind):
    latest[pid] = len(events)
    events.append({"pid": pid, "free_t": free_t, "orig_create": orig_create, "orig_image": orig_image,
                   "kind": kind, "occ": []})


open(LOG, "w").close()
t0 = time.time()
prev = nt.nt_table()
log(f"start: {len(prev)} processes; artificial frees={ARTIFICIAL}; duration={DURATION}s")
for _ in range(ARTIFICIAL):
    p = subprocess.Popen(["cmd.exe", "/d", "/c", "exit 0"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, creationflags=CREATE_NO_WINDOW)
    pid = p.pid
    p.wait()
    p._handle.Close()   # drop our handle so the pid is really free
    add_free(pid, time.time(), None, "cmd.exe (artificial)", "artificial")
log(f"artificial frees done: {len({e['pid'] for e in events})} distinct pids")

end = t0 + DURATION
last_progress = time.time()
while time.time() < end:
    time.sleep(1.0)
    cur = nt.nt_table()
    now = time.time()
    for pid, (create, image, session) in prev.items():
        if pid in (0, 4):
            continue
        c = cur.get(pid)
        if c is None or c[0] != create:
            add_free(pid, now, create, image, "natural")
    for pid, idx in latest.items():
        c = cur.get(pid)
        if c is None:
            continue
        create, image, session = c
        ev = events[idx]
        if ev["orig_create"] is not None and create == ev["orig_create"]:
            continue
        if create is not None and create < ev["free_t"] - 3.0:
            continue        # an occupant older than the free event cannot be a reuse (artificial-free bookkeeping)
        key = (create, image)
        occ = next((o for o in ev["occ"] if (o["create"], o["image"]) == key), None)
        if occ is None:
            ck = (pid, create)
            if ck not in probe_cache:
                alive, start, detail = sl.probe_process(pid)
                state, ldetail = sl.liveness({"holder_pid": pid, "holder_start": (ev["orig_create"] or ev["free_t"] - 1.0)})
                probe_cache[ck] = (alive, detail, state)
            alive, detail, state = probe_cache[ck]
            occ = {"create": create, "image": image, "session": session, "first": now, "last": now,
                   "probe_alive": alive, "probe_detail": detail, "v2_liveness": state}
            ev["occ"].append(occ)
            if alive is None:
                log(f"INACCESSIBLE reuse: pid {pid} freed {now - ev['free_t']:.0f}s ago (was {ev['orig_image']}) -> "
                    f"{image} session {session}; v2 liveness={state} ({detail})")
        else:
            occ["last"] = now
    prev = cur
    if now - last_progress >= 60:
        reused = sum(1 for e in events if e["occ"])
        inacc = sum(1 for e in events if any(o["probe_alive"] is None for o in e["occ"]))
        log(f"t+{now - t0:.0f}s: free events {len(events)}, reused by a process {reused}, "
            f"reused by an inaccessible process {inacc}")
        last_progress = now

final = prev
t_end = time.time()


def occupant_at(ev, t):
    for o in ev["occ"]:
        if o["first"] - 1.5 <= t <= o["last"] + 0.5:
            return o
    return None


summary = {"duration_s": round(t_end - t0), "free_events": len(events),
           "artificial": sum(1 for e in events if e["kind"] == "artificial"),
           "natural": sum(1 for e in events if e["kind"] == "natural")}
for horizon in (60, 300, 600, 900, 1800):
    eligible = [e for e in events if e["free_t"] + horizon <= t_end]
    occ_any = [e for e in eligible if occupant_at(e, e["free_t"] + horizon)]
    occ_inacc = [e for e in eligible if (lambda o: o is not None and o["probe_alive"] is None)(occupant_at(e, e["free_t"] + horizon))]
    summary[f"at_free+{horizon}s"] = {"eligible": len(eligible), "occupied_by_process": len(occ_any),
                                      "occupied_by_inaccessible_process": len(occ_inacc)}
summary["reused_by_process_within_window"] = sum(1 for e in events if e["occ"])
summary["reused_by_inaccessible_within_window"] = sum(1 for e in events if any(o["probe_alive"] is None for o in e["occ"]))
summary["inaccessible_reusers"] = [
    {"pid": e["pid"], "was": e["orig_image"], "kind": e["kind"], "reuser": o["image"], "session": o["session"],
     "after_free_s": round(o["first"] - e["free_t"]), "observed_lifetime_s": round(o["last"] - o["first"]),
     "still_alive_at_end": final.get(e["pid"], (None,))[0] == o["create"], "v2_liveness": o["v2_liveness"]}
    for e in events for o in e["occ"] if o["probe_alive"] is None]
times = sorted(o["first"] - e["free_t"] for e in events for o in e["occ"][:1])
summary["first_reuse_delay_s_quartiles"] = ([round(times[int(q * (len(times) - 1))]) for q in (0, .25, .5, .75, 1)]
                                            if times else [])
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(summary, f, indent=1)
log("SUMMARY " + json.dumps(summary))
