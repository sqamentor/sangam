import io, json, datetime, hashlib
H = r"D:\GITHUB  Project\sangam-handoff"
W = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
REG = W + r"\sangam\TESTING-GAP-REGISTER.md"
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
s = io.open(REG, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
rl = s.split(nl)
def setstatus(rid, new):
    i = next(k for k, l in enumerate(rl) if l.startswith(f"| {rid} |"))
    cells = rl[i].split(" | ")
    # last cell is "status |"
    assert rl[i].endswith(" |")
    head = rl[i][:rl[i].rstrip(" |").rfind(" | ")]
    rl[i] = head + " | " + new.rstrip(" |") + " |"
    assert rl[i].count("|") == 6, (rid, rl[i].count("|"))
setstatus("SL-166", "PARTIAL. Finding 6 FIXED (404 for an unknown notice, MeetingNoticeAckH2Test); findings 2, 3, 4 coded by W3-M under owner decisions 63-66 (board 405-407); finding 1 routed to W3-D (FORM_READ for tenant admin); finding 5: the server is right (X-Purpose carries the purpose code) but the UI still sends free text (registry.tsx:190), routed to W3-J2 at the Wave 3 close-out (board REQUEST 163). |")
setstatus("SL-167", "FIXED in tree (owner decision 62): the reason is recorded in ACTION_INVOKED through the ActionModels.invoke overload (W3-M, board 405); test written, not run. |")
setstatus("SL-169", "FIXED in tree (W3-E, board 317 and 380): visibility, privacy, consent, publish and system-of-record ports use the caller's Connection; RegistryPortConnectionH2Test written, not run. |")
setstatus("SL-174", "FIXED in tree (W3-I, board 498 and the 15:15Z/15:39Z DONE lines): all five test engines follow the server order (410 deleted, 404 no task, else lease check), incident and awaiting-reconciliation vectors shared in sdks/testing/work-step-problems.json; tests written, not run. |")
row = "| SL-181 | MEDIUM security (W3-D REQUEST board line 80 part c, M-085) | Field re-encryption has sources for REST_DOCUMENT kinds and form drafts, but none for the secret values held by SecretAdmin, so a key rotation never re-encrypts stored secret values. | Implement a ReencryptionSource for the SecretAdmin store (keyset page, envelope extract and replace, compare-and-set update) and register it with FieldReencryptionWiring, with an H2 test that a rotated secret reads back under the new key. | OPEN (Wave 4, W4-B) |"
assert row.count("|") == 6
if not any(l.startswith("| SL-181 |") for l in rl):
    i = next(k for k, l in enumerate(rl) if l.startswith("| SL-180 |"))
    rl.insert(i + 1, row)
io.open(REG, "w", encoding="utf-8", newline="").write(nl.join(rl))
def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
pol = W + r"\sangam\deploy\licence\licence-policy.json"
pom = W + r"\sangam\pom.xml"
b = [
 f"{now} | integrator | ACK | Audit X5 item 2: REQUEST 80 part (c) (SecretAdmin re-encryption source) is deferred to Wave 4 lane W4-B as register SL-181; parts (a)(b) were closed by W3-D DONE 395 (RestStoreReencryptionSources).",
 f"{now} | integrator | ACK | Audit X5 item 3: REQUEST 163 (UI sends the purpose code in X-Purpose) routed to W3-J2 at the close-out; SL-166 register text corrected.",
 f"{now} | integrator | ACK | Audit X5 item 4: REQUEST 210 (M-059 response codes in the updateProcessInstance and queryProcessInstance descriptions) routed to W3-M; W3-I regenerates the SDK surface after W3-M's DONE.",
 f"{now} | integrator | ACK | Audit X5 item 5, closures on the board: REQUEST 167 closed by the register rows SL-166/167/168 (now updated); REQUEST 266 closed by the M-141a..d backlog rows (integrator 20:18Z); REQUEST 268 closed by W3-A's own pom change at 14:42:34Z (root pom manages jnats/jedis; W3-E released the pom); REQUEST 296 closed by W3-D DONE 396 (M-077 server token path); REQUEST 204 parts (a)(b) granted to W3-H and closed by W3-H DONE 444; BROKEN 264 (PdfFontSet in flight) closed by W3-E DONE 14:39:11Z and the sangam-integration gate EXIT 0.",
 f"{now} | integrator | ACK | Audit X5 item 6 (W3-A closed its own items at 15:50:00Z: board 542-544, FILE-CLAIMS 1347-1348; PATCHES row for ExternalWorkAnnouncer present per W3-F 15:02:12Z): full SHA-256 recorded above for board lines 133, 380, 394-396, 444, 457, 458 and 193 (sangam-field pom.xml and README.md ambiguous by name, covered by W3-L's release); the W3-D 28-file scope is covered by the current hashes in the W3-D INFO line. Prefix-only hashes stay a process slip on record.",
 f"{now} | integrator | DONE | Audit X5 item 7, integrator edits on record: sangam/deploy/licence/licence-policy.json {sha(pol)} (decisions 90, 94, 95, 102) and sangam/pom.xml {sha(pom)} (W3 dependency management, Shibboleth repository, veraPDF/pdfbox/opensaml versions).",
 f"{now} | integrator | INFO | Audit X5 item 8: the integrator standing claim (FILE-CLAIMS 1021: TESTING-GAP-REGISTER.md and the handoff trackers) is an accepted exception at the Wave 3 checkpoint.",
 f"{now} | integrator | INFO | Audit X5 item 9: register SL-166, SL-167, SL-169, SL-174 synced; leftovers assigned: M-057 data-lake sink to W4-H, partition-count admin command (M-056) and M-060 continue-as-new to W4-E (WAVE4-LANE-PLAN-DRAFT.md).",
]
io.open(H + r"\WAVE3-SYNC-BOARD.md", "a", encoding="utf-8", newline="").write("".join(x + "\n" for x in b))
io.open(H + r"\FILE-CLAIMS.md", "a", encoding="utf-8", newline="").write(f"{now} integrator CLAIM+RELEASE (audit X5 item 7) sangam/deploy/licence/licence-policy.json {sha(pol)}; sangam/pom.xml {sha(pom)}\n")
print("ok")
