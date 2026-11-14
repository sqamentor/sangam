
  set -uo pipefail
  npm run e2e -- --reporter=list,html,json; rc=$?
  python3 -I - e2e-results.json "$rc" <<"PY"
import json, sys
path, rc = sys.argv[1], int(sys.argv[2])
try:
    with open(path, encoding="utf-8") as f:
        s = json.load(f)["stats"]
except (OSError, ValueError, KeyError) as e:
    sys.exit(f"::error title=ui-e2e::no Playwright JSON stats ({e}); run exit {rc}")
passed, flaky, skipped, failed = (s.get(k, 0) for k in ("expected", "flaky", "skipped", "unexpected"))
ran = passed + flaky
print(f"ui-e2e: passed {passed}, flaky {flaky}, failed {failed}, skipped {skipped}, exit {rc}")
if rc != 0 or failed or skipped or ran == 0:
    sys.exit("::error title=ui-e2e::end-to-end run failed, skipped tests or ran none (a skip is not a pass)")
PY