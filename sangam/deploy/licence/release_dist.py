# SPDX-License-Identifier: Apache-2.0
"""Reads distributions.json for release.yml, so the workflow never repeats a file name the JSON already defines (M-147).

  python3 -I deploy/licence/release_dist.py field <id> <key> [--version V]
      prints the field ("{version}" replaced by V); a list prints one item per line; exit 2 when the id or key
      is unknown (a release step then records BLOCKED, never a guess)
  python3 -I deploy/licence/release_dist.py ids [--kind KIND]
      prints the distribution ids, one per line, in file order
  python3 -I deploy/licence/release_dist.py scope <id>
      prints the licence scope of the id: its "scope_as" when set, else the id itself
  python3 -I deploy/licence/release_dist.py pom-version [--pom POM]
      prints the project version of a Maven pom (default: the sangam root pom), so a pull-request workflow names
      each jar from distributions.json with the version it was built with instead of finding it (RM-15); exit 2
      when the pom has no literal own <version> (an inherited or missing version is never guessed)

Standard library only.
"""
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT_POM = os.path.normpath(os.path.join(HERE, "..", "..", "pom.xml"))


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)["distributions"]


def find(dists, dist_id):
    for d in dists:
        if d.get("id") == dist_id:
            return d
    raise KeyError(f"distribution {dist_id!r} is not in distributions.json")


def field(dists, dist_id, key, version=""):
    d = find(dists, dist_id)
    if key not in d:
        raise KeyError(f"distribution {dist_id!r} has no field {key!r}")
    v = d[key]
    items = v if isinstance(v, list) else [v]
    return [str(x).replace("{version}", version) for x in items]


def pom_version(path):
    """The pom's own <project><version>; a pom without a literal one (inherited, a property) is an error, not a guess."""
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        raise ValueError(f"cannot read {path}: {e}") from e
    ns = root.tag[:root.tag.index("}") + 1] if root.tag.startswith("{") else ""
    v = root.find(f"{ns}version")
    text = (v.text or "").strip() if v is not None else ""
    if not text or "${" in text:
        raise ValueError(f"{path} has no literal project version")
    return text


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--distributions", default=os.path.join(HERE, "distributions.json"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("field")
    f.add_argument("id")
    f.add_argument("key")
    f.add_argument("--version", default="")
    i = sub.add_parser("ids")
    i.add_argument("--kind")
    s = sub.add_parser("scope")
    s.add_argument("id")
    pv = sub.add_parser("pom-version")
    pv.add_argument("--pom", default=ROOT_POM)
    a = ap.parse_args(argv)
    try:
        if a.cmd == "pom-version":
            print(pom_version(a.pom))
            return 0
        dists = load(a.distributions)
        if a.cmd == "field":
            out = field(dists, a.id, a.key, a.version)
        elif a.cmd == "scope":
            out = [find(dists, a.id).get("scope_as") or a.id]
        else:
            out = [d["id"] for d in dists if a.kind is None or d.get("kind") == a.kind]
    except (OSError, ValueError, KeyError) as e:
        print(f"::error title=distributions.json::{e}", file=sys.stderr)
        return 2
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
