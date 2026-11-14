import os, glob, subprocess, shutil, sys
sp = os.path.dirname(os.path.abspath(__file__))
root = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
jdk = r"D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
javac = os.path.join(jdk, "bin", "javac.exe")
out = os.path.join(sp, "jc-w4a-308")
shutil.rmtree(out, ignore_errors=True)
os.makedirs(out)
integ = os.path.join(root, "sangam-integration", sys.argv[1] if len(sys.argv) > 1 else "target-w4h", "classes")
cp = []
for x in open(os.path.join(sp, "cp-w4a-srv.txt")).read().strip().split(";"):
    x = x.strip()
    if not x or x in cp:
        continue
    if os.path.join("sangam-compliance", "target") in x:
        continue  # compiled from source below
    if x.startswith(os.path.join(root, "sangam-integration", "target")):
        x = integ
    cp.append(x)
if integ not in cp:
    cp.append(integ)
print("integration classes:", integ, os.path.isdir(integ), len(os.listdir(integ)) if os.path.isdir(integ) else 0)
files = glob.glob(os.path.join(root, "sangam-compliance", "src", "main", "java", "**", "*.java"), recursive=True)
files += glob.glob(os.path.join(root, "sangam-compliance", "src", "test", "java", "**", "*.java"), recursive=True)
argf = os.path.join(sp, "w4a-308.args")
with open(argf, "w", encoding="utf-8") as fh:
    fh.write("-proc:none -nowarn -encoding UTF-8 -parameters\n")
    fh.write('-d "%s"\n' % out.replace("\\", "/"))
    fh.write('-cp "%s"\n' % ";".join(cp).replace("\\", "/"))
    for f in files:
        fh.write('"%s"\n' % f.replace("\\", "/"))
p = subprocess.run([javac, "-Xmaxerrs", "200", "@" + argf], capture_output=True, text=True)
lines = (p.stdout + p.stderr).splitlines()
errs = [l for l in lines if "error:" in l]
print("sangam-compliance main+test: %d files, javac exit %d, %d error lines" % (len(files), p.returncode, len(errs)))
for l in lines[:40]:
    print("  " + l[-300:])
sys.exit(p.returncode)
