import os, glob, subprocess, shutil
sp = os.path.dirname(os.path.abspath(__file__))
root = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
jdk = r"D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
javac = os.path.join(jdk, "bin", "javac.exe")
out = os.path.join(sp, "jc-w4a-r2")
shutil.rmtree(out, ignore_errors=True)
os.makedirs(out)
cp = [os.path.join(root, "sangam-server", "target-w4a", "classes")]
for x in open(os.path.join(sp, "cp-w4a-srv.txt")).read().strip().split(";"):
    x = x.strip()
    if x and x not in cp:
        cp.append(x)
files = glob.glob(os.path.join(root, "sangam-server", "src", "test", "java", "**", "*.java"), recursive=True)
argf = os.path.join(sp, "w4a-r2-srvt.args")
with open(argf, "w", encoding="utf-8") as fh:
    fh.write("-proc:none -nowarn -encoding UTF-8 -parameters\n")
    fh.write('-d "%s"\n' % out.replace("\\", "/"))
    fh.write('-cp "%s"\n' % ";".join(cp).replace("\\", "/"))
    for f in files:
        fh.write('"%s"\n' % f.replace("\\", "/"))
p = subprocess.run([javac, "-Xmaxerrs", "2000", "@" + argf], capture_output=True, text=True)
lines = (p.stdout + p.stderr).splitlines()
errs = [l for l in lines if "error:" in l]
mine = ("UnverifiedPaymentVendorIT", "BillingPaymentPolicyWiringTest", "BillingWiringTest", "UnverifiedPaymentAuthorityBridge")
print("server tests: %d files, javac exit %d, %d error lines" % (len(files), p.returncode, len(errs)))
files_with_errors = sorted(set(l.split(".java:")[0].rsplit("\\", 1)[-1].rsplit("/", 1)[-1] for l in errs))
print("files with errors:", files_with_errors)
for l in errs:
    if any(m in l for m in mine):
        print("  MINE? " + l[-300:])
for l in errs[:25]:
    print("  " + l[-300:])
