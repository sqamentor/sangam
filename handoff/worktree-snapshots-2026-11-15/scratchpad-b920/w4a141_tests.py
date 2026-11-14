import os, glob, subprocess, shutil
sp = os.path.dirname(os.path.abspath(__file__))
root = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
javac = r"D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8\bin\javac.exe"
out = os.path.join(sp, "jc-w4a141t")
shutil.rmtree(out, ignore_errors=True)
cp = [x for x in open(os.path.join(sp, "cp-w4a-srv.txt")).read().strip().split(";") if x]
cp = [os.path.join(root, "sangam-server", "target-w4a", "classes")] + cp

def run(name, extra, pattern):
    files = glob.glob(os.path.join(root, pattern), recursive=True)
    dest = os.path.join(out, name); os.makedirs(dest)
    argf = os.path.join(sp, "w4a141t-%s.args" % name)
    with open(argf, "w", encoding="utf-8") as fh:
        fh.write("-proc:none -nowarn -encoding UTF-8 -parameters --release 21\n")
        fh.write('-d "%s"\n' % dest.replace("\\", "/"))
        fh.write('-cp "%s"\n' % ";".join(extra + cp).replace("\\", "/"))
        for f in files:
            fh.write('"%s"\n' % f.replace("\\", "/"))
    p = subprocess.run([javac, "@" + argf], capture_output=True, text=True)
    errs = [l for l in (p.stdout + p.stderr).splitlines() if "error:" in l]
    print("%s: %d files, exit %d, %d errors" % (name, len(files), p.returncode, len(errs)))
    for l in errs[:30]:
        print("  " + l.split("\\java\\org\\sangam\\")[-1][:240])
    return dest

b = run("billing", [], "sangam-billing/src/test/java/**/*.java")
run("server", [b], "sangam-server/src/test/java/**/*.java")
