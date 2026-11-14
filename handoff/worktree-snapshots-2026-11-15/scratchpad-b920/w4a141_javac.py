import os, glob, subprocess, shutil, sys
sp = os.path.dirname(os.path.abspath(__file__))
root = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
jdk = r"D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
javac = os.path.join(jdk, "bin", "javac.exe")
out = os.path.join(sp, "jc-w4a141")
shutil.rmtree(out, ignore_errors=True)
for d in ("rest", "srv", "srvt"):
    os.makedirs(os.path.join(out, d))

entries, seen = [], set()
for d in sorted(glob.glob(os.path.join(root, "sangam-*", "target-w4a", "classes"))):
    if "sangam-server" in d or "sangam-rest" in d:
        continue
    entries.append(d); seen.add(d)
for d in sorted(glob.glob(os.path.join(root, "sangam-*", "target-w4a", "test-classes"))):
    if "sangam-server" in d or "sangam-rest" in d:
        continue
    entries.append(d); seen.add(d)
for f in glob.glob(os.path.join(sp, "cp-sangam-*.txt")) + [os.path.join(sp, "integration-test-cp.txt")]:
    for x in open(f).read().strip().split(";"):
        x = x.strip()
        if not x or x in seen:
            continue
        norm = x.replace("/", "\\").lower()
        if "\\org\\sangam\\" in norm or "\\target" in norm:
            continue
        seen.add(x); entries.append(x)

def srcs(*patterns):
    r = []
    for p in patterns:
        r += glob.glob(os.path.join(root, p), recursive=True)
    return r

def run(name, cp, files, dest):
    argf = os.path.join(sp, "w4a141-%s.args" % name)
    with open(argf, "w", encoding="utf-8") as fh:
        fh.write("-proc:none -nowarn -encoding UTF-8 -parameters\n")
        fh.write('-d "%s"\n' % dest.replace("\\", "/"))
        fh.write('-cp "%s"\n' % ";".join(cp).replace("\\", "/"))
        for f in files:
            fh.write('"%s"\n' % f.replace("\\", "/"))
    p = subprocess.run([javac, "@" + argf], capture_output=True, text=True)
    errs = [l for l in (p.stdout + p.stderr).splitlines() if "error:" in l]
    print("%s: %d files, exit %d, %d errors" % (name, len(files), p.returncode, len(errs)))
    for l in errs[:40]:
        print("  " + l[-260:])
    return p.returncode

rest_out = os.path.join(out, "rest")
rc1 = run("rest", entries, srcs("sangam-rest/src/main/java/**/*.java", "sangam-backup/src/main/java/**/*.java", "sangam-kube/src/main/java/**/*.java"), rest_out)
srv_out = os.path.join(out, "srv")
rc2 = run("srv", [rest_out] + entries, srcs("sangam-server/src/main/java/**/*.java"), srv_out)
rc3 = run("srvt", [srv_out, rest_out] + entries, srcs("sangam-server/src/test/java/**/*.java"), os.path.join(out, "srvt"))
print("RC", rc1, rc2, rc3)
