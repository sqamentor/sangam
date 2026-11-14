"""HLV2-AD-06 refutation driver. Exercises a COPY of heavy_lock v2 (./code) against temp lock folders (./locks/<tag>)
only. Never touches the live lock folder (ff9f2caf scratchpad) or the live v2 folder."""
import json, os, shutil, subprocess, sys, time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
PY = sys.executable
assert os.path.basename(BASE) == "hlv2-refute-ad06", BASE
assert "ff9f2caf" not in BASE.lower() and "ff9f2caf" not in HEAVY.lower()
ARROW = "RED → GREEN"                 # U+2192 is not in cp1252
DEVA = "परीक्षण"   # Devanagari text, not in cp1252
CP1252_NONASCII = "RED — GREEN café"         # em dash and e-acute ARE in cp1252


def lockdir(tag):
    d = os.path.join(BASE, "locks", tag)
    assert os.path.abspath(d).startswith(os.path.join(BASE, "locks") + os.sep), d
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def env_for(d, **extra):
    e = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
    e["SHARED_LOCK_DIR"] = d
    e["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    e.update(extra)
    assert e["SHARED_LOCK_DIR"].startswith(os.path.join(BASE, "locks"))
    return e


def run(d, *args, flags=("-I",), stdout_file=None, env_extra=None, timeout=30):
    env = env_for(d, **(env_extra or {}))
    cmd = [PY, *flags, HEAVY, *args]
    if stdout_file:
        with open(stdout_file, "wb") as f:
            r = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, env=env, timeout=timeout, cwd=BASE)
        with open(stdout_file, "rb") as f:
            out = f.read()
    else:
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=timeout, cwd=BASE)
        out = r.stdout
    return r.returncode, out, r.stderr


def show(label, res):
    rc, out, err = res
    print(f"--- {label}: rc={rc}")
    print(f"    stdout bytes: {out!r}")
    lines = err.decode("utf-8", "backslashreplace").strip().splitlines()
    for line in lines[-4:]:
        print(f"    ERR| {line}")


def lockfile(d):
    p = os.path.join(d, "heavy-build.lock")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def served(d):
    p = os.path.join(d, "heavy-build.lock.served.log")
    if not os.path.exists(p):
        return []
    with open(p, encoding="utf-8") as f:
        return [l.rstrip("\n") for l in f]


def queue(d):
    q = os.path.join(d, "heavy-build.lock.queue")
    return sorted(os.listdir(q)) if os.path.isdir(q) else []


def sleeper(sec):
    return subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({sec})"])


def t_basic():
    print("=== T1 old-style, what with U+2192, stdout = PIPE, python -I")
    d = lockdir("t1")
    show("acquire customwf-enc 'RED -> GREEN (U+2192)'", run(d, "acquire", "customwf-enc", ARROW))
    rec = lockfile(d)
    print("    lock file present after rc!=0:", rec is not None, "| owner:", rec and rec.get("owner"),
          "| what:", ascii(rec and rec.get("what")))
    print("    served log:", [ascii(l) for l in served(d)])
    print("    queue left:", queue(d))

    print("=== T2 same, stdout redirected to a FILE")
    d = lockdir("t2")
    show("acquire (stdout -> file)", run(d, "acquire", "customwf-enc", ARROW,
                                         stdout_file=os.path.join(BASE, "locks", "t2.out")))
    print("    lock file present:", lockfile(d) is not None)

    print("=== T3 control: ASCII what")
    d = lockdir("t3")
    show("acquire ascii", run(d, "acquire", "customwf-enc", "RED -> GREEN"))

    print("=== T4 control: non-ASCII but cp1252-encodable what (em dash, e-acute)")
    d = lockdir("t4")
    show("acquire cp1252 non-ascii", run(d, "acquire", "customwf-enc", CP1252_NONASCII))

    print("=== T5 Devanagari what")
    d = lockdir("t5")
    show("acquire devanagari", run(d, "acquire", "customwf-enc", DEVA))
    print("    lock file present:", lockfile(d) is not None)

    print("=== T6 non-cp1252 OWNER (customwf-λ), ASCII what; then release by that owner")
    d = lockdir("t6")
    show("acquire owner lambda", run(d, "acquire", "customwf-λ", "ascii job"))
    print("    lock file present:", lockfile(d) is not None)
    show("release owner lambda", run(d, "release", "customwf-λ"))
    print("    lock file present after release rc:", lockfile(d) is not None)

    print("=== T7 controls: no -I (PYTHONIOENCODING from env honoured) and -I -X utf8")
    print("    env PYTHONIOENCODING =", os.environ.get("PYTHONIOENCODING"))
    d = lockdir("t7a")
    show("acquire WITHOUT -I", run(d, "acquire", "customwf-enc", ARROW, flags=()))
    d = lockdir("t7b")
    show("acquire -I -X utf8", run(d, "acquire", "customwf-enc", ARROW, flags=("-I", "-X", "utf8")))


def t_pid_lease():
    print("=== T8 pid lock: rc=1 acquire, holder then dies; how long does a waiter wait?")
    d = lockdir("t8")
    s = sleeper(120)
    res = run(d, "acquire", "customwf-enc", ARROW, "--pid", str(s.pid), "--lease", "4")
    show("acquire --pid sleeper --lease 4", res)
    rec = lockfile(d)
    print("    record:", {k: rec.get(k) for k in ("owner", "holder_pid", "lease_seconds")} if rec else None)
    s.kill(); s.wait()
    t0 = time.time()
    w = subprocess.run([PY, "-I", HEAVY, "acquire", "W", "next job"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       env=env_for(d), timeout=60, cwd=BASE)
    t1 = time.time()
    print(f"    waiter rc={w.returncode} after {t1 - t0:.1f}s; grant-to-free {t1 - rec['epoch']:.1f}s (lease 4s)")
    print(f"    waiter stdout: {w.stdout!r}")
    print(f"    waiter stderr: {w.stderr.decode('utf-8', 'replace').strip().splitlines()[:2]}")
    print("    served log:", [ascii(l) for l in served(d)])

    print("=== T9 old-style lock left by the rc=1 acquire: does a waiter ever get it?")
    d = lockdir("t9")
    show("acquire old-style", run(d, "acquire", "customwf-enc", ARROW))
    w = subprocess.Popen([PY, "-I", HEAVY, "acquire", "W", "next job"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         env=env_for(d, SHARED_LOCK_STALE_SECONDS="1"), cwd=BASE)
    time.sleep(4)
    alive = w.poll() is None
    w.kill(); out, err = w.communicate()
    print(f"    waiter after 4s: {'STILL WAITING' if alive else 'exited rc=%s' % w.returncode}; stderr: "
          f"{err.decode('utf-8', 'replace').strip().splitlines()[:1]}")


def t_status():
    print("=== T10 status after the rc=1 grant, and after N further ASCII grants")
    d = lockdir("t10")
    run(d, "acquire", "customwf-enc", ARROW)
    rc, out, err = run(d, "status")
    print(f"    status while held: rc={rc}; first stdout line: {out.splitlines()[:1]}; "
          f"err tail: {err.decode('utf-8', 'replace').strip().splitlines()[-1:]}")
    print("    release customwf-enc:", run(d, "release", "customwf-enc")[:2])
    rc, out, err = run(d, "status")
    print(f"    status after release: rc={rc}; first stdout line: {out.splitlines()[:1]}")
    for i in range(6):
        run(d, "acquire", f"o{i}", "ascii job"); run(d, "release", f"o{i}")
        print(f"    after {i + 1} further grant(s): status rc={run(d, 'status')[0]}")


def t_runner():
    print("=== T11 README PowerShell runner recipe (pwsh -File, output captured as an agent tool does)")
    d = lockdir("t11")
    ps1 = os.path.join(BASE, "runner_readme.ps1")
    r = subprocess.run(["pwsh", "-NoProfile", "-NonInteractive", "-File", ps1, "-Lock", HEAVY, "-What", ARROW],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env_for(d), timeout=60, cwd=BASE)
    print(f"    runner exit={r.returncode}")
    print(f"    runner stdout: {r.stdout.decode('utf-8', 'replace').strip().splitlines()[-3:]}")
    print(f"    runner stderr tail: {r.stderr.decode('utf-8', 'replace').strip().splitlines()[-3:]}")
    rec = lockfile(d)
    print("    lock left behind:", {k: rec.get(k) for k in ("owner", "what", "holder_pid", "lease_seconds")} if rec else None)
    marker = os.path.join(d, "heavy-step-ran.txt")
    print("    heavy step ran:", os.path.exists(marker), "| release ran:", os.path.exists(os.path.join(d, "release-ran.txt")))
    rc, out, err = run(d, "status")
    print(f"    status rc={rc}; lines: {[l for l in out.decode('cp1252', 'replace').splitlines()[1:3]]}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    which = sys.argv[1:] or ["basic", "pid", "status", "runner"]
    for w in which:
        {"basic": t_basic, "pid": t_pid_lease, "status": t_status, "runner": t_runner}[w]()
