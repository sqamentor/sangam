"""E3: Windows sharing semantics the race depends on. A file held open by another process through Python's
open() (what shared_lock.read() does at :99) makes os.remove (release, :460) and os.replace (_auto_release, :340)
fail with PermissionError, i.e. release enters its 50 ms retry loop (:464-469).

argv: scratch folder
"""
import os
import subprocess
import sys
import time

d = sys.argv[1]
os.makedirs(d, exist_ok=True)
p = os.path.join(d, "probe.lock")
with open(p, "w", encoding="utf-8") as f:
    f.write('{"owner": "A"}')
holder = subprocess.Popen([sys.executable, "-I", "-c",
                           "import sys,time\nf=open(sys.argv[1],encoding='utf-8')\nf.read()\nprint('open',flush=True)\ntime.sleep(3)", p],
                          stdout=subprocess.PIPE, text=True)
holder.stdout.readline()
for label, fn in (("os.remove", lambda: os.remove(p)), ("os.replace", lambda: os.replace(p, p + ".aside"))):
    try:
        fn()
        print(f"{label}: succeeded while another process had the file open for reading")
    except PermissionError as exc:
        print(f"{label}: PermissionError while another process had it open -> {exc}")
holder.wait()
os.remove(p)
print("os.remove after the reader closed: ok")
