import os, re
S = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
HOSTS = ("sangam-server/", "sangam-spring-boot-starter/", "sangam-quarkus-extension/", "sangam-control-plane/", "sangam-gateway/", "sangam-rollup/",
         "sdks/java-spring-boot/", "sdks/python-frameworks/", "sdks/typescript-nestjs/")
REST_HOST = ("sangam-rest/src/main/java/org/sangam/rest/web/", "sangam-rest/src/main/java/org/sangam/rest/modules/", "sangam-rest/src/main/java/org/sangam/rest/config/",
             "sangam-rest/src/main/java/org/sangam/rest/compliance/ComplianceController.java", "sangam-rest/src/main/java/org/sangam/rest/compliance/ComplianceProblemAdvice.java",
             "sangam-rest/src/main/java/org/sangam/rest/lifecycle/DeprecationHeadersFilter.java")
BAN = re.compile(r"\b(org\.springframework|io\.quarkus|io\.micronaut|io\.helidon|jakarta\.enterprise|jakarta\.inject|javax\.inject|com\.google\.inject|io\.dropwizard|jakarta\.ws\.rs|javax\.ws\.rs|org\.glassfish\.jersey)\.")
hits = []
for root, dirs, files in os.walk(S):
    dirs[:] = [d for d in dirs if not d.startswith("target") and d not in ("node_modules", ".git")]
    for f in files:
        if not f.endswith(".java"):
            continue
        p = os.path.join(root, f)
        rel = os.path.relpath(p, S).replace("\\", "/")
        if "/src/main/" not in rel or rel.startswith(HOSTS) or rel.startswith(REST_HOST):
            continue
        if BAN.search(open(p, encoding="utf-8", errors="replace").read()):
            hits.append(rel)
print(len(hits))
for h in sorted(hits):
    print(h)
