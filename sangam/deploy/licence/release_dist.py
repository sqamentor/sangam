# SPDX-License-Identifier: Apache-2.0
"""Reads distributions.json for release.yml, so the workflow never repeats a file name the JSON already defines (M-147).

  python3 -I deploy/licence/release_dist.py field <id> <key> [--version V]
      prints the field ("{version}" replaced by V); a list prints one item per line; exit 2 when the id or key
      is unknown (a release step then records BLOCKED, never a guess)
  python3 -I deploy/licence/release_dist.py ids [--kind KIND]
      prints the distribution ids, one per line, in file order
  python3 -I deploy/licence/release_dist.py scope <id>
      prints the licence scope of the id: its "scope_as" when set, else the id itself

Standard library only.
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


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
    a = ap.parse_args(argv)
    try:
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
