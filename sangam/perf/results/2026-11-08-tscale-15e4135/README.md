# T-SCALE runs of 2026-11-08 (source commit 15e4135)

Raw results published per `../../README.md` (R22: publish raw results and limits with every run, including failed
thresholds). Claims and thresholds: `../../../SCALE-ARCHITECTURE.md` section 11.

* Source: detached worktree `D:\wt-scale` at commit `15e4135f3c1462b426cf22e309cd4c542f977de6`
  ("Sangam testing phase: root-cause fixes, acceptance suite, gap register"), run on 2026-11-08 with
  `TScaleLoadIT` (`sangam-server/src/test/java/org/sangam/server/acceptance/ix8/TScaleLoadIT.java`), PostgreSQL only.
* `base/`: the measured run (cpus=8 maxHeapMB=8160 hikariMax=20 starters=32 workers=8 reviewers=16 warmup=60s
  measure=300s): 11.6 starts/s, the figure in register row SG-15.
* `smoke/`: the short smoke run (same settings, warmup=15s measure=30s): 8.7 starts/s.
* Each folder holds `report.txt` and `timeline.csv`, copied byte for byte from the run output.
* Left out: the JFR recordings (`tscale.jfr`, 41 MB base and 7 MB smoke; the repository tracks no `.jfr` files) and the
  event dumps (`ev.json`: 1.6 GB smoke, empty base).
* These numbers predate the SG-15 T-SCALE fixes; they are the before-fix baseline, not a result of the current code.
