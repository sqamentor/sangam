import io, json, datetime, hashlib
H = r"D:\GITHUB  Project\sangam-handoff"
W = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
_unused = (
 f"\n## 115. API contract versions are stored only while the package is DRAFT ({now}, owner via AskUserQuestion)\n\n"
 "Question (W3-B, M-068): a contract version could be stored after its package was submitted or approved, and an unversioned operationRef pins the newest version at deploy, so an unreviewed contract could enter a deployed model. "
 "Answer (recommended option): PUT of a contract version answers 409 unless the package is DRAFT, matching the hash freeze at submit (R43). Every newly stored version writes the audit event API_CONTRACT_STORED in the same transaction (an identical re-send that returns the stored version writes none); an audit failure rolls the store back.\n")
# SANGAM.md change control
p = W + r"\SANGAM.md"
s = io.open(p, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
lines = s.split(nl)
if not any("API_CONTRACT_STORED" in l for l in lines):
    pk = [i for i, l in enumerate(lines) if l.startswith("| Admin | `TENANT_CREATED`") and "`PACKAGE_RETIRED`" in l]
    assert len(pk) == 1, pk
    cc = max(i for i, l in enumerate(lines) if l.startswith("Change control (X.5.2), Wave 3"))
    lines.insert(cc + 1, "Change control (X.5.2), Wave 3, 2026-11-12 (third entry, owner decision 115): the package events gain `API_CONTRACT_STORED` (M-068), written by `JdbcApiContractStore` in the same transaction as each newly stored contract version (tenant, package, contract id, version, actor = created_by); storing is refused with 409 unless the package is DRAFT. Owner decision 114: the PDF validator (veraPDF) runs as a separate container; the server answers 422 `FORMAT_NOT_AVAILABLE` for PDF until its client lands.")
    if pk:
        i = pk[0]
        assert lines[i].endswith(" |")
        lines[i] = lines[i][:-2] + ", `API_CONTRACT_STORED` (M-068, decision 115) |"
    io.open(p, "w", encoding="utf-8", newline="").write(nl.join(lines))
sp = H + r"\backlog-status.json"
j = json.load(open(sp, encoding="utf-8"))
j["M-068"]["note"] = "JDBC ApiContractStore + 4 routes + ApiContractWiring (W3-B DONE 15:00:55Z, gate exit 0 enforcer on); UI picker (W3-J2); decision 115 DRAFT-only + API_CONTRACT_STORED (W3-B); pinner wiring W3-F in progress"
j["M-066"].update(status="CODED", note="decision 101 notify-only: ExternalWorkAnnouncer engine plugin, after-commit payload-free hint on the shared sangamWakeupNotifier (pg_notify / in-JVM / NATS-Redis bus), WorkAnnounceWiring (W3-A); PATCHES allow-list B row present (W3-F)")
j["M-057"].update(status="PARTIAL", note="decision 88 opt-in: search-index and Kafka history sinks, HistoryProjectionWiring, outbox routing of sangam.history.* (W3-A); data-lake sink Wave 4 (no object-store connector)")
open(sp, "w", encoding="utf-8", newline="\n").write(json.dumps(j, ensure_ascii=False, indent=2) + "\n")
rd = W + r"\sangam\sangam-server\README.md"
h = hashlib.sha256(open(rd, "rb").read()).hexdigest().upper()
hs = hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
b = [
 f"{now} | integrator | ACK | W3-A/B42 DONE (M-066 announcer, Q4 SecretResolver chain, M-057 search + Kafka sinks, pom clean-up; gate exit 0 target-w3a) accepted. integration pom hash 09BBC0F5 matches W3-A's 14:42:34Z RELEASE (file last written 14:36:14Z), so no unknown edit. PATCHES row already present (W3-F 15:02:12Z).",
 f"{now} | integrator | ACK | W3-B/B43 DONE M-068 store + routes + wiring accepted. Owner decision 115: contract PUT only while the package is DRAFT (409 otherwise) + API_CONTRACT_STORED audit event in the same transaction; routed to W3-B. W3-F told to wire the pinner now.",
 f"{now} | integrator | DONE | sangam-server/README.md rows for sangam.work.announce.* and sangam.history.projection.* plus the decision 114 note (SHA-256 {h}); SANGAM.md X.4.0.3 + change-control line for API_CONTRACT_STORED and decision 114 (SHA-256 {hs}).",
]
io.open(H + r"\WAVE3-SYNC-BOARD.md", "a", encoding="utf-8", newline="").write("".join(x + "\n" for x in b))
io.open(H + r"\FILE-CLAIMS.md", "a", encoding="utf-8", newline="").write(f"{now} integrator CLAIM+RELEASE docs: sangam/sangam-server/README.md {h}; SANGAM.md {hs}\n")
print("ok")
