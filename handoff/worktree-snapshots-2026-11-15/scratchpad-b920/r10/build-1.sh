set -euo pipefail
python -I -c 'import json,sys; v=json.load(open("typescript/package.json"))["version"]; sys.exit(0 if v == sys.argv[1] else f"typescript/package.json version {v} is not {sys.argv[1]}")' "$V"
python -I -c 'import sys,tomllib; v=tomllib.load(open("python/pyproject.toml","rb"))["project"]["version"]; sys.exit(0 if v == sys.argv[1] else f"python/pyproject.toml version {v} is not {sys.argv[1]}")' "$V"
python -I -c 'import sys,xml.etree.ElementTree as E; v=E.parse("dotnet/src/Sangam.Sdk/Sangam.Sdk.csproj").getroot().findtext("./PropertyGroup/Version"); sys.exit(0 if v == sys.argv[1] else f"Sangam.Sdk.csproj Version {v} is not {sys.argv[1]}")' "$V"
