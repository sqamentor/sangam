import hashlib, datetime
H=r"D:\GITHUB  Project\sangam-handoff"
fs=["backlog-status.json","BACKLOG-STATUS-0.1.0.md","W5-GAP-AUDIT.md"]
h={f:hashlib.sha256(open(H+"\\"+f,"rb").read()).hexdigest().upper() for f in fs}
t=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
hs="; ".join(f"sangam-handoff/{f} {h[f]}" for f in fs)
done=(f"{t} | W5-S | DONE | Status sync and gap audit (no code, no build, no test, never PASS). backlog-status.json _as_of Wave 4 close-out; 154 M-ids: CODED 121, PARTIAL 23, NOT_STARTED 4 (M-018, M-024, M-117 W5-B active, M-128 no lane), REFERENCE 6, BLOCKED 0, OUT_OF_SCOPE 0 whole items. Gap report W5-GAP-AUDIT.md: (a) unowned M-128, M-047, M-103, M-108, M-080 LTV, M-088 UNSURE, M-149 RM rows; (b) decisions without DONE: 127, 116/129/133/178/109 UNSURE, 292-299 in progress, 19/42/37/40/49 UNSURE; (c) open REQUESTs 17:22:41Z gitSync, 20:38:37Z dec 145 inventory, 00:41:24Z PFM coverage map, 00:43:53Z dec 187 UNSURE, 03:15:41Z DEC-223 row, 03:18:48Z DEC-225 row, 07:06:41Z dec 292 port; (d) OPEN register rows without owner SL-111, SL-172, SL-176, SL-179, SL-180, DEC-246, DEC-248, plus 13 stale rows for the integrator. Claim 2026-11-13T07:25:43Z. SHA-256: {hs} | integrator, coordinator, all lanes")
rel=f"{t} W5-S RELEASE {hs} (claim 2026-11-13T07:25:43Z)"
for p,l in [(H+"\WAVE4-SYNC-BOARD.md",done),(H+"\FILE-CLAIMS.md",rel)]:
    b=open(p,"rb").read()
    pre=b"" if b.endswith(b"\n") else b"\r\n"
    open(p,"ab").write(pre+l.encode("utf-8")+b"\r\n")
print(t); print(hs)
