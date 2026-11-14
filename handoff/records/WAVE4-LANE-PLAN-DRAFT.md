# Wave 4 lane plan (DRAFT, integrator, 2026-11-12)

Status: draft. It launches only after the Wave 3 close-out gate and the owner-approved Wave 3 checkpoint commit. Every lane follows `WAVE3-LANE-BRIEF.md` house rules 1-10, with the board renamed `WAVE4-SYNC-BOARD.md` and the checkpoint id updated. Owner decisions 1-136 apply, in particular:
- 114: veraPDF runs as a separate validator container;
- 115: contract versions only in DRAFT;
- 116-136: the E16 answers to C-01..C-21.

Sources:
- `BACKLOG-0.1.0-MASTER.md` and `backlog-status.json`;
- `compliance/REMEDIATION-PLAN.md` (RM-01..RM-56), `CI-RELEASE-CONTROLS.md` and `PHASED-PLAN.md`;
- `sangam/TESTING-GAP-REGISTER.md` (SL-170..SL-179).

## Order inside every lane
P0 remediation first (correctness, authorization, money, audit), then the lane's backlog items by priority. This matches the 0.1.0 priority order: correctness, authz, money, audit, Oracle/SQL Server, E2E.

## Lanes

| Lane | Scope (items) | Owns (files) | Depends on |
|---|---|---|---|
| W4-A money and audit | RM-02/RM-03 (decision 136: payment confirmation fail-closed, no trusted channels by default, PAYMENT_ACCEPTED_UNVERIFIED), RM-04/RM-05 (ledger posting, audit row and GL row atomic; refuse GL connector without integration), RM-08 (one power-limit rule), decision 126 part 2 (GL export required where the ledger is enabled) | `sangam-billing`, ledger module, server `bridge/BillingPaymentPort`, `config/LedgerWiring` | W4-M for any new audit event names (X.4.0.3 via integrator) |
| W4-B security | RM-06 (consent deny by default), RM-07 (eSign signature, hash and chain verified), RM-09 (production refuses missing issuer/audience), RM-11 (decision 124: replay deny wins), IdempotencyFilter replay refused when its audit fails, decision 125 (malware scanner required in production, logged waiver), decision 126 part 1 (signed checkpoints for every tenant in production), SL-175, M-141a (security jar split) | `sangam-security`, server `config/SecurityConfig`, `OidcConfig`, `SecurityPermissionBridge`, `IdempotencyFilter`, `AuditCheckpointWiring` | none |
| W4-C supply chain and release | RM-01 + decision 135 (SAML SP closure and Shibboleth repository out of 0.1.0; integrator edits the root pom), M-147 (per-distribution CycloneDX SBOMs, NOTICE, source offers; M-088), M-148 (licence gate per SBOM, strict on every PR (decision 116), pinned actions/images/tools, new-transitive diff, release security gate (decision 133), never PASS when skipped (RM-12)), decision 118 (OS-package rule), decisions 122/123 (DB terms only by operator variable, ojdbc in a CI-only profile), decision 127 (build-tool exception), decision 129 (Helm backup images empty + required), decision 130 (`npm ci --ignore-scripts` everywhere), Grafana out of the air-gap bundle (RM-13), decision 117 elections recorded, M-019 release pipeline (code only, decision 105) | `.github/workflows/**`, `deploy/licence/**`, `deploy/helm/**`, `deploy/compose/**`, `deploy/airgap/**`, `release.yml` | decision 134 downloads logged in `DOWNLOADS-WAVE4` |
| W4-D PDF validator | Decision 114 + 119 (C-04): new module `sangam-pdf-validator` (VeraPdfValidator moved out of sangam-integration), its image, a server remote `Validator` client that fails closed, the scoped Rhino policy entry, SL-178; script-worker image release requirements (decision 128, RM-32) | new module, `deploy/docker/pdf-validator.Dockerfile`, `script-worker.Dockerfile`, `sangam-integration/document/pdf/**` | W4-C for the policy and release jobs (REQUEST) |
| W4-E engine | M-060 continue-as-new, M-056 partition-count admin command and metrics, M-106 engine patches (D-19, D-10, FC-2x), M-114 UTC persistence (decision 104), SL-177 PROCESS_TRACE cleanup, M-136 replay fixtures, M-055 check | `sangam-engine`, `sangam-flowable-ports` engine plugins, `PATCHES.md` | W4-G for tables |
| W4-F hosts and assembly | M-141b/c/d, M-144 host SPI code, M-150 cross-host equivalence kit, M-143 Quarkus only if no release delay | new `sangam-assembly`, `sangam-rest` non-host files, registry/field/geo controllers | W4-B (M-141a) first |
| W4-G persistence and modeller standards | Generator owner (all tables and columns of every lane), M-125 semantic diff, M-126 DMN 1.4/1.5, M-127 CMMN to BPMN converter, M-069 Arazzo, M-131 legacy history | `sangam-persistence` generator, `sangam-modeller` | none |
| W4-H integration breadth | M-105 GraphQL/JDBC/S3/chat connectors, M-070 Temporal HTTP, M-100 JGit, M-103 OCR/IDP port (Tesseract sidecar), M-104 RPA bridge, M-079 DSC agent, M-080 PAdES/CMS, M-083 OPA/Cedar deny-only, M-057 data-lake sink, SL-170 | `sangam-integration` connectors, `sangam-channels` (chat) | downloads per item (approval lines) |
| W4-I platform and operations | M-090 Kubernetes operator, M-092 `sangam-cli upgrade check`, M-102 backup console, M-108 experimental DB tier (non-gating), M-099 SCIM, M-008 replay controller carry | new operator module, `tools/sangam-cli`, `sangam-rest` ops routes (via W4-M) | W4-M |
| W4-J UI | Decisions 120/121 (UI fonts from deploy/fonts, Sakal Bharati and @fontsource dropped), M-043 web components, M-041 CMMN editor, M-101 presence UI, M-109 Teams app, M-048 Playwright suite (browsers need an approval line) | `sangam-ui/**` | W4-M contract |
| W4-K domain packs | M-133 US health payer APIs (decision 107), M-134 PFM/IFMS packs (decision 108, JL data), M-129 AI vector port, M-107 CMMN plan-item extensions, M-135 Flowable live migration, M-089 GeM catalogue generator | `sangam-packs` (packgen/profilegen), domain modules | W4-G tables |
| W4-M contracts and SDK surface | All REST/AsyncAPI shapes for every lane, then the SDK surface regeneration (old W3-I role), M-022 Tier-2 SDK leftovers, M-027 remaining adapters within decision 96/131 | `sangam-rest/api/**`, `gen_openapi.py`, `sdks/**` generated surface | requests from all lanes |

## Integrator-only during Wave 4
- Root `pom.xml`: RM-01, SAML removal, version alignment (C-22 is not blocking; align only with a recorded reason).
- `SANGAM.md` change control, the register, owner decisions and the trackers.
- M-018 guide and HANDOFF.md (Wave 5).

## Not in Wave 4
- M-117 vite 8 and M-019 publishing: Wave 5.
- M-142: release 0.2.
- Django, Laravel, Symfony and FastAPI adapters: deferred (decisions 96, 131).
- C-22..C-37: test phase or release.
