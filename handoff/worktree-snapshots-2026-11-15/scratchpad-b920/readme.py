import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui')
sub('README.md', "| jsdom, @testing-library/dom, @testing-library/react (dev) | 25.0.1 / 10.4.0 / 16.1.0 | MIT | Test environment |\n",
    "| jsdom, @testing-library/dom, @testing-library/react (dev) | 25.0.1 / 10.4.0 / 16.1.0 | MIT | Test environment |\n| axe-core (dev) | 4.14.0 | MPL-2.0 | Accessibility checks in component tests (M-046, `src/test/axe.ts`, `src/a11y.test.tsx`); not shipped |\n")
sub('GIGW-CONFORMANCE.md', """## Automated checks (axe-core): waiting for the download

- **Plan.** Run axe-core on every component test (vitest + jsdom) and on the E2E journeys (M-048), and fail CI on any `serious` or `critical` violation.
- **Dependency needed.** axe-core is MPL-2.0, which `deploy/licence/licence-policy.json:6` allows. It is **not installed**, and per the Wave rules nothing is downloaded without approval. See the download request in the W2-C report.
- **Not built yet.** Until the download is approved, no axe helper or CI step exists. The `ui.yml` gate step belongs to the integrator (W2-C does not own `.github/workflows/ui.yml`).""", """## Automated checks (axe-core)

- **Installed.** axe-core 4.14.0, exact-pinned dev dependency (download batch 2, approved CI-first; MPL-2.0, allowed by `deploy/licence/licence-policy.json:6`; no transitive dependencies). It is never shipped.
- **Check.** `src/test/axe.ts` runs WCAG 2.0/2.1 A and AA rules and fails on any `serious` or `critical` violation. `color-contrast` is off in jsdom (no layout); contrast is checked in the E2E run (M-048) and by the auditor.
- **Gate.** `src/a11y.test.tsx` covers the shared components and the W2-C screens. It runs in `npm test`, which `ui.yml` runs on every UI change, so no workflow change is needed. Other screens join as M-047 adds their component tests (W3-J), and the E2E journeys follow with M-048.""")
sub('GIGW-CONFORMANCE.md', "4. axe-core in component tests and E2E, with a CI gate (waiting for the download approval).", "4. axe-core on the remaining screens (with their M-047 component tests) and in the E2E journeys (M-048).")
