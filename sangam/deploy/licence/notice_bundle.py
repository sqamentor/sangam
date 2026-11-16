# SPDX-License-Identifier: Apache-2.0
"""Notices bundle of one distribution (CR-07, CR-20, M-147): what goes next to the artefact so its licence notices travel.

Writes into --out:
  LICENSE              Sangam's own licence (the repository LICENSE)
  NOTICE               the repository NOTICE, then every NOTICE file found inside the shipped dependencies
                       (META-INF/NOTICE* of each jar in BOOT-INF/lib or --dir, or NOTICE* of each npm package under
                       --node-modules), each under the name of the jar or package it came from; identical texts once;
                       then the attribution text of every SBOM component listed in --attributions (attributions.json:
                       exact purl -> file), for components whose artefact carries no licence or copyright text of its
                       own (decision 176: paranamer 2.8.3). A listed file that is missing is BLOCKED (exit 2).
  THIRD-PARTY.txt      one line per SBOM component: purl, the licence terms as declared, and the licence the
                       distribution uses (the elected one where licence-policy.json records an election); for a
                       component used under EPL, MPL or CDDL also where its source code is (RM-16,
                       source-locations.json by exact purl; a missing entry FAILS the bundle, exit 1)
  licenses/<id>.txt    the text of every licence id that THIRD-PARTY.txt uses
Licence texts come from --texts DIR/<id>.txt ("X WITH Y" needs X.txt and Y.txt); Apache-2.0 is the repository
LICENSE. A licence id with no text FAILS the bundle (exit 1) and is listed: the text has to be added (decision 134
download line), never skipped. First-party components (io.github.sqamentor, decision 210; @sangam) are covered by LICENSE and NOTICE.

Exit codes: 0 bundle complete, 1 texts missing, 2 unreadable input (BLOCKED). Standard library only.
"""
import argparse
import io
import os
import re
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_licences import (Checker, InputError, expression_alternatives, iter_components,  # noqa: E402
                            licence_terms, load_json, normalise)

# Decision 210: the Maven groupId is io.github.sqamentor (sweep done); a component still under org.sangam is
# third-party to this check, so a missed rename shows up in THIRD-PARTY.txt instead of being hidden.
FIRST_PARTY = ("pkg:maven/io.github.sqamentor/", "pkg:npm/%40sangam/")
NOTICE_NAME = re.compile(r"(^|/)NOTICE(\.txt|\.md)?$", re.IGNORECASE)


def used_licences(comp, checker):
    """Returns (declared text, list of SPDX ids the distribution uses)."""
    names, expr = licence_terms(comp)
    purl = comp.get("purl") or ""
    election = checker.election_for(purl) if purl else None
    declared = expr if expr else " AND ".join(normalise(n) for n in names)
    if election:
        terms = [election["elected"]]
    elif expr:
        terms = sorted({t for alt in expression_alternatives(expr) for t in alt})
    else:
        terms = [normalise(n) for n in names]
    ids = []
    for t in terms:
        for part in t.split(" WITH "):
            part = part.strip()
            if part and part not in ids:
                ids.append(part)
    return declared or "(none declared)", ids


def text_path(lic, texts, root):
    if lic == "Apache-2.0":
        return os.path.join(root, "LICENSE")
    return os.path.join(texts, f"{lic}.txt")


def jar_notices(jar=None, directory=None):
    """Yields (source name, text) for NOTICE files inside the shipped jars."""
    def scan(name, data):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for n in z.namelist():
                    if n.startswith("META-INF/") and NOTICE_NAME.search(n):
                        yield name, z.read(n).decode("utf-8", "replace")
        except zipfile.BadZipFile as e:
            raise InputError(f"{name} is not a jar: {e}") from e
    if jar:
        try:
            with zipfile.ZipFile(jar) as outer:
                for n in sorted(outer.namelist()):
                    if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"):
                        yield from scan(n.rsplit("/", 1)[1], outer.read(n))
        except (OSError, zipfile.BadZipFile) as e:
            raise InputError(f"cannot read {jar}: {e}") from e
    if directory:
        for n in sorted(os.listdir(directory)):
            if n.endswith(".jar") and not n.endswith("-sources.jar"):
                with open(os.path.join(directory, n), "rb") as f:
                    yield from scan(n, f.read())


def npm_notices(node_modules, sbom):
    for comp in iter_components(sbom):
        purl = comp.get("purl") or ""
        if not purl.startswith("pkg:npm/") or purl.startswith(FIRST_PARTY):
            continue
        name = (f"@{comp['group'].lstrip('@')}/" if comp.get("group") else "") + comp.get("name", "")
        pkg = os.path.join(node_modules, *name.split("/"))
        if os.path.isdir(pkg):
            for n in sorted(os.listdir(pkg)):
                if NOTICE_NAME.search(n):
                    with open(os.path.join(pkg, n), encoding="utf-8", errors="replace") as f:
                        yield f"{name}@{comp.get('version', '')}", f.read()


def attribution_texts(sbom, attributions):
    """Yields (purl, text) for SBOM components listed in DIR/attributions.json; keys are exact purls without qualifiers."""
    if not attributions:
        return
    index = os.path.join(attributions, "attributions.json")
    if not os.path.isfile(index):
        raise InputError(f"{index} does not exist")
    listed = {k: v for k, v in load_json(index).items() if not k.startswith("_")}
    for comp in sorted(iter_components(sbom), key=lambda c: c.get("purl") or ""):
        purl = (comp.get("purl") or "").split("?", 1)[0].split("#", 1)[0]
        if purl in listed:
            path = os.path.join(attributions, listed[purl])
            if not os.path.isfile(path):
                raise InputError(f"attribution file {path} for {purl} does not exist")
            with open(path, encoding="utf-8") as f:
                yield purl, f.read()


SOURCE_MISSING = "source location of "


def load_sources(path):
    """source-locations.json (RM-16): the weak-copyleft licence ids and the exact purl -> source URL map."""
    if not os.path.isfile(path):
        raise InputError(f"{path} does not exist")
    doc = load_json(path)
    licences, comps = doc.get("licences"), doc.get("components")
    if not isinstance(licences, list) or not isinstance(comps, dict):
        raise InputError(f"{path} needs a 'licences' list and a 'components' map")
    return {"licences": set(licences), "components": comps}


def build(sbom, policy, dist_id, root, texts, out, jar=None, directory=None, node_modules=None, attributions=None,
          sources=None):
    """Returns what is missing: licence ids with no text, then "source location of <purl>" for every component used
    under a weak-copyleft licence of ``sources`` (RM-16: EPL, MPL, CDDL) that has no source-location entry for its
    exact purl. Nothing is written when anything is missing."""
    attributed = list(attribution_texts(sbom, attributions))  # unreadable input is BLOCKED before anything is written
    checker = Checker(policy, True, distribution=dist_id)
    rows, ids, no_source = [], [], []
    for comp in sorted(iter_components(sbom), key=lambda c: c.get("purl") or c.get("name") or ""):
        purl = comp.get("purl") or ""
        if not purl or purl.startswith(FIRST_PARTY):
            continue
        declared, used = used_licences(comp, checker)
        row = f"{purl}\tdeclared: {declared}\tused: {' AND '.join(used) if used else '(none)'}"
        if sources is not None and any(i in sources["licences"] for i in used):
            coord = purl.split("?", 1)[0].split("#", 1)[0]
            url = sources["components"].get(coord)
            if url:
                row += f"\tsource: {url}"
            else:
                no_source.append(SOURCE_MISSING + coord)
        rows.append(row)
        ids.extend(i for i in used if i not in ids)
    missing = [i for i in sorted(ids) if not os.path.isfile(text_path(i, texts, root))] + no_source
    if missing:
        return missing
    os.makedirs(os.path.join(out, "licenses"), exist_ok=True)
    shutil.copyfile(os.path.join(root, "LICENSE"), os.path.join(out, "LICENSE"))
    for i in sorted(ids):
        shutil.copyfile(text_path(i, texts, root), os.path.join(out, "licenses", f"{i}.txt"))
    with open(os.path.join(root, "NOTICE"), encoding="utf-8") as f:
        notice = [f.read().replace("\r\n", "\n").rstrip() + "\n"]
    # A NOTICE the repository NOTICE already reproduces word for word (M-088: the aggregated root NOTICE) is not
    # repeated; any other text, even a near copy, is kept.
    seen = set()
    already = notice[0]
    sources = list(jar_notices(jar, directory)) if (jar or directory) else []
    if node_modules:
        sources += list(npm_notices(node_modules, sbom))
    for name, text in sources:
        key = text.replace("\r\n", "\n").strip()
        if key and key not in seen and key not in already:
            seen.add(key)
            notice.append(f"\n==== NOTICE of {name} ====\n{text.rstrip()}\n")
    for purl, text in attributed:
        notice.append(f"\n==== Licence and copyright of {purl} (the artefact carries none) ====\n{text.rstrip()}\n")
    with open(os.path.join(out, "NOTICE"), "w", encoding="utf-8", newline="\n") as f:
        f.writelines(notice)
    with open(os.path.join(out, "THIRD-PARTY.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"Third-party components of the {dist_id} distribution (from its CycloneDX SBOM).\n"
                "Licence texts are in licenses/. A component used under a weak-copyleft licence (EPL, MPL, CDDL) names\n"
                "where its source code can be obtained (source:). This list records licence terms; it is not legal advice.\n\n")
        f.write("\n".join(rows) + "\n")
    return []


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sbom")
    ap.add_argument("--distribution", required=True)
    ap.add_argument("--root", required=True, help="repository root (LICENSE, NOTICE)")
    ap.add_argument("--texts", default=os.path.join(here, "texts"))
    ap.add_argument("--policy", default=os.path.join(here, "licence-policy.json"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--jar")
    ap.add_argument("--dir")
    ap.add_argument("--node-modules")
    ap.add_argument("--attributions", default=os.path.join(here, "attributions"),
                    help="directory with attributions.json (exact purl -> text file); default deploy/licence/attributions")
    ap.add_argument("--sources", default=os.path.join(here, "source-locations.json"),
                    help="RM-16 source locations of weak-copyleft components; default deploy/licence/source-locations.json")
    a = ap.parse_args(argv)
    try:
        sbom = load_json(a.sbom)
        if not isinstance(sbom, dict) or sbom.get("bomFormat") != "CycloneDX":
            raise InputError(f"{a.sbom} is not a CycloneDX SBOM")
        for p in ("LICENSE", "NOTICE"):
            if not os.path.isfile(os.path.join(a.root, p)):
                raise InputError(f"{p} is not in {a.root}")
        missing = build(sbom, load_json(a.policy), a.distribution, a.root, a.texts, a.out, a.jar, a.dir, a.node_modules,
                        a.attributions, load_sources(a.sources))
    except InputError as e:
        print(f"::error title=notices BLOCKED::{e}")
        return 2
    for m in missing:
        if m.startswith(SOURCE_MISSING):
            print(f"::error title=notices {a.distribution}::no {m} (add its exact purl to {a.sources})")
        else:
            print(f"::error title=notices {a.distribution}::no licence text for {m} (add {a.texts}/{m}.txt)")
    print(f"notices {a.distribution}: {'FAIL' if missing else 'PASS'}, {len(missing)} licence text(s) or source location(s) missing")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
