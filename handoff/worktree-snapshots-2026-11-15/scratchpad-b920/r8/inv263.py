import csv, hashlib, io, os, sys, zipfile
inv, ts, log_sha = sys.argv[1], sys.argv[2], sys.argv[3]
M2 = os.path.join(os.path.expanduser("~"), ".m2", "repository")
raw = open(inv, "rb").read()
assert b"\r\n" in raw
rows = list(csv.reader(io.StringIO(raw.decode("utf-8"), newline="")))
head, body = rows[0], rows[1:]
ix = {k: i for i, k in enumerate(head)}
gavs = [r[0] for r in body]
for g in ("io.github.x-stream:mxparser:1.2.2", "xmlpull:xmlpull:1.1.3.1"):
    assert g not in gavs, g
assert not any(g.startswith("xpp3:") for g in gavs)


def jarinfo(rel):
    p = os.path.join(M2, *rel.split("/"))
    sha = hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
    z = zipfile.ZipFile(p)
    mf = z.getinfo("META-INF/MANIFEST.MF").date_time
    lic = [n for n in z.namelist() if n.upper().startswith("META-INF/") and ("LICENSE" in n.upper() or "NOTICE" in n.upper())]
    return sha, "%04d-%02d-%02d" % mf[:3], lic


tree = ("dependency:tree offline %s sangam-connectors-health -> %s compile (optional) (log SHA-256 %s)")
sha, d, lic = jarinfo("io/github/x-stream/mxparser/1.2.2/mxparser-1.2.2.jar")
assert sha == "AEEEE23A3303D811BCA8790EA7F25B534314861C03CFF36DAFDCC2180969EB97" and lic == ["META-INF/LICENSE"], (sha, lic)
mx = ["io.github.x-stream:mxparser:1.2.2", sha, "Central", "use=runtime; maven=compile(optional)", "sangam-connectors-health",
      "Indiana University Extreme! Lab Software License", "LicenseRef-IU-Extreme-Lab",
      "YES (META-INF/LICENSE; IU acknowledgement)",
      "LicenseRef-IU-Extreme-Lab: copyright and licence in the documentation; IU acknowledgement in the documentation; no endorsement; no 'Indiana University' in a product name",
      "ACCEPTED FOR DEFINED USE",
      "owner decision 263 (2026-11-13, replaces decision 226): replaces xpp3:xpp3_min 1.1.4c on the HAPI FHIR validation path; optional dependency of sangam-connectors-health only; the jar carries no NOTICE, so attributions/mxparser-1.2.2.txt carries the acknowledgement, copyright and licence into the distribution NOTICE (notice_bundle.py); licence text texts/LicenseRef-IU-Extreme-Lab.txt is byte-identical to the jar META-INF/LICENSE (SHA-256 79F77E2E...)",
      "~/.m2/repository/io/github/x-stream/mxparser/1.2.2/mxparser-1.2.2.jar; SHA-256 as in DOWNLOADS-WAVE4.md (W4-K); licence from POM io.github.x-stream:mxparser:1.2.2 and jar META-INF/LICENSE (Version 1.2); release signal: MANIFEST.MF zip entry date %s; %s" % (d, tree % (ts, "mxparser 1.2.2", log_sha))]
sha, d, lic = jarinfo("xmlpull/xmlpull/1.1.3.1/xmlpull-1.1.3.1.jar")
assert sha == "34E08EE62116071CBB69C0ED70D15A7A5B208D62798C59F2120BB8929324CB63", sha
xp = ["xmlpull:xmlpull:1.1.3.1", sha, "Central", "use=runtime; maven=compile(optional)", "sangam-connectors-health",
      "Public Domain", "LicenseRef-Public-Domain", "n/a (public domain)", "LicenseRef-Public-Domain: none (jurisdiction-dependent)",
      "ACCEPTED FOR DEFINED USE",
      "owner decision 263 (2026-11-13): the XmlPull API used by mxparser 1.2.2; dedication quoted in texts/LicenseRef-Public-Domain.txt",
      "~/.m2/repository/xmlpull/xmlpull/1.1.3.1/xmlpull-1.1.3.1.jar; licence from POM xmlpull:xmlpull:1.1.3.1 (Public Domain, http://www.xmlpull.org/v1/download/unpacked/LICENSE.txt); jar licence files %s; release signal: MANIFEST.MF zip entry date %s; %s" % (lic, d, tree % (ts, "xmlpull 1.1.3.1", log_sha))]

# decision 275: jna 5.17.0
j = [r for r in body if r[0] == "net.java.dev.jna:jna:5.17.0"]
assert len(j) == 1
j = j[0]
assert j[ix["scopes"]] == "use=build; maven=build" and j[ix["modules"]] == "plugin:spring-boot-maven-plugin-4.1.2"
old_c = j[ix["conditions_or_reason"]]
assert old_c.endswith("elect Apache-2.0; record the election in the SBOM (SPDX expression) and NOTICE")
j[ix["scopes"]] = "use=build; maven=build,test"
j[ix["modules"]] = "plugin:spring-boot-maven-plugin-4.1.2 sangam-quarkus-extension"
j[ix["conditions_or_reason"]] = old_c + ("; decision 117: Apache-2.0 elected (licence-policy.json elections); decision 275 (2026-11-13): pinned to 5.17.0 in the "
                                         "sangam-quarkus-extension test closure (quarkus-junit, resolved by the Quarkus bootstrap at test time), replacing the flagged 5.8.0; test scope only, never shipped")
j[ix["evidence"]] = j[ix["evidence"]] + ("; dependency:tree offline 2026-11-13T05:15:29Z -f sangam-quarkus-extension/pom.xml shows no jna in the Maven tree, so the test-time "
                                         "closure comes from the Quarkus bootstrap (log SHA-256 A438922D2EC19E8CEF757C93B8652F0C7091633E77E33762703239D9283F0ED0); the 5.17.0 pin is decision 275 (W4-F)")

import bisect
was_sorted = [r[0] for r in body] == sorted(r[0] for r in body)
for new in (mx, xp):
    if was_sorted:
        body.insert(bisect.bisect([r[0] for r in body], new[0]), new)
    else:
        body.append(new)
print('sorted', was_sorted)
out = io.StringIO(newline="")
w = csv.writer(out, quoting=csv.QUOTE_ALL, lineterminator="\r\n")
w.writerow(head)
w.writerows(body)
open(inv, "wb").write(out.getvalue().encode("utf-8"))
print("rows", len(body))
