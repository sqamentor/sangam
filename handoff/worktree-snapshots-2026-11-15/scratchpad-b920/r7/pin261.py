import json, re, sys, os
root, digests_path = sys.argv[1], sys.argv[2]
D = {r["ref"]: r["digest"] for r in json.load(open(digests_path, encoding="utf-8-sig"))}
for k, v in D.items():
    assert re.fullmatch(r"sha256:[0-9a-f]{64}", v), (k, v)
def pin(tag): return f"{tag}@{D[tag]}"

def rw(rel, fn):
    p = os.path.join(root, rel)
    raw = open(p, "rb").read().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    assert not (nl == "\n" and "\r" in raw), rel
    lines = raw.split(nl)
    lines = fn(lines, rel)
    out = nl.join(lines)
    open(p, "wb").write(out.encode("utf-8"))
    print("wrote", rel, "nl", repr(nl))

PIN_NOTE = "# Base images are pinned by index digest in the FROM lines (decisions 253, 261; lookup in DOWNLOADS-WAVE4.md)."

def dockerfile(keep):
    def fn(lines, rel):
        vars_ = {}
        out = []
        noted = False
        for ln in lines:
            m = re.match(r"^ARG (\w*IMAGE\w*)=(\S+)$", ln)
            if m and m.group(1) != "SERVER_IMAGE":
                var, tag = m.group(1), m.group(2)
                assert tag in D, (rel, tag)
                vars_[var] = tag
                if var in keep:
                    out.append(f"ARG {var}={pin(tag)}")
                elif not noted:
                    out.append(PIN_NOTE); noted = True
                continue
            out.append(ln)
        res = []
        for ln in out:
            m = re.match(r"^FROM \$\{(\w+)\}(.*)$", ln)
            if m and m.group(1) in vars_ and m.group(1) not in keep:
                ln = f"FROM {pin(vars_[m.group(1)])}{m.group(2)}"
            res.append(ln)
        left = [l for l in res if re.match(r"^FROM \$\{(\w*IMAGE\w*)\}", l) and re.match(r"^FROM \$\{(\w+)\}", l).group(1) not in keep and re.match(r"^FROM \$\{(\w+)\}", l).group(1) != "SERVER_IMAGE"]
        assert not left, (rel, left)
        return res
    return fn

def sub(old, new):
    def fn(lines, rel):
        txt = "\n".join(lines)
        assert txt.count(old) == 1, (rel, old, txt.count(old))
        return txt.replace(old, new).split("\n")
    return fn

def chain(*fns):
    def fn(lines, rel):
        for f in fns: lines = f(lines, rel)
        return lines
    return fn

srv_note = ("# JRE_IMAGE and DISTROLESS_IMAGE stay build args (decision 253) because the JDK 21 variant above overrides them;\n"
            "# their defaults are digest-pinned like every FROM (decision 261).")
rw("sangam/deploy/docker/server.Dockerfile", chain(
    sub("--build-arg JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble \\",
        "--build-arg JRE_IMAGE=" + pin("eclipse-temurin:21.0.8_9-jre-noble") + " \\"),
    sub("#       --build-arg DISTROLESS_IMAGE=gcr.io/distroless/java21-debian12:nonroot -t sangam-server:dev-jdk21 .",
        "#       --build-arg DISTROLESS_IMAGE=" + pin("gcr.io/distroless/java21-debian12:nonroot") + " \\\n#       -t sangam-server:dev-jdk21 ."),
    dockerfile({"JRE_IMAGE", "DISTROLESS_IMAGE"}),
    sub("ARG JRE_IMAGE=", srv_note + "\nARG JRE_IMAGE="),
))
basenote = ("# Base images: the server's Maven and JRE images (deploy/docker/server.Dockerfile); digests are pinned with the\n"
            "# other images (RM-33).")
newbase = ("# Base images: the server's Maven and JRE images (deploy/docker/server.Dockerfile), pinned by index digest in the\n"
           "# FROM lines (RM-33; decisions 253, 261).")
for f in ("ocr", "pdf-validator"):
    rw(f"sangam/deploy/docker/{f}.Dockerfile", chain(
        sub("# Base images: the server's MAVEN_IMAGE and JRE_IMAGE (deploy/docker/server.Dockerfile); digests are pinned with the\n# other images (RM-33).", newbase),
        dockerfile(set())))
rw("sangam/deploy/docker/script-worker.Dockerfile", chain(
    sub("# Base images: MAVEN_IMAGE and JRE_IMAGE are the server's (deploy/docker/server.Dockerfile). NODE_IMAGE is new to the\n# repository;",
        "# Base images: the Maven and JRE images are the server's (deploy/docker/server.Dockerfile). The Node image is new to\n# the repository;"),
    dockerfile(set())))
rw("sangam/deploy/operator/Dockerfile", chain(
    sub("# Same base-image arguments as deploy/docker/scale.Dockerfile; the release pipeline pins them by digest.",
        "# Same base images as deploy/docker/scale.Dockerfile, pinned by index digest in the FROM lines (decisions 253, 261)."),
    dockerfile(set())))
for f in ("scale", "simulators", "sdk-worker-native", "ui"):
    rw(f"sangam/deploy/docker/{f}.Dockerfile", dockerfile(set()))

def images(lines, rel):
    out = []
    for ln in lines:
        m = re.match(r"^(runtime|observability|devdb)(\s+)(\S+)(\s+)(# licence: .*)$", ln)
        if m:
            ref = m.group(3); assert ref in D, ref
            ln = f"{m.group(1)}{m.group(2)}{pin(ref)}  {m.group(5)}"
        out.append(ln)
    txt = "\n".join(out)
    old = '# ${SANGAM_VERSION} and ${SANGAM_REGISTRY} are substituted by save-images.sh / save-images.ps1.'
    assert txt.count(old) == 1
    txt = txt.replace(old, "# Third-party images are pinned as <name>:<tag>@sha256:<index digest> (decision 261; lookup in DOWNLOADS-WAVE4.md):\n"
                           "# save-images pulls by the digest, checks it, tags the image <name>:<tag> and saves it under that tag.\n" + old)
    return txt.split("\n")
rw("sangam/deploy/airgap/images.txt", images)
rw("sangam/deploy/README.md", chain(
    sub("--build-arg JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble\n", "--build-arg JRE_IMAGE=" + pin("eclipse-temurin:21.0.8_9-jre-noble") + "\n"),
    sub("--build-arg DISTROLESS_IMAGE=gcr.io/distroless/java21-debian12:nonroot\n", "--build-arg DISTROLESS_IMAGE=" + pin("gcr.io/distroless/java21-debian12:nonroot") + "\n")))
