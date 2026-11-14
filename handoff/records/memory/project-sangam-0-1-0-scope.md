---
name: project-sangam-0-1-0-scope
description: "Sangam 0.1.0 scope as the owner decided on 2026-11-12. Every known item is in, except multilingual UI. English only."
metadata:
  node_type: memory
  type: project
  originSessionId: 6e98c2a7-cdac-4783-9098-832546572812
  modified: 2026-11-12T11:51:12.791Z
---

**Scope.** Owner decisions of 2026-11-12, in order:
- 0.1.0 includes EVERY KNOWN item from the master backlog (`sangam-handoff\BACKLOG-0.1.0-MASTER.md`, 136 M-ids). The owner confirmed this after seeing the estimate of 742–1,827 engineer-days.
- Only unknown or unrecorded items wait for 0.2.

**Exception (Q-06): the initial release is ENGLISH ONLY.**
- Deferred to a later release: Hindi and every other translation, BHASHINI, translation-review workflows, ≥10 locales, diglot templates, RTL.
- Kept in 0.1.0:
  - externalized strings and stable keys;
  - Unicode support, and locale-aware formatting (currency comes from the tenant locale);
  - English as the default and fallback;
  - the existing i18n infrastructure. Do NOT remove `hi.json`.
- Hindi and other Unicode business data, and original-language regulatory documents, must still be accepted, stored and displayed correctly.
- Reassess languages before a State-facing pilot. Register row: SL-147.

**Exception: host architecture (owner directive 2026-11-12, OWNER-DECISIONS 54R/55R, master backlog E15 M-137..M-143).**
- Target is Option B, done incrementally: a plain-Java assembly layer, with Spring and Quarkus as thin, replaceable hosts. The Spring standalone product stays the baseline. No duplicated wiring and no custom DI framework.
- Full extraction is NOT required for 0.1.0. If Quarkus would delay the release, 0.1.0 ships with no Quarkus host (Option C), and the extraction stays a phased backlog.
- Option A (engine SDK) is allowed only with an explicit capability boundary, and is never called full support.
- JVM only. Native needs separate evidence.
- Without Quarkus, only declared host conveniences may be lost. Money correctness, authorization, audit, transaction integrity and duplicate prevention never weaken; a missing required capability BLOCKS the affected operation.

**Approvals (2026-11-12):** the owner approved "end to end all things needed currently" (decision 56: Wave 3 Maven downloads within licence-policy, logged in DOWNLOADS-WAVE3-2026-11-12.md). Not covered: images, npm, or licences outside the policy.

**Priority inside 0.1.0** (owner): core workflow correctness, real authorization, financial controls, auditability, Oracle/SQL Server compatibility, end-to-end acceptance.

**Why:** the owner wants one complete first phase, with the effort going to correctness rather than to translation.

**How to apply:**
- Plan waves from the master backlog and `backlog-status.json`.
- Never add translation work to 0.1.0.
- Rank work by the priority list above when lanes compete.

Related: [[feedback-sync-after-every-item]], [[feedback-code-first-then-push-then-test]], [[project-sangam-workflow-engine]].
