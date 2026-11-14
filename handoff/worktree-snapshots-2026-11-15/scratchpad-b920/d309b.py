import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import edit

edit('README.md', [(
    "- `@csstools/color-helpers` (MIT-0, OSI-approved, a jsdom test dependency) is reported as `[CHECK]` by `npm run licences` because MIT-0 "
    "is not yet in the script's allow list; that allow list is licence policy, so the change has been requested from deploy/licence (W4-C);\n",
    "- `@csstools/color-helpers` is MIT-0 (OSI-approved), a jsdom test dependency. MIT-0 is in `scripts/list-licences.mjs`'s allow list, "
    "as it is in `deploy/licence/licence-policy.json` (coordinator ruling 2026-11-13), so `npm run licences` reports 0 packages needing a check;\n",
)])
