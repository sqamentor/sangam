set -euo pipefail
python -I - <<'PY'
import json, sys, tomllib, xml.etree.ElementTree as E
got = {
    "typescript/package.json name": json.load(open("typescript/package.json"))["name"],
    "python/pyproject.toml project.name": tomllib.load(open("python/pyproject.toml", "rb"))["project"]["name"],
    "Sangam.Sdk.csproj PackageId": E.parse("dotnet/src/Sangam.Sdk/Sangam.Sdk.csproj").getroot().findtext("./PropertyGroup/PackageId"),
    "php/composer.json name": json.load(open("php/composer.json"))["name"],
}
want = dict(zip(got, ("@sangam/sdk", "sangam-sdk", "Sangam.Sdk", "sangam/sdk")))
bad = [f"{k} is {got[k]!r}, decision 105 says {want[k]!r}" for k in got if got[k] != want[k]]
for b in bad:
    print(f"::error::{b}")
sys.exit(1 if bad else 0)
PY
