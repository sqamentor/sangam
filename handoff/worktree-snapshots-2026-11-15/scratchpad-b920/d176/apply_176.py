"""Decision 176 row updates for the E16 inventory CSVs (W4-C, Wave 4b). Rewrites only the named rows and columns."""
import csv
import io
import sys

MAVEN, NON_MAVEN = sys.argv[1], sys.argv[2]
DATE = "2026-11-12"

JERSEY_COMMON = (
    "decision 176 (W4-C {d}): the POM <licenses> list (ten entries) covers the whole Jersey project and its "
    "third-party content; the jar's own META-INF/NOTICE.md declares the project licence as "
    "'SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0' (GPL-2.0 is the deprecated SPDX id of "
    "GPL-2.0-only), and META-INF/LICENSE.md is the EPL-2.0 text. Entry-by-entry check of the 4.0.3 jar: "
)
JERSEY_TAIL = (
    " The NOTICE third-party entries (Angular JS, Bootstrap, jQuery, KineticJS, W3C and the rest) are not in this "
    "artefact. Conditions: Sangam elects EPL-2.0 (allowed list of licence-policy.json); EPL-2.0 s.3 source offer is "
    "carried by the distribution source offer; META-INF/NOTICE.md goes into the notices bundle."
)
JERSEY_PLAIN = "only org/glassfish/jersey/** classes, module-info and META-INF; no third-party code or web asset."
JERSEY_EVIDENCE = (
    "; decision 176 review {d}: META-INF/NOTICE.md SPDX line and jar entry listing read from the cached 4.0.3 jar "
    "(SHA-256 in column sha256)"
)

MAVEN_ROWS = {
    "com.thoughtworks.paranamer:paranamer:2.8.3": {
        "spdx": "BSD-3-Clause",
        "obligations": "BSD-3-Clause: attribution (copyright + licence text in docs); no endorsement",
        "decision": "ACCEPTED WITH CONDITIONS",
        "conditions_or_reason": (
            "decision 176 (W4-C {d}): POM licence name 'BSD' with URL https://opensource.org/license/bsd-3-clause; "
            "the upstream LICENSE.txt at tag paranamer-parent-2.8.3 is BSD-3-Clause (three conditions; Portions "
            "copyright (c) 2006-2018 Paul Hammant & ThoughtWorks Inc; Portions copyright (c) 2000-2007 INRIA, France "
            "Telecom). Condition: the jar has no LICENSE or NOTICE file, so the distribution notices must carry that "
            "licence text with both copyright lines."
        ),
        "evidence_append": (
            "; decision 176 review {d}: licence text "
            "https://raw.githubusercontent.com/paul-hammant/paranamer/paranamer-parent-2.8.3/LICENSE.txt "
            "(spec lookup, decision 137(3))"
        ),
    },
}
for gav in ("org.glassfish.jersey.containers:jersey-container-servlet:4.0.3",
            "org.glassfish.jersey.core:jersey-client:4.0.3",
            "org.glassfish.jersey.ext:jersey-entity-filtering:4.0.3",
            "org.glassfish.jersey.ext:jersey-spring6:4.0.3",
            "org.glassfish.jersey.inject:jersey-hk2:4.0.3"):
    MAVEN_ROWS[gav] = {
        "spdx": "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0",
        "obligations": "EPL-2.0 (elected): weak copyleft (file level); source offer for EPL code distributed in "
                       "object form; patent; attribution; carry META-INF/NOTICE.md",
        "decision": "ACCEPTED WITH CONDITIONS",
        "conditions_or_reason": JERSEY_COMMON + JERSEY_PLAIN + JERSEY_TAIL,
        "evidence_append": JERSEY_EVIDENCE,
    }
MAVEN_ROWS["org.glassfish.jersey.ext:jersey-bean-validation:4.0.3"] = {
    "spdx": "(EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0) AND Apache-2.0",
    "obligations": "EPL-2.0 (elected): weak copyleft (file level); source offer for EPL code distributed in object "
                   "form; patent; attribution; Apache-2.0 (repackaged Hibernate Validator CDI): attribution, licence "
                   "copy, carry NOTICE; carry META-INF/NOTICE.md",
    "decision": "ACCEPTED WITH CONDITIONS",
    "conditions_or_reason": JERSEY_COMMON + (
        "org/glassfish/jersey/** classes only, of which org/glassfish/jersey/server/validation/internal/hibernate/** "
        "is Hibernate Validator CDI 8.0.5.Final repackaged (Apache-2.0 per the NOTICE.md entry 'Repackaged in "
        "org.glassfish.jersey.server.validation.internal.hibernate')."
    ) + JERSEY_TAIL + " Apache-2.0 attribution for the repackaged code is carried with it.",
    "evidence_append": JERSEY_EVIDENCE,
}

TELEMETRY = (
    "decision 176 (W4-C {d}): licence Apache-2.0 confirmed (package.json license and LICENSE file). The open point was "
    "not the licence but IBM install-time telemetry: "
)
NON_MAVEN_ROWS = {
    ("@carbon/grid", "11.66.0"): {
        "decision": "ACCEPTED WITH CONDITIONS",
        "conditions_or_reason": TELEMETRY + (
            "the package's only telemetry entry point is scripts.postinstall 'ibmtelemetry --config=telemetry.yml' "
            "(SCSS package, nothing to import at run time). Condition: every npm install or ci of sangam-ui runs with "
            "--ignore-scripts (decision 130; workflow_guards.py npm-scripts check fails any other), so the postinstall "
            "never runs; open: ui.Dockerfile npm ci (W4-C REQUEST 2026-11-12T17:53:27Z to its owner)."
        ),
    },
    ("@carbon/layout", "11.63.0"): {
        "decision": "ACCEPTED WITH CONDITIONS",
        "conditions_or_reason": TELEMETRY + (
            "the package's only telemetry entry point is scripts.postinstall 'ibmtelemetry --config=telemetry.yml'; "
            "its lib/index.js (layout tokens) does not import @ibm/telemetry-js. Condition: every npm install or ci of "
            "sangam-ui runs with --ignore-scripts (decision 130; workflow_guards.py npm-scripts check fails any "
            "other), so the postinstall never runs; open: ui.Dockerfile npm ci (W4-C REQUEST 2026-11-12T17:53:27Z)."
        ),
    },
    ("@ibm/telemetry-js", "1.12.0"): {
        "decision": "ACCEPTED WITH CONDITIONS",
        "conditions_or_reason": TELEMETRY + (
            "this package is a command-line tool only (package.json has bin ibmtelemetry -> dist/collect.js and no "
            "main or exports), reached only from the @carbon/grid and @carbon/layout postinstall scripts; no sangam-ui "
            "source imports it, so the vite bundle does not contain it. Condition: --ignore-scripts on every npm "
            "install or ci of sangam-ui (decision 130), so it never runs; open: ui.Dockerfile npm ci (W4-C REQUEST "
            "2026-11-12T17:53:27Z)."
        ),
    },
    ("@fontsource/noto-sans", "5.1.0"): {
        "decision": "REJECTED FOR DEFINED USE",
        "conditions_or_reason": (
            "decision 176 (W4-C {d}): not cleared (OFL-1.1 web-font use under pkg:npm is outside the conditional "
            "OFL-1.1 entry of licence-policy.json, decisions 70/95) and removed from the shipped closure: sangam-ui "
            "package-lock.json no longer contains node_modules/@fontsource/noto-sans (removed by W4-J)."
        ),
    },
    ("@fontsource/noto-sans-devanagari", "5.1.0"): {
        "decision": "REJECTED FOR DEFINED USE",
        "conditions_or_reason": (
            "decision 176 (W4-C {d}): not cleared (OFL-1.1 web-font use under pkg:npm is outside the conditional "
            "OFL-1.1 entry of licence-policy.json, decisions 70/95) and removed from the shipped closure: sangam-ui "
            "package-lock.json no longer contains node_modules/@fontsource/noto-sans-devanagari (removed by W4-J)."
        ),
    },
}


def rewrite(path, key_of, updates, quoting):
    with open(path, encoding="utf-8", newline="") as f:
        raw = f.read()
    newline = "\r\n" if "\r\n" in raw else "\n"
    rows = list(csv.DictReader(io.StringIO(raw, newline="")))
    fields = list(csv.DictReader(io.StringIO(raw, newline="")).fieldnames)
    seen = set()
    for r in rows:
        k = key_of(r)
        if k in updates:
            if r["decision"] != "REVIEW REQUIRED":
                raise SystemExit(f"{k}: decision is {r['decision']!r}, not REVIEW REQUIRED")
            u = updates[k]
            for col, val in u.items():
                if col == "evidence_append":
                    r["evidence"] = r["evidence"] + val.format(d=DATE)
                else:
                    r[col] = val.format(d=DATE)
            seen.add(k)
    missing = set(updates) - seen
    if missing:
        raise SystemExit(f"rows not found: {sorted(missing)}")
    out = io.StringIO(newline="")
    w = csv.DictWriter(out, fieldnames=fields, quoting=quoting, lineterminator=newline)
    w.writeheader()
    w.writerows(rows)
    data = out.getvalue()
    if not raw.endswith(newline):
        data = data[: -len(newline)]
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(data)
    print(f"{path}: {len(seen)} rows updated, {len(rows)} rows, newline {newline!r}")


rewrite(MAVEN, lambda r: r["gav"], MAVEN_ROWS, csv.QUOTE_ALL)
rewrite(NON_MAVEN, lambda r: (r["name"], r["version"]), NON_MAVEN_ROWS, csv.QUOTE_MINIMAL)
