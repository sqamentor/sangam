import io, json, datetime
H = r"D:\GITHUB  Project\sangam-handoff"
REG = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md"
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
b = [f"{now} | integrator | ACK | W3-I/B50 round 3 DONE accepted: EngineAdapterWorkAdapter ends the lease after outcome-unknown (SangamLeaseTable.endLease), awaiting-reconciliation vectors in 5 engines, OutcomeUnknownNoReofferH2Test on both adapters, python-fastapi job removed (decision 131), Hosting test-only (decision 132); gate exit 0 enforcer on. The hold-window re-offer after an early resolve is recorded as SL-180 for Wave 4 (W4-E). All Wave 3 coding lanes have now reported; the close-out gate starts."]
io.open(H + r"\WAVE3-SYNC-BOARD.md", "a", encoding="utf-8", newline="").write("".join(x + "\n" for x in b))
s = io.open(REG, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
rl = s.split(nl)
row = "| SL-180 | LOW latency (W3-I finding, both work adapters) | When an officer resolves an outcome-unknown item while its work is still inside the 24 h hold, OperatonHeldWorkReleaser frees only parked work (retries 0), so the resolved work is offered again only after the hold ends. Behaviour predates Wave 3. | Release the held work at resolve time (end the hold, then set retries) on both adapters, with an H2 test that a resolved item inside the hold is offered at once under a newer lockVersion; confirm with the owner that an early release is wanted. | OPEN (Wave 4) |"
assert row.count("|") == 6
if not any(l.startswith("| SL-180 |") for l in rl):
    i = next(k for k, l in enumerate(rl) if l.startswith("| SL-179 |"))
    rl.insert(i + 1, row)
    io.open(REG, "w", encoding="utf-8", newline="").write(nl.join(rl))
print("ok")
