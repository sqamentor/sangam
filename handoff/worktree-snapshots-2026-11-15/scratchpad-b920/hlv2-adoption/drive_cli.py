"""Read-only review driver: exercises a COPY of heavy_lock v2 against temp lock folders only."""
import json, os, shutil, subprocess, sys, time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
PY = sys.executable
assert os.path.basename(BASE) == "hlv2-adoption", BASE
assert "ff9f2caf" not in HEAVY


def lockdir(tag):
    d = os.path.join(BASE, "locks", tag)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    assert d.startswith(BASE)
    return d


def env_for(d, **extra):
    e = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
    e["SHARED_LOCK_DIR"] = d
    e["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    e.update(extra)
    return e


def run(d, *args, timeout=20, env_extra=None, raw=False):
    r = subprocess.run([PY, "-I", HEAVY, *args], capture_output=True, env=env_for(d, **(env_extra or {})),
                       timeout=timeout, cwd=BASE)
    out = r.stdout.decode("utf-8", "replace")
    err = r.stderr.decode("utf-8", "replace")
    return r.returncode, out, err


def show(label, res):
    rc, out, err = res
    print(f"--- {label}: rc={rc}")
    for line in out.rstrip().splitlines():
        print(f"    OUT| {line}")
    for line in err.rstrip().splitlines()[:6]:
        print(f"    ERR| {line}")


def rec(d):
    try:
        with open(os.path.join(d, "heavy-build.lock"), encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return None


def sleeper(sec):
    return subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({sec})"])


def t_contract():
    print("=== T1 CLI contract")
    d = lockdir("t1")
    show("acquire (no args)", run(d, "acquire"))
    show("acquire --pid abc", run(d, "acquire", "X", "w", "--pid", "abc"))
    show("acquire --lease nan (no pid)", run(d, "acquire", "X", "w", "--lease", "nan"))
    show("heartbeat X on old-style", run(d, "heartbeat", "X"))
    show("release Y (non-owner)", run(d, "release", "Y"))
    show("release X", run(d, "release", "X"))
    show("release X again", run(d, "release", "X"))
    show("heartbeat X with no lock", run(d, "heartbeat", "X"))
    show("release (no owner)", run(d, "release"))
    s = sleeper(60)
    show("acquire X --pid live --lease 30", run(d, "acquire", "customwf-x", "job x", "--pid", str(s.pid), "--lease", "30"))
    print("    record:", rec(d))
    show("status (pid lock)", run(d, "status"))
    show("heartbeat customwf-x", run(d, "heartbeat", "customwf-x"))
    show("heartbeat other-owner", run(d, "heartbeat", "other-owner"))
    show("release customwf-x", run(d, "release", "customwf-x"))
    show("served log via status", run(d, "status"))
    s.kill(); s.wait()


def t_encoding():
    print("=== T2 non-cp1252 'what' with redirected stdout (as every detached runner does)")
    d = lockdir("t2")
    show("acquire what='RED → GREEN' (old-style)", run(d, "acquire", "customwf-enc", "RED → GREEN"))
    print("    lock file after a non-zero acquire:", rec(d))
    show("status", run(d, "status"))
    show("release customwf-enc", run(d, "release", "customwf-enc"))


def t_corrupt():
    print("=== T3 error handling")
    d = lockdir("t3")
    lock = os.path.join(d, "heavy-build.lock")
    for label, content in (("empty file (acquire died between O_EXCL and json.dump)", ""),
                           ("truncated JSON", '{"owner": "customwf-a", "what": "x", "since": "2026'),
                           ("JSON null", "null"),
                           ("JSON list", "[]")):
        with open(lock, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"## planted: {label}")
        show("status", run(d, "status"))
        show("release customwf-a", run(d, "release", "customwf-a"))
        print("    lock file still present:", os.path.exists(lock))
        r = subprocess.Popen([PY, "-I", HEAVY, "acquire", "W", "waiter"], env=env_for(d, SHARED_LOCK_STALE_SECONDS="1"),
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        time.sleep(2.0)
        alive = r.poll() is None
        r.kill(); out, err = r.communicate()
        print(f"    waiter after 2 s: {'STILL WAITING' if alive else 'exited rc=%s' % r.returncode}; stderr: "
              f"{err.decode('utf-8','replace').strip().splitlines()[:1]}")
        if os.path.exists(lock):
            show("release '?' (the placeholder owner)", run(d, "release", "?"))
        try:
            os.remove(lock)
        except OSError:
            pass
    print("## --lease inf / nan on a pid lock")
    for lease in ("inf", "nan"):
        d = lockdir(f"t3-{lease}")
        s = sleeper(60)
        show(f"acquire --lease {lease}", run(d, "acquire", "customwf-l", "lease test", "--pid", str(s.pid), "--lease", lease))
        print("    record:", rec(d))
        show("status", run(d, "status"))
        s.kill(); s.wait()
        r = subprocess.Popen([PY, "-I", HEAVY, "acquire", "W", "waiter"], env=env_for(d), stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE)
        try:
            r.wait(5)
        except subprocess.TimeoutExpired:
            pass
        alive = r.poll() is None
        if alive:
            r.kill()
        out, err = r.communicate()
        tail = err.decode('utf-8', 'replace').strip().splitlines()[-1:] if err else []
        print(f"    waiter vs dead holder: {'STILL WAITING' if alive else 'exited rc=%s' % r.returncode}; last stderr: {tail}")
        try:
            os.remove(os.path.join(d, "heavy-build.lock"))
        except OSError:
            pass


def t_lease_only():
    """Only the inf/nan part of t_corrupt, each in a fresh lock folder."""
    print("## --lease inf / nan on a pid lock")
    for lease in ("inf", "nan"):
        d = lockdir(f"t3-{lease}")
        s = sleeper(60)
        show(f"acquire --lease {lease}", run(d, "acquire", "customwf-l", "lease test", "--pid", str(s.pid), "--lease", lease))
        print("    record:", rec(d))
        show("status", run(d, "status"))
        s.kill(); s.wait()
        r = subprocess.Popen([PY, "-I", HEAVY, "acquire", "W", "waiter"], env=env_for(d), stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE)
        try:
            r.wait(5)
        except subprocess.TimeoutExpired:
            pass
        alive = r.poll() is None
        if alive:
            r.kill()
        out, err = r.communicate()
        tail = err.decode('utf-8', 'replace').strip().splitlines()[-1:] if err else []
        print(f"    waiter vs dead holder: {'STILL WAITING' if alive else 'exited rc=%s' % r.returncode}; last stderr: {tail}")


def t_encoding_full():
    d = lockdir("t2b")
    rc, out, err = run(d, "acquire", "customwf-enc", "RED → GREEN")
    print(f"acquire rc={rc}\n  stdout={out!r}\n  stderr tail:")
    for line in err.strip().splitlines()[-4:]:
        print("   ", line)
    print("  served log:", open(os.path.join(d, "heavy-build.lock.served.log"), encoding="utf-8").read().strip())
    rc, out, err = run(d, "status")
    print(f"status rc={rc}; stderr tail: {err.strip().splitlines()[-1:]}")
    print("  release:", run(d, "release", "customwf-enc")[:2])
    for i in range(5):
        run(d, "acquire", f"o{i}", "ascii job"); run(d, "release", f"o{i}")
        print(f"  after {i + 1} more grant(s): status rc={run(d, 'status')[0]}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    which = sys.argv[1:] or ["contract", "encoding", "corrupt"]
    for w in which:
        {"contract": t_contract, "encoding": t_encoding, "corrupt": t_corrupt, "lease": t_lease_only,
         "encfull": t_encoding_full}[w]()
