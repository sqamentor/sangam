# E12–E15: security, viability, documentation and ecosystem (Operaton 2.2.0 vs Flowable 8.0.0)

Date: 2026-11-04. Raw data: `E12.json`, `E13.json`, `E14.json`, `E15.json` (same folder).

Markers:
- **[F]** fact from a cited source or a measurement;
- **[I]** judgement;
- "not found" means searched for and not located. It does not mean "does not exist".

These four dimensions are **decision support only**. They are not part of the VI.7 weighted score in `scorecard.md`, and its weights are unchanged.

---

## Summary

| Dimension | Operaton 2.2.0 | Flowable 8.0.0 | Knock-out |
|---|---|---|---|
| E12 Security | **8.5** | **3.5** | Both PASS. Flowable passes on condition: its XXE in the engine itself (CVE-2026-91197) has no fix and must be mitigated in the product layer |
| E13 Viability and governance | **7.5** | **6.5** | Both PASS. Operaton is AMBER: one maintainer writes about 73–80 % of human commits |
| E14 Documentation and support | **8.5** | **8.0** | Both PASS |
| E15 Ecosystem and compatibility | **8.5** | **5.5** | Both PASS |
| Unweighted mean (information only) | 8.25 | 5.9 | — |

**No engine fails a must-have.**

These results do not contradict the Stage B result (Operaton). Two risks are new for Operaton:
- the bus factor;
- the 6-month support window with no LTS (E13).

The ADR should record both explicitly.

---

## E12 Security

### Method
1. **Dependency trees.** From `spike/`, ran `mvnw.cmd -q -pl adapter-operaton|adapter-flowable dependency:list -DincludeScope=runtime -DoutputFile=…`. `product-api` was excluded.
   - The trees are the **spike adapters' embedded engine trees**. They contain no REST, web apps or Spring Boot starters.
   - Operaton's Jackson 2.22.1 is pinned by the spike (`adapter-operaton/pom.xml`, ledger W-1) to the line that `jackson-module-scala` requires.
   - Flowable's `tools.jackson` 3.0.1 comes from Flowable 8.0.0 itself.
2. **Dependency vulnerabilities.** Every `group:artifact:version` was sent to `POST https://api.osv.dev/v1/querybatch` (ecosystem Maven). Details came from `https://api.osv.dev/v1/vulns/{id}`.
3. **Advisories for the engines themselves.** Three GitHub sources:
   - `GET /repos/{repo}/security-advisories`;
   - `GET /advisories?ecosystem=maven&affects=<pkg>` for 10 engine and web-app artifacts (including `org.camunda.bpm:camunda-engine`);
   - `GET /advisories?cve_id=CVE-2026-91197`.
   - NVD was also checked: `services.nvd.nist.gov/rest/json/cves/2.0?cveId=CVE-2026-91197`.
4. **Score:** 10 − 3 per unfixed engine-level HIGH/CRITICAL − 0.25 per distinct HIGH in a dependency with a fix available − 0.1 per MODERATE. A CRITICAL with no fix is a knock-out.

### Counts [F]

| | Operaton 2.2.0 | Flowable 8.0.0 |
|---|---|---|
| Runtime artifacts queried | 33 | 67 |
| CRITICAL / HIGH / MODERATE / LOW (dependencies) | **0 / 5 / 2 / 0** | **0 / 10 / 10 / 1** |
| Affected artifacts | `com.fasterxml.jackson.core:jackson-databind` 2.22.1, `jackson-core` 2.22.1 | `tools.jackson.core:jackson-databind` 3.0.1, `jackson-core` 3.0.1, `org.springframework:spring-core` 7.0.3 (LOW) |
| Fix available for every dependency finding? | Yes: same line (2.22.2 / 2.22.3) | Yes, but only on a newer minor (3.1.4–3.1.7 / 3.2.x). That override is untested with Flowable 8.0.0 [I] |
| Repo security advisories (GitHub) | 0 | 0 |
| Global GitHub advisories for engine packages (`affects=`) | 0 | 0 (the CVE below has no package mapping, so `affects=` and OSV do not match it) |
| Engine-level CVE | none found | **CVE-2026-91197 / GHSA-8x6x-vw5h-q563**, unfixed |

### Every CRITICAL/HIGH

There are no CRITICAL findings in either tree.

| ID (alias) | Artifact @ version | Engine | Fixed in | Summary (short) | Reachable in our use? **[I]** |
|---|---|---|---|---|---|
| GHSA-cxp5-3px4-pw24 (CVE-2026-91777) | jackson-databind 2.22.1 / 3.0.1 | both | 2.22.3 · 3.1.7 / 3.2.3 | quadratic forward-reference completion (DoS) | Only where untrusted JSON reaches engine-side Jackson (JSON/FEEL variables). Low–moderate |
| GHSA-wv8q-qhhj-9h54 (CVE-2026-91776) | jackson-databind 2.22.1 / 3.0.1 | both | 2.22.3 · 3.1.7 / 3.2.3 | retains unknown raw type IDs (DoS) | Needs polymorphic typing on untrusted input. Unlikely |
| GHSA-q4xh-88c3-wmh7 (CVE-2026-68497) | jackson-databind 2.22.1 / 3.0.1 | both | 2.22.2 · 3.1.6 / 3.2.2 | Duration/XMLGregorianCalendar number parse (DoS) | Unlikely |
| GHSA-7hhh-6rmp-j9qf (CVE-2026-89425) | jackson-core 2.22.1 / 3.0.1 | both | 2.22.3 · 3.1.7 / 3.2.3 | missing maxErrorTokenLength (DoS) | Low |
| GHSA-p6pp-m3f8-5c89 (CVE-2026-89407) | jackson-core 2.22.1 / 3.0.1 | both | 2.22.3 · 3.1.7 / 3.2.2 | ReDoS in float pattern | Low–moderate |
| GHSA-j3rv-43j4-c7qm (CVE-2026-54512) | jackson-databind 3.0.1 | Flowable | 3.1.4 | PolymorphicTypeValidator bypass via generics | Needs default typing. Unlikely in engine defaults |
| GHSA-rmj7-2vxq-3g9f (CVE-2026-54513) | jackson-databind 3.0.1 | Flowable | 3.1.4 | array subtype allowlist bypass | Unlikely |
| GHSA-2m67-wjpj-xhg9 (no CVE) | jackson-core 3.0.1 | Flowable | 3.1.1 | document length constraint bypass (DoS) | JSON variables and event payloads. Low–moderate |
| GHSA-6v53-7c9g-w56r (CVE-2026-29062) | jackson-core 3.0.1 | Flowable | 3.1.0 | nesting depth bypass, DataInput parser (DoS) | Low |
| GHSA-r7wm-3cxj-wff9 (CVE-2026-68494) | jackson-core 3.0.1 | Flowable | 3.1.4 | async parser number-length bypass (DoS) | Low |

All OSV records: `https://osv.dev/vulnerability/<ID>`. MODERATE/LOW entries are listed in `E12.json`.

### Engine-level advisory: CVE-2026-91197 (SANGAM.md X-14)

The CVE is **now verified [F]**. This upgrades X-14's "CVE ID [U]" to [F].

| Field | Value |
|---|---|
| Records | [GHSA-8x6x-vw5h-q563](https://github.com/advisories/GHSA-8x6x-vw5h-q563) ("unreviewed", severity *high*); [NVD](https://nvd.nist.gov/vuln/detail/CVE-2026-91197) "Awaiting Analysis", published 2026-09-14 |
| Weakness | CWE-611 XXE in `ProcessDiagramLayoutFactory.parseXml()`, Flowable "through 8.0.0". This matches X-14's local finding |
| CVSS | 3.1: 6.5 (`AV:N/AC:L/PR:L/UI:N/C:H`); 4.0: 7.1 |
| Fix | **None released.** [Issue #4216](https://github.com/flowable/flowable-engine/issues/4216): open since 2026-06-15, 0 comments. [PR #4254](https://github.com/flowable/flowable-engine/pull/4254): open and unmerged since 2026-08-09, from an external contributor. There has been no Flowable release since 8.0.0 (2026-02-27) |
| Reachable? **[I]** | It needs deploy privilege and a call to the diagram-layout API (`getProcessDiagramLayout` / REST diagram-layout). Grep of the spike code finds no use. The R24 hardened-XML rule (reject DOCTYPE at deploy) mitigates it |

Operaton: no advisory was found, either in the repo or in the global database for `org.operaton.*`.

### Knock-out
- **No CRITICAL** in either tree, and every dependency HIGH has an upstream fix. Both PASS.
- **Flowable passes on condition [I].** It has an unfixed HIGH-rated (CVSS 3.1: 6.5) vulnerability in the engine itself, with no vendor response in 4.5 months. If chosen, the product layer would have to:
  - block the diagram-layout API;
  - reject DOCTYPE;
  - accept that a minor-line Jackson override is needed (3.0 → 3.1/3.2).

### Score
- **Operaton:** 10 − 5 × 0.25 − 2 × 0.1 = **8.5**.
- **Flowable:** 10 − 3 (unfixed engine XXE) − 10 × 0.25 − 10 × 0.1 = **3.5**.

---

## E13 Project viability and governance

### Method
- **Data:** GitHub REST API, read-only, using the local `gh` token. The window ends 2026-11-04.
- **Commits:** default branch only. Bots are excluded from "human" figures (`dependabot[bot]`, `github-actions[bot]`).
- **Counts:** issue and PR counts come from the search API.
- **First response:** the first comment by someone other than the author and not a bot; failing that, the first non-author event (label, assign, close, reference).
- **Sample:** up to 50 issues closed in the last 182 days.

### Measurements [F]

| Metric | Operaton (`operaton/operaton`) | Flowable (`flowable/flowable-engine`) |
|---|---|---|
| Licence | Apache-2.0 | Apache-2.0 |
| Releases in the last 24 months | **19 GA**. Also 4 betas, 2 RCs, 2 milestones and 2 SNAPSHOT tags | **2 GA**: 7.2.0 (2025-08-21), 8.0.0 (2026-02-27). Maven Central agrees |
| Cadence | Monthly patches. Minors every 6 months: 1.0 (2025-11), 1.1 and 2.0 (2026-02/03), 2.1 (2026-04), 2.2 (2026-10-23) | About one release per 6 months. **No OSS patch release since 8.0.0** |
| Last GA | 2.2.0 / 2.1.6, 2026-10-23 | 8.0.0, 2026-02-27 (8 months ago) |
| Commits, last 12 months (all / human) | 2,394 / 1,406 (941 from dependabot) | 166 / 165 |
| Commits, last 6 months | 912 | 63 |
| Distinct authors, 12 months (human) | 34 | 20 |
| Bus factor: top-3 share of human commits, 12 months | **84.3 %** (kthoms 72.5 %; 74.3 % with the "Karsten Thoms" alias; 86.5 % top-3 if Copilot's 94 commits are excluded) | **79.4 %** (filiphr 43 %, tijsrademakers 32 %) |
| Authors needed for 50 % of human commits | 1 | 2 |
| Issues open / closed | 44 / 575 (7 % open) | 382 / 1,045 (27 % open) |
| Issues opened / closed, last 12 months | 199 / 209 | 56 / 31 |
| Open PRs; PRs merged in 12 months | 22; 2,062 | 62; 57 |
| Median time to first response (issues closed in last 6 months) | **76 h** (n = 50; p75 182 h; 10 without a non-author response) | **9 h** (n = 9 only, **low confidence**; p75 136 h) |
| Stars / forks | 515 / 139 (repo created 2024-10) | 9,635 / 2,904 (since 2016) |
| Governing entity | Community fork of Camunda 7 CE: "community-owned … (legal work in progress)", with a planned non-profit ([operaton.org](https://operaton.org/), [FAQ](https://operaton.org/faq)). 14 independent service providers ([list](https://operaton.org/service-providers)) | **Flowable AG**, open core: Apache-2.0 engines; the commercial "Agentic Case Platform" / Enterprise Cloud is proprietary ([open-source page](https://www.flowable.com/open-source)) |
| Support window | **About 6 months per minor, no LTS**. Patches go only to the current minor ([roadmap](https://operaton.org/roadmap), [FAQ](https://operaton.org/faq)). Extended maintenance only through providers | No OSS maintenance or LTS policy found. Vendor SLAs (Silver/Gold/Platinum) are tied to subscriptions ([support](https://www.flowable.com/support)) |

**Observation [I]:** Flowable's public repo has low commit volume (about 14 commits a month), and only 9 issues were closed in 6 months. This fits a vendor that develops mainly for its commercial product and publishes OSS releases rarely. The unanswered XXE issue (E12) points the same way.

### Score

Method:
- recency 0–2;
- cadence 0–2;
- human commits 0–1.5;
- contributors 0–0.5;
- bus factor 0–1.5 (top-3 share < 60 % = 1.5, 60–80 % = 0.75, > 80 % = 0);
- first response 0–1;
- governance 0–1 (legal entity with funding = 1; community with legal entity in progress = 0.5);
- backlog 0–0.5.

The thresholds are in `E13.json`.

| | Recency | Cadence | Commits | Contrib. | Bus | Response | Governance | Backlog | **Total** |
|---|---|---|---|---|---|---|---|---|---|
| Operaton | 2 | 2 | 1.5 | 0.5 | 0 | 0.5 | 0.5 | 0.5 | **7.5** |
| Flowable | 1 | 1 | 1 | 0.5 | 0.75 | 1 | 1 | 0.25 | **6.5** |

### Knock-out
- **No release in 12 months:** both PASS. Flowable's last release is 8 months old.
- **Single-maintainer bus factor:**
  - **Operaton: AMBER, not a fail [I].** There are 34 human contributors, but one person authors about 73–80 % of human commits, and 1 author covers 50 %.
  - Flowable relies on two employees of one company (79 %), backed by that company.
- **Additional risk [I] for a 10-year product:**
  - Operaton has no LTS. The product must either track a new minor every 6 months or buy extended maintenance from a provider.
  - Flowable's OSS line gets no patch releases at all between minors.

---

## E14 Documentation and support

### Method
- **Pages:** HTTP status of each page (`curl -L`) and the sites' sitemaps.
- **Last-updated dates:** the last commit on the doc's source path. Operaton's docs live in [`operaton/documentation`](https://github.com/operaton/documentation); Flowable's OSS docs live in `flowable/flowable-engine/docs/docusaurus`.
- **Forums:** Discourse `/about.json` stats and `/latest.json?order=created`. These count topics created per month and the posts in them. One Flowable page timed out, so November 2025 may be partial.

### Docs coverage [F]

| Topic | Operaton (page · source last changed) | Flowable (page · source last changed) |
|---|---|---|
| Embedding / engine config | [process engine](https://docs.operaton.org/docs/documentation/user-guide/process-engine/), [Spring Boot](https://docs.operaton.org/docs/documentation/user-guide/spring-boot-integration/) · 2026-11-02 | [Configuration](https://flowable.com/open-source/docs/bpmn/ch03-Configuration), [Spring Boot](https://www.flowable.com/open-source/docs/bpmn/ch05a-Spring-Boot) · 2026-03-16 |
| REST API | [REST reference](https://docs.operaton.org/docs/documentation/reference/rest/) · 2026-11-04 | [BPMN REST](https://flowable.com/open-source/docs/bpmn/ch14-REST) · chapter 2024-01-02; OpenAPI `docs/public-api` 2026-02-27 |
| External tasks / workers | [external tasks](https://docs.operaton.org/docs/documentation/user-guide/process-engine/external-tasks/) 2026-06-21; [ext-client](https://docs.operaton.org/docs/documentation/user-guide/ext-client/) 2026-10-26 | **Not in the OSS docs** (not found in the sitemap). It is only in the commercial documentation site: [external clients](https://documentation.flowable.com/latest/develop/external-clients/) |
| DMN | [DMN 1.3 reference](https://docs.operaton.org/docs/documentation/reference/dmn/) · 2026-10-26 | [DMN](https://flowable.com/open-source/docs/dmn/ch02-Configuration) · 2025-07-11 |
| CMMN | [CMMN 1.1 reference](https://docs.operaton.org/docs/documentation/reference/cmmn11/) · 2026-10-26 | [CMMN](https://flowable.com/open-source/docs/cmmn/ch02-Configuration) · 2025-12-15 |
| Migration / upgrade | [Camunda 7 → Operaton](https://docs.operaton.org/docs/documentation/update/camunda-to-operaton) · 2026-06-21. The update section has only an index plus this page. **No guide from one Operaton minor to the next was found** | [Flowable 5 → 6](https://www.flowable.com/open-source/docs/migration) only (source last changed 2023-09-20). **No 6 → 7 or 7 → 8 upgrade guide found in the OSS docs.** Commercial how-to: [howto-migration](https://documentation.flowable.com/latest/howto/howto/howto-migration) |

### Support, community and training [F]

| | Operaton | Flowable |
|---|---|---|
| Commercial support | 14 independent providers (DE, CH, PL, FI, UK, BG, BR, MX). None is the project owner ([list](https://operaton.org/service-providers)) | The vendor (subscription SLAs; [support](https://www.flowable.com/support)), plus 48 listed partners, including Accenture, TCS, NTT DATA, CGI and Swisscom ([partners](https://www.flowable.com/company/partners)). Paid support specifically for the OSS engines is not stated |
| Forum | [forum.operaton.org](https://forum.operaton.org): 1,503 posts total. Last 30 days: 23 posts, 4 topics. Topics created per month, 2026-05 to 2026-10: 5, 8, 2, 3, 5, 5. Average about 13 posts a month in those topics. GitHub Discussions: disabled | [forum.flowable.org](https://forum.flowable.org): 28,136 posts total. Last 30 days: 9 posts, 1 topic. Topics per month, 2026-05 to 2026-10: 5, 5, 4, 2, 4, 1. Average about 13 posts a month. Declining. GitHub Discussions: disabled |
| Training | No official training. Offered by providers such as itemis, Otus, uubato, BeOne and nterra (provider list) | Official: [training.flowable.com](https://training.flowable.com/w/courses/) (public and private courses, free eLearning, certification) |

### Score

Method:
- 6 doc topics: each scores 1 if present and changed in the last 12 months, 0.5 if present but older or only in the commercial docs, 0 if absent;
- support 0–2;
- forum 0–1 (6-month average ≥ 15 posts a month = 1, 5–15 = 0.5);
- training 0–1.

| | Embed | REST | Workers | DMN | CMMN | Migration | Support | Forum | Training | **Total** |
|---|---|---|---|---|---|---|---|---|---|---|
| Operaton | 1 | 1 | 1 | 1 | 1 | 0.5 | 2 | 0.5 | 0.5 | **8.5** |
| Flowable | 1 | 1 | 0.5 | 0.5 | 1 | 0.5 | 2 | 0.5 | 1 | **8.0** |

**Knock-out:** none. Both have public docs for every required topic, commercial support and a forum.

---

## E15 Ecosystem and compatibility

| Item | Operaton | Flowable |
|---|---|---|
| **Camunda 7 compatibility** | **[F]** "API-compatible and schema-compatible with Camunda 7.24". Path: upgrade to C7 7.24, then start Operaton on the **same datasource with no schema upgrade**. The engine accepts the `operaton:` and the legacy `camunda:` namespaces. An [OpenRewrite recipe](https://github.com/operaton/migrate-from-camunda-recipe) (v1.0.1) rewrites `org.camunda` → `org.operaton` ([docs](https://docs.operaton.org/docs/documentation/update/camunda-to-operaton), [FAQ](https://operaton.org/faq)). The package rename is a breaking change for Java code. The FAQ states full compatibility for **1.x**; for 2.x the docs say Operaton "tracks Camunda 7.24" [F], and per-version behaviour should be checked [I] | **[F]** No native C7 schema or API compatibility found. Flowable **LEAP** analyses Camunda/Activiti BPMN for incompatibilities ([commercial how-to](https://documentation.flowable.com/latest/howto/howto/howto-migration)); availability for OSS users was not found. A `flowable5-compatibility` engine module still exists in `main` (Activiti 5 / Flowable 5 in-flight instances). One forum report shows a C7 schema migration failing ([thread](https://forum.flowable.org/t/migration-from-camunda-7-to-flowable/12032)). Direct import of `camunda:` extension attributes: not found |
| **Activiti models** | Not applicable (not found) | `flowable5-*` compatibility modules are present in the `flowable-engine` repo (`modules/`) [F]. Whether the `activiti:` namespace is accepted was not verified |
| **Modeller** | Today: Camunda Modeler (MIT, bpmn-js), per the [FAQ](https://operaton.org/faq). [Operaton Modeler](https://github.com/operaton/operaton-modeler) is MIT, created 2026-01, **0 releases**, and "in development" | **Flowable Design** is proprietary; free cloud sign-up for OSS users ([open-source page](https://www.flowable.com/open-source)). The OSS UI apps (modeler) are not in the 8.x module list. [flowable-designer](https://github.com/flowable/flowable-designer) (Eclipse) was last pushed in 2016. bpmn-js is generic (no Flowable properties panel found) |
| **Worker clients** | Official: Java external task client plus a Spring Boot starter ([ext-client](https://docs.operaton.org/docs/documentation/user-guide/ext-client/)). Other languages: none official found. Camunda 7 community clients (JS and others) target the same REST API, so they probably work, but this was not tested [I] | Official, Apache-2.0: [Java](https://github.com/flowable/flowable-external-client-java) (pushed 2026-02), [JS/TS](https://github.com/flowable/flowable-external-client-js) (2025-11), [Python](https://github.com/flowable/flowable-external-client-python) (2025-11), [.NET](https://github.com/flowable/flowable-external-client.net) (2026-07), [Go](https://github.com/flowable/flowable-external-client-golang) (2026-04). Docs default to Flowable Cloud |
| **Spring Boot / Quarkus** | Both official: [Spring Boot](https://docs.operaton.org/docs/documentation/user-guide/spring-boot-integration/) (Spring Boot 4 in 2.x) and [Quarkus](https://docs.operaton.org/docs/documentation/user-guide/quarkus-integration/) (Quarkus 3.33 LTS in 2.1) | Spring Boot: official (`flowable-spring-boot` modules; [docs](https://www.flowable.com/open-source/docs/bpmn/ch05a-Spring-Boot)). Quarkus: **not found** (no official or Quarkiverse extension found) |
| **Kubernetes / Helm** | [operaton-helm](https://github.com/operaton/operaton-helm) (Apache-2.0), chart release operaton-1.2.0 on 2026-10-23; [operaton-docker](https://github.com/operaton/operaton-docker) images | [flowable/helm](https://github.com/flowable/helm): last commit 2024-01-16 ("Release/7.0.x", 7.0.0-beta). **No chart for 8.x found** |

### Score

Method:
- C7 compatibility 0–3;
- OSS modeller 0–2;
- worker clients 0–2 (≥ 3 official languages = 2);
- Spring Boot + Quarkus 0–2;
- Helm 0–1.

| | C7 | Modeller | Workers | Framework | Helm | **Total** |
|---|---|---|---|---|---|---|
| Operaton | 3 | 1.5 | 1 | 2 | 1 | **8.5** |
| Flowable | 1.5 | 0.5 | 2 | 1 | 0.5 | **5.5** |

**Knock-out:** none.
- Flowable has no OSS modeller. This is not treated as a must-have fail [I]: bpmn-js can be extended in the product layer, at a cost.
- Operaton has only one official worker language. This is mitigated by its REST API compatibility with the C7 clients [I].

---

## Decision-support section (separate from VI.7)

| Dimension | Operaton | Flowable | Notes |
|---|---|---|---|
| E12 | 8.5 | 3.5 | Flowable is held down by an unfixed engine XXE and Jackson 3.0.1 findings that need a minor-line bump |
| E13 | 7.5 | 6.5 | Operaton: high activity, but concentrated in one maintainer, with a 6-month window. Flowable: company-backed, but rare OSS releases |
| E14 | 8.5 | 8.0 | Flowable's worker docs and recent upgrade guides are commercial-only or missing; Flowable has official training |
| E15 | 8.5 | 5.5 | C7 continuity, Quarkus and Helm favour Operaton; worker clients favour Flowable |

**Actions if Operaton stays the choice [I]:**
1. Bump Jackson to 2.22.3 now (same line, all 5 HIGH fixed).
2. Record in the ADR the risk from the dominant maintainer, and plan a provider contract for extended maintenance across the 6-month minor cadence.
3. Keep R24 regardless of engine: the XXE class applies to both engines' parsers.

**Limits:**
- The dependency trees are the spike adapters' trees only. REST, web apps and starters are out of scope.
- Reachability is judgement **[I]**.
- The Flowable issue-response sample is n = 9.
- Forum counts are approximate. "Posts" counts the posts in topics created that month.
