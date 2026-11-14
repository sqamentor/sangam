---
name: feedback-evidence-based-97-confidence
description: "Owner rule (2026-11-12, \"follow this pattern always\"): code only on gathered evidence; code a decision only at >=97% confidence, otherwise first confirm and clear the logic"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 6e98c2a7-cdac-4783-9098-832546572812
  modified: 2026-11-11T18:34:28.598Z
---

On 2026-11-12 the owner said every coding decision must rest on evidence: what has been gathered, learned, seen in the system, and found so far. Nothing may be coded blindly. A change is coded only when confidence is at least 97%. Below that, the logic is confirmed and cleared first (by reading source, logs and authoritative docs, or by asking the owner or IFMS), and only then coded. The owner said: "follow this pattern always".

**Why:** the owner wants no guessed code going into main, especially now that all code is written before the test phase.

**How to apply:**
- Every agent brief and every report carries the evidence (file:line, log line, register or ledger reference, decision quote) and a confidence figure for each design decision.
- Anything below 97% is listed as "needs confirmation", with the open question, and is not coded until it is resolved.
- This sits alongside [[feedback-root-cause-only]] (≥99.99% for root-cause claims) and [[feedback-code-first-then-push-then-test]].
