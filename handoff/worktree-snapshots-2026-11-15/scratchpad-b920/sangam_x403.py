import io
p = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\SANGAM.md"
s = io.open(p, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
lines = s.split(nl)
ops = next(i for i, l in enumerate(lines) if l.startswith("| Operations (VI.5) |"))
adm = next(i for i, l in enumerate(lines) if l.startswith("| Admin | `TENANT_CREATED`"))
cc = next(i for i, l in enumerate(lines) if l.startswith("Change control (X.5.2), Wave 3, 2026-11-12:"))
assert "`INSTANCE_UPDATED`" not in lines[ops] and "`INSTANCE_MODIFIED`" in lines[ops]
lines[ops] = lines[ops].replace("`INSTANCE_MODIFIED`", "`INSTANCE_MODIFIED`, `INSTANCE_UPDATED` (M-059 update handler: handler, revision, reason)", 1)
assert lines[adm].endswith(" |") and "TEST_CLOCK_CHANGED" not in lines[adm]
lines[adm] = lines[adm][:-2] + ", `EXECUTOR_OWNERSHIP_CHANGED` (X.2.1, class B), `TEST_CLOCK_CHANGED` (M-021, test mode only) |"
lines.insert(cc + 1, "Change control (X.5.2), Wave 3, 2026-11-12 (second entry): the Operations group gains `INSTANCE_UPDATED`, written by the M-059 model-declared update handler on the root chain. The Admin group gains `EXECUTOR_OWNERSHIP_CHANGED` (already named in X.2.1 and the audit-class table, now emitted by `PartitionLeaseManager` in the same transaction as each partition owner change) and `TEST_CLOCK_CHANGED` (written by `TestModeClock` before a test-mode clock change is answered; never emitted unless test mode is on).")
io.open(p, "w", encoding="utf-8", newline="").write(nl.join(lines))
print("ok", ops + 1, adm + 1, cc + 2)
