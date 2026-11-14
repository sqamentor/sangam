# SPDX-License-Identifier: Apache-2.0
"""Local-content dossier generator (M-089; SANGAM.md R62 / GOV-12, ADR-D-11 (a) and (d), PK-07).

Builds a DRAFT local-content dossier for one SKU from an operator-filled cost breakdown
(local-content-input.template.json). Standard library only; money is decimal text, never float.

What it computes (the general definition of the public procurement preference order, which applies "unless
otherwise prescribed by the Nodal Ministry"): local content = (total value of the item, excluding net domestic
indirect taxes, minus the value of imported content, including customs duties) / total value, in percent.
A component counts as imported content when its origin is IMPORTED or any part of its value is paid outside the
country (licence fees, royalties, technical charges), or it is an imported item bought from a local reseller.
The class thresholds are input data with their source (the template carries the values of the order as read on
SANGAM.md IX.7, marked for verification), never constants in this code.

What it does NOT do (owner decision 109 and decision 112):
- it never signs, files or lists anything: the declaration is signed by the owner at listing time;
- it never states that a SKU qualifies under any law or order: the output is a calculation draft for the owner
  and the owner's advisers;
- no software-specific calculation method was found (SANGAM.md PK-07); if a nodal ministry prescribes one, the
  dossier must be redone under it.

Usage (from any directory):
  python -I local_content_dossier.py INPUT.json --out OUT_DIR
Writes OUT_DIR/local-content-<sku>.md and OUT_DIR/local-content-<sku>.json. Exit 0 on success, 1 when the input
is invalid (each problem is printed).
"""
import argparse
import json
import os
import re
import sys
from decimal import Decimal, InvalidOperation, ROUND_DOWN

ORIGINS = ("DOMESTIC", "IMPORTED")
SKU_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
DRAFT_BANNER = ("DRAFT FOR THE OWNER. This is a calculation, not a declaration. It is not signed, not filed and not "
                "listed by any code. The owner signs the declaration at listing time (owner decision 109).")


class InputError(Exception):
    """The input file is invalid; ``problems`` lists every problem found."""

    def __init__(self, problems):
        super().__init__("; ".join(problems))
        self.problems = problems


def _decimal(value, where, problems):
    if isinstance(value, bool) or not isinstance(value, str):
        problems.append(f"{where}: money and percentages are decimal text (for example \"1250.00\"), got {value!r}")
        return None
    try:
        d = Decimal(value.strip())
    except InvalidOperation:
        problems.append(f"{where}: not a decimal number: {value!r}")
        return None
    if not d.is_finite():
        problems.append(f"{where}: not a finite number: {value!r}")
        return None
    return d


def _text(value, where, problems, required=True):
    if value is None or (isinstance(value, str) and not value.strip()):
        if required:
            problems.append(f"{where}: required")
        return ""
    if not isinstance(value, str):
        problems.append(f"{where}: must be text")
        return ""
    return value.strip()


def compute(doc):
    """Validates the input and returns the dossier model (a dict). Raises InputError with every problem found."""
    problems = []
    if not isinstance(doc, dict):
        raise InputError(["the input must be a JSON object"])
    sku = _text(doc.get("sku"), "sku", problems)
    if sku and not SKU_RE.match(sku):
        problems.append("sku: letters, digits, '.', '_' and '-' only (at most 64), starting with a letter or digit")
    product = _text(doc.get("product"), "product", problems)
    version = _text(doc.get("productVersion"), "productVersion", problems)
    currency = _text(doc.get("currency"), "currency", problems)
    total = _decimal(doc.get("totalValueExcludingDomesticIndirectTaxes"), "totalValueExcludingDomesticIndirectTaxes", problems)
    if total is not None and total <= 0:
        problems.append("totalValueExcludingDomesticIndirectTaxes: must be greater than zero")

    th = doc.get("thresholds")
    class_i = class_ii = None
    th_source = ""
    if not isinstance(th, dict):
        problems.append("thresholds: required object {classIPercent, classIIPercent, source}")
    else:
        class_i = _decimal(th.get("classIPercent"), "thresholds.classIPercent", problems)
        class_ii = _decimal(th.get("classIIPercent"), "thresholds.classIIPercent", problems)
        th_source = _text(th.get("source"), "thresholds.source", problems)
        if class_i is not None and class_ii is not None and not (Decimal(0) < class_ii < class_i <= Decimal(100)):
            problems.append("thresholds: need 0 < classIIPercent < classIPercent <= 100")

    comps = doc.get("components")
    rows = []
    if not isinstance(comps, list) or not comps:
        problems.append("components: at least one component is required")
        comps = []
    seen = set()
    for i, c in enumerate(comps):
        where = f"components[{i}]"
        if not isinstance(c, dict):
            problems.append(f"{where}: must be an object")
            continue
        cid = _text(c.get("id"), where + ".id", problems)
        if cid in seen:
            problems.append(f"{where}.id: duplicate id {cid!r}")
        seen.add(cid)
        value = _decimal(c.get("value"), where + ".value", problems)
        if value is not None and value < 0:
            problems.append(f"{where}.value: must not be negative")
        origin = c.get("origin")
        if origin not in ORIGINS:
            problems.append(f"{where}.origin: one of {', '.join(ORIGINS)}")
        paid_outside = c.get("paidOutsideCountry", False)
        resold_import = c.get("importedItemBoughtLocally", False)
        for flag, name in ((paid_outside, "paidOutsideCountry"), (resold_import, "importedItemBoughtLocally")):
            if not isinstance(flag, bool):
                problems.append(f"{where}.{name}: true or false")
        evidence = _text(c.get("evidence"), where + ".evidence", problems)
        imported = origin == "IMPORTED" or paid_outside is True or resold_import is True
        reasons = [r for r, on in (("origin IMPORTED", origin == "IMPORTED"), ("paid outside the country", paid_outside is True),
                                   ("imported item bought locally", resold_import is True)) if on]
        rows.append({"id": cid, "description": _text(c.get("description"), where + ".description", problems, False),
                     "value": value, "imported": imported, "reasons": reasons, "evidence": evidence})

    if problems:
        raise InputError(problems)
    component_sum = sum((r["value"] for r in rows), Decimal(0))
    if component_sum != total:
        raise InputError([f"components: values sum to {component_sum}, but totalValueExcludingDomesticIndirectTaxes is {total};"
                          " every part of the value must be in exactly one component"])
    imported_value = sum((r["value"] for r in rows if r["imported"]), Decimal(0))
    local_value = total - imported_value
    exact = local_value * 100 / total
    # never overstate: the reported figure is cut (not rounded up) to 2 places, and the class uses the exact value
    shown = exact.quantize(Decimal("0.01"), rounding=ROUND_DOWN)
    if exact >= class_i:
        klass = "CLASS_I"
    elif exact >= class_ii:
        klass = "CLASS_II"
    else:
        klass = "BELOW_CLASS_II"
    return {
        "status": "DRAFT_NOT_A_DECLARATION",
        "sku": sku, "product": product, "productVersion": version, "currency": currency,
        "totalValue": str(total), "importedContentValue": str(imported_value), "localValue": str(local_value),
        "localContentPercent": str(shown), "calculatedClass": klass,
        "thresholds": {"classIPercent": str(class_i), "classIIPercent": str(class_ii), "source": th_source},
        "components": [dict(r, value=str(r["value"])) for r in rows],
        "method": "general definition: (total value - imported content) / total value x 100; no software-specific"
                  " method found (SANGAM.md PK-07)",
        "declaration": {"signed": False, "signedBy": None,
                        "note": "signed by the owner at listing time (owner decision 109); never by this generator"},
    }


def markdown(m):
    lines = [
        f"# Local-content dossier (draft): {m['product']} {m['productVersion']}, SKU {m['sku']}",
        "",
        f"> {DRAFT_BANNER}",
        "",
        "## Result of the calculation",
        "",
        "| Item | Value |",
        "|---|---|",
        f"| Total value of the item, excluding net domestic indirect taxes ({m['currency']}) | {m['totalValue']} |",
        f"| Imported content, including customs duties ({m['currency']}) | {m['importedContentValue']} |",
        f"| Value added locally ({m['currency']}) | {m['localValue']} |",
        f"| Local content (cut to 2 places, never rounded up) | {m['localContentPercent']} % |",
        f"| Calculated class against the thresholds below | {m['calculatedClass']} |",
        "",
        f"Thresholds used: Class-I at or above {m['thresholds']['classIPercent']} %, Class-II at or above "
        f"{m['thresholds']['classIIPercent']} %. Source: {m['thresholds']['source']}.",
        "",
        "## Method",
        "",
        f"{m['method']}. A component is imported content when its origin is imported, when any of its value is paid"
        " outside the country (licence fees, royalties, technical charges), or when it is an imported item bought from"
        " a local reseller or distributor. If a nodal ministry prescribes a different method for software, this"
        " dossier must be recalculated under it.",
        "",
        "## Components",
        "",
        "| Id | Description | Value | Counted as | Why | Evidence |",
        "|---|---|---|---|---|---|",
    ]
    for r in m["components"]:
        why = ", ".join(r["reasons"]) or "domestic value addition"
        lines.append(f"| {r['id']} | {r['description']} | {r['value']} | {'imported' if r['imported'] else 'local'} | {why} | {r['evidence']} |")
    lines += [
        "",
        "## Declaration",
        "",
        "Not signed. The owner signs the local-content declaration, with the supporting evidence above, at listing time."
        " This document makes no statement that the SKU qualifies under any law, order or procurement rule.",
        "",
    ]
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Local-content dossier draft (M-089)")
    ap.add_argument("input", help="filled copy of local-content-input.template.json")
    ap.add_argument("--out", required=True, help="output directory")
    a = ap.parse_args(argv)
    with open(a.input, encoding="utf-8") as f:
        doc = json.load(f)
    try:
        model = compute(doc)
    except InputError as e:
        for p in e.problems:
            print("ERROR " + p, file=sys.stderr)
        return 1
    os.makedirs(a.out, exist_ok=True)
    base = os.path.join(a.out, "local-content-" + model["sku"])
    with open(base + ".json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(model, f, indent=2, ensure_ascii=False)
        f.write("\n")
    with open(base + ".md", "w", encoding="utf-8", newline="\n") as f:
        f.write(markdown(model))
    print(f"{model['sku']}: local content {model['localContentPercent']} % ({model['calculatedClass']}), draft written to {base}.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
