import io, json, datetime
H = r"D:\GITHUB  Project\sangam-handoff"
REG = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md"
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
board = H + r"\WAVE3-SYNC-BOARD.md"
lines = [
 f"{now} | integrator | ACK | W3-C/B44 DONE durable inbound media (CH_INBOUND_SEEN media_* columns, SKIP LOCKED claim + lease, park with INBOUND_MEDIA_FAILED, restart H2 test) accepted. The 14:29:02Z changeset REQUEST is routed to W3-G. Until W3-G posts DONE, the server must not be deployed with this change (production schema lacks the columns).",
 f"{now} | integrator | ACK | W3-X1 Maven inventory and W3-X2 non-Maven inventory received (sangam-handoff/compliance). Their findings feed the E16 synthesis after W3-X3. Shibboleth repository without a group filter (X1 issue 1) is recorded as an M-149 P1 item.",
 f"{now} | integrator | ACK | W3-J2 DONE (en.json hint, M-042 currency filter and amount sort, OperationRefPicker, test-clock tab; tsc exit 0) accepted.",
]
io.open(board, "a", encoding="utf-8", newline="").write("".join(l + "\n" for l in lines))
s = io.open(REG, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
rl = s.split(nl)
row = "| SL-176 | LOW storage (W3-C M-040 media queue) | When a worker stores an inbound media document and is stopped before marking the row done, another worker finishes the row with its own stored copy, so the first copy stays in document storage with no reference. The media itself is not lost and is delivered once. | Delete or reuse the earlier stored copy on takeover (the row keeps media_doc), or sweep unreferenced channel documents; add a restart test that counts stored documents. | OPEN |"
assert row.count("|") == 6
if not any(l.startswith("| SL-176 |") for l in rl):
    i = next(k for k, l in enumerate(rl) if l.startswith("| SL-175 |"))
    rl.insert(i + 1, row)
    io.open(REG, "w", encoding="utf-8", newline="").write(nl.join(rl))
print("ok")
