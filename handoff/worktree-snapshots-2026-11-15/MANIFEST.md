# Worktree snapshots, 2026-11-15 (account switch)

Every file in a stray worktree copy whose bytes (raw, or with CRLF/LF folded) match no blob in the git object
store after checkpoint 71b2c66d. Files are copied byte-for-byte at their path inside the copy. Most are earlier
lane versions that were later integrated and edited further; they are kept so that no character is lost.

Left out on purpose (still on local disk at the source path): third-party `reference/` clones, build outputs
(`dist/`, `bin/`, `obj/`), local `.claude/` data, `.git` pointer files, JFR recordings and files over 20 MB.

| copy | source | kept | left out |
|---|---|---|---|
| wt-auth401 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401` | 24 | 266 |
| wt-base | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-base` | 5 | 196 |
| wt-bulk | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-bulk` | 0 | 1 |
| wt-bulk2 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-bulk2` | 0 | 1 |
| wt-comp | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-comp` | 0 | 270 |
| wt-conf | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-conf` | 2 | 196 |
| wt-dst | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst` | 36 | 196 |
| wt-forms | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-forms` | 12 | 34014 |
| wt-fwtd | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-fwtd` | 0 | 197 |
| wt-i8 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-i8` | 2 | 0 |
| wt-ifmswf | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-ifmswf` | 49 | 196 |
| wt-iso | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-iso` | 8 | 1 |
| wt-packs2 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-packs2` | 6 | 196 |
| wt-packs3 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-packs3` | 7 | 196 |
| wt-packs4 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-packs4` | 0 | 197 |
| wt-perf | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-perf` | 0 | 1 |
| wt-perf-base | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-perf-base` | 0 | 1 |
| wt-qdl | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-qdl` | 3 | 196 |
| wt-quota | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-quota` | 1 | 196 |
| wt-quota-stash | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-quota-stash` | 8 | 0 |
| wt-sg35 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-sg35` | 0 | 196 |
| wt-sl37 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-sl37` | 0 | 50 |
| wt-small | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-small` | 0 | 50 |
| wt-uip2 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-uip2` | 42 | 146 |
| wt-uip2-ctl | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-uip2-ctl` | 4 | 0 |
| wt-uiparity | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-uiparity` | 1 | 197 |
| wt-auth401pg | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-auth401pg` | 24 | 196 |
| wt-d10 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-d10` | 36 | 146 |
| wt-ifmswf-red | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-ifmswf-red` | 30 | 0 |
| wt-matrix | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-matrix` | 36 | 0 |
| wt-sec | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sec` | 46 | 146 |
| wt-sg36 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sg36` | 4 | 0 |
| wt-sl84 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sl84` | 41 | 146 |
| wt-sl88 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sl88` | 51 | 150 |
| wt-sl92 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sl92` | 42 | 150 |
| wt-sl93 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sl93` | 42 | 0 |
| wt-sl94 | `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sl94` | 41 | 0 |
| cwf-docs-wt | `D:\GITHUB  Project\cwf-docs-wt` | 4 | 1 |
| wt-fwt | `D:\wt-fwt` | 2 | 1 |
| wt-scale | `D:\wt-scale` | 0 | 4 |

## wt-auth401

- kept `sangam/TESTING-GAP-REGISTER.md` (101509 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (7952 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-integration/src/test/java/org/sangam/integration/connector/http/LiveHttpConnectorsTest.java` (31248 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (96348 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12862 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25447 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out `.git` (69 bytes)
- left out 265 third-party or build-output files

## wt-base

- kept `sangam/SECTOR-CONFORMANCE.md` (16982 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantIsolationGuard.java` (3573 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/CrossTenantCallH2Test.java` (10700 bytes)
- kept `sangam/sangam-flowable-ports/src/test/java/org/sangam/ports/batch/ProductBatchServiceH2Test.java` (8291 bytes)
- kept `sangam/sangam-flowable-ports/src/test/java/org/sangam/ports/events/EventRegistryH2Test.java` (5783 bytes)
- left out 196 third-party or build-output files

## wt-bulk

- left out `.git` (66 bytes)

## wt-bulk2

- left out `.git` (67 bytes)

## wt-comp

- left out 270 third-party or build-output files

## wt-conf

- kept `sangam/SECTOR-CONFORMANCE.md` (16935 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/fwt/FwtWorkerCompatIT.java` (13880 bytes)
- left out 196 third-party or build-output files

## wt-dst

- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (99669 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-integration/src/test/java/org/sangam/integration/connector/http/LiveHttpConnectorsTest.java` (31248 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (96348 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-rest/src/main/java/org/sangam/rest/web/ProblemHandler.java` (8908 bytes)
- kept `sangam/sangam-server/pom.xml` (11577 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (26979 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/main/resources/application.yaml` (13355 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12756 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25355 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/web/RequestStatusContractH2Test.java` (8153 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out 196 third-party or build-output files

## wt-forms

- kept `spike/results/run-queue.console.log` (5081 bytes)
- kept `spike/results/run-queue.log` (5084 bytes)
- kept `spike/results/run-queue/q1-adapter-flowable.err.log` (1528349 bytes)
- kept `spike/results/run-queue/q1-adapter-flowable.out.log` (7490 bytes)
- kept `spike/results/run-queue/q1-adapter-operaton.err.log` (1421863 bytes)
- kept `spike/results/run-queue/q1-adapter-operaton.out.log` (5413 bytes)
- kept `spike/results/run-queue/q2-e1-mysql-mariadb.out.log` (3556 bytes)
- kept `spike/results/run-queue/q2-e1-mysql.out.log` (3360 bytes)
- kept `spike/results/run-queue/q2-e1-oracle.out.log` (3360 bytes)
- kept `spike/results/run-queue/q2-e1-sqlserver.out.log` (3360 bytes)
- kept `spike/results/run-queue/q3-e2e7-oracle.out.log` (1551 bytes)
- kept `spike/results/sources/lmmh/LMMH-CS1127.pdf` (9222852 bytes)
- left out `.git` (67 bytes)
- left out 34013 third-party or build-output files

## wt-fwtd

- left out `.git` (66 bytes)
- left out 196 third-party or build-output files

## wt-i8

- kept `run1.log` (295596 bytes)
- kept `run2.log` (5121396 bytes)

## wt-ifmswf

- kept `it.log` (56800 bytes)
- kept `it2.log` (685233 bytes)
- kept `it3.log` (1469160 bytes)
- kept `it4.log` (961797 bytes)
- kept `it5.log` (417215 bytes)
- kept `run-it.ps1` (727 bytes)
- kept `verify.log` (11194 bytes)
- kept `verify.ps1` (847 bytes)
- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (106130 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/README.md` (114925 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-authority.dmn` (1350 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-powers.dmn` (2525 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/pack.json` (3028 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/sangam-ifms-budget-allocation.bpmn` (18780 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/forms/proposal.form.json` (1640 bytes)
- kept `sangam/sangam-packs/src/main/resources/profiles/in/states/RJ.yaml` (10404 bytes)
- kept `sangam/sangam-packs/src/test/java/org/sangam/packs/SectorConformanceTest.java` (22038 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (98205 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-packs/tools/profilegen/states.py` (38441 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-server/pom.xml` (11577 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (28118 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12862 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25447 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out 196 third-party or build-output files

## wt-iso

- kept `sangam/TESTING-GAP-REGISTER.md` (77494 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8013 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16234 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-rest/src/main/java/org/sangam/rest/web/ProblemHandler.java` (8908 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (42995 bytes)
- left out `.git` (65 bytes)

## wt-packs2

- kept `sangam/SECTOR-CONFORMANCE.md` (16982 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantIsolationGuard.java` (3573 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/CrossTenantCallH2Test.java` (10700 bytes)
- kept `sangam/sangam-flowable-ports/src/test/java/org/sangam/ports/batch/ProductBatchServiceH2Test.java` (8291 bytes)
- kept `sangam/sangam-flowable-ports/src/test/java/org/sangam/ports/events/EventRegistryH2Test.java` (5783 bytes)
- kept `sangam/sangam-onboarding/src/test/java/org/sangam/onboarding/readiness/PackPlaceholderReadinessTest.java` (1750 bytes)
- left out 196 third-party or build-output files

## wt-packs3

- kept `sangam/SECTOR-CONFORMANCE.md` (16982 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantIsolationGuard.java` (3573 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/CrossTenantCallH2Test.java` (10700 bytes)
- kept `sangam/sangam-flowable-ports/src/test/java/org/sangam/ports/batch/ProductBatchServiceH2Test.java` (8291 bytes)
- kept `sangam/sangam-flowable-ports/src/test/java/org/sangam/ports/events/EventRegistryH2Test.java` (5783 bytes)
- kept `sangam/sangam-onboarding/src/test/java/org/sangam/onboarding/readiness/PackPlaceholderReadinessTest.java` (1750 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/sec-bank-payment-dual-auth/pack.json` (3837 bytes)
- left out 196 third-party or build-output files

## wt-packs4

- left out `.git` (68 bytes)
- left out 196 third-party or build-output files

## wt-perf

- left out `.git` (66 bytes)

## wt-perf-base

- left out `.git` (71 bytes)

## wt-qdl

- kept `green.log` (989093 bytes)
- kept `pg.log` (420405 bytes)
- kept `red.log` (1246319 bytes)
- left out 196 third-party or build-output files

## wt-quota

- kept `sangam/TESTING-GAP-REGISTER.md` (38395 bytes)
- left out 196 third-party or build-output files

## wt-quota-stash

- kept `TESTING-GAP-REGISTER.md` (31904 bytes)
- kept `sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java` (15436 bytes)
- kept `sangam-persistence/src/main/resources/db/changelog/h2/sangam-0001-baseline.sql` (200715 bytes)
- kept `sangam-persistence/src/main/resources/db/changelog/mssql/sangam-0001-baseline.sql` (195591 bytes)
- kept `sangam-persistence/src/main/resources/db/changelog/mysql/sangam-0001-baseline.sql` (207441 bytes)
- kept `sangam-persistence/src/main/resources/db/changelog/oracle/sangam-0001-baseline.sql` (210279 bytes)
- kept `sangam-persistence/src/main/resources/db/changelog/postgresql/sangam-0001-baseline.sql` (198133 bytes)
- kept `sangam-persistence/tools/generate_changelogs.py` (182016 bytes)

## wt-sg35

- left out 196 third-party or build-output files

## wt-sl37

- left out 50 third-party or build-output files

## wt-small

- left out 50 third-party or build-output files

## wt-uip2

- kept `.gitattributes` (543 bytes)
- kept `uip2-mod.log` (49002 bytes)
- kept `uip2-srv1.log` (408660 bytes)
- kept `uip2-srv2.log` (324745 bytes)
- kept `uip2-srv3.log` (322474 bytes)
- kept `uip2-srv4.log` (520534 bytes)
- kept `logs-2026-11-09/control-pristine-server-1.log` (5202739 bytes)
- kept `logs-2026-11-09/green-1.log` (5288953 bytes)
- kept `logs-2026-11-09/green-2.log` (6913 bytes)
- kept `logs-2026-11-09/green-3.log` (6913 bytes)
- kept `logs-2026-11-09/int-jdk21-compile.log` (35461 bytes)
- kept `logs-2026-11-09/int-jdk25-isolated-1.log` (5419921 bytes)
- kept `logs-2026-11-09/int-jdk25-test-compile.log` (56789 bytes)
- kept `logs-2026-11-09/int-ui-build.log` (12862 bytes)
- kept `logs-2026-11-09/int-ui-lint.log` (212 bytes)
- kept `logs-2026-11-09/int-ui-npm-ci-offline.log` (151 bytes)
- kept `logs-2026-11-09/int-ui-typecheck.log` (67 bytes)
- kept `logs-2026-11-09/int-ui-vitest.log` (17648 bytes)
- kept `logs-2026-11-09/red-without-fix.log` (498337 bytes)
- kept `logs-2026-11-09/ui-build.log` (13354 bytes)
- kept `logs-2026-11-09/ui-lint.log` (212 bytes)
- kept `logs-2026-11-09/ui-typecheck.log` (67 bytes)
- kept `logs-2026-11-09/ui-vitest-1.log` (18382 bytes)
- kept `logs-2026-11-09/ui-vitest-2.log` (18391 bytes)
- kept `logs-2026-11-09/ui-vitest-3.log` (18402 bytes)
- kept `logs-2026-11-09/wt-isolated-1.log` (335896 bytes)
- kept `logs-2026-11-09/wt2-gate-compile.log` (5945 bytes)
- kept `logs-2026-11-09/wt2-jdk21-compile.log` (41836 bytes)
- kept `logs-2026-11-09/wt2-jdk21-isolated-1.log` (5267583 bytes)
- kept `logs-2026-11-09/wt2-jdk25-isolated-1.log` (5276081 bytes)
- kept `logs-2026-11-09/wt2-jdk25-test-compile.log` (61258 bytes)
- kept `logs-2026-11-09/wt2-ui-build.log` (13354 bytes)
- kept `logs-2026-11-09/wt2-ui-lint.log` (212 bytes)
- kept `logs-2026-11-09/wt2-ui-typecheck.log` (67 bytes)
- kept `logs-2026-11-09/wt2-ui-vitest.log` (18395 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (81916 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/sangam-integration/src/main/java/org/sangam/integration/connector/http/LiveHttpConnectors.java` (12012 bytes)
- kept `sangam/sangam-integration/src/test/java/org/sangam/integration/connector/http/LiveHttpConnectorsTest.java` (24071 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)
- left out 146 third-party or build-output files

## wt-uip2-ctl

- kept `.gitattributes` (543 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (73406 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)

## wt-uiparity

- kept `bak/engine.tsx` (24198 bytes)
- left out `.git` (70 bytes)
- left out 196 third-party or build-output files

## wt-auth401pg

- kept `sangam/TESTING-GAP-REGISTER.md` (101509 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (7952 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-integration/src/test/java/org/sangam/integration/connector/http/LiveHttpConnectorsTest.java` (31248 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (96348 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12862 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25447 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out 196 third-party or build-output files

## wt-d10

- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (102419 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-integration/src/test/java/org/sangam/integration/connector/http/LiveHttpConnectorsTest.java` (31248 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (96348 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-rest/src/main/java/org/sangam/rest/web/ProblemHandler.java` (8908 bytes)
- kept `sangam/sangam-server/pom.xml` (11577 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (26979 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/main/resources/application.yaml` (13355 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12756 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25355 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/web/RequestStatusContractH2Test.java` (8153 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out 146 third-party or build-output files

## wt-ifmswf-red

- kept `sangam/TESTING-GAP-REGISTER.md` (101509 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (7952 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-integration/src/test/java/org/sangam/integration/connector/http/LiveHttpConnectorsTest.java` (31248 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/src/main/resources/profiles/in/states/RJ.yaml` (10404 bytes)
- kept `sangam/sangam-packs/src/test/java/org/sangam/packs/SectorConformanceTest.java` (22038 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (96348 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-packs/tools/profilegen/states.py` (38441 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-rest/src/main/java/org/sangam/rest/web/ProblemHandler.java` (8908 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (26979 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/main/resources/application.yaml` (13355 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (27603 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12756 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25355 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/web/RequestStatusContractH2Test.java` (8153 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)

## wt-matrix

- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (102419 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-integration/src/test/java/org/sangam/integration/connector/http/LiveHttpConnectorsTest.java` (31248 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (96348 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-rest/src/main/java/org/sangam/rest/web/ProblemHandler.java` (8908 bytes)
- kept `sangam/sangam-server/pom.xml` (11577 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (26979 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/main/resources/application.yaml` (13355 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12756 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25355 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/web/RequestStatusContractH2Test.java` (8153 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)

## wt-sec

- kept `ACCEPTANCE-PLUG-AND-PLAY.md` (4795 bytes)
- kept `pom.xml` (13737 bytes)
- kept `TESTING-GAP-REGISTER.md` (119671 bytes)
- kept `deploy/README.md` (47985 bytes)
- kept `deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `deploy/helm/sangam/values.schema.json` (2081 bytes)
- kept `deploy/helm/sangam/values.yaml` (16787 bytes)
- kept `deploy/helm/sangam/templates/server-configmap.yaml` (7392 bytes)
- kept `sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam-engine/README.md` (26581 bytes)
- kept `sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18756 bytes)
- kept `sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (10383 bytes)
- kept `sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam-packs/README.md` (114925 bytes)
- kept `sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-authority.dmn` (1350 bytes)
- kept `sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-powers.dmn` (2525 bytes)
- kept `sangam-packs/src/main/resources/packs/ifms-budget-allocation/pack.json` (3028 bytes)
- kept `sangam-packs/src/main/resources/packs/ifms-budget-allocation/sangam-ifms-budget-allocation.bpmn` (18780 bytes)
- kept `sangam-packs/src/main/resources/packs/ifms-budget-allocation/forms/proposal.form.json` (1640 bytes)
- kept `sangam-packs/src/main/resources/profiles/in/states/RJ.yaml` (10404 bytes)
- kept `sangam-packs/src/test/java/org/sangam/packs/SectorConformanceTest.java` (22038 bytes)
- kept `sangam-packs/tools/packgen/gov_ifms.py` (98205 bytes)
- kept `sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam-packs/tools/profilegen/states.py` (38441 bytes)
- kept `sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java` (69973 bytes)
- kept `sangam-server/pom.xml` (11577 bytes)
- kept `sangam-server/README.md` (46976 bytes)
- kept `sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam-server/src/main/java/org/sangam/server/bridge/SecurityAdminBridge.java` (26576 bytes)
- kept `sangam-server/src/main/java/org/sangam/server/config/SecurityConfig.java` (14658 bytes)
- kept `sangam-server/src/main/java/org/sangam/server/config/SecurityWiring.java` (9067 bytes)
- kept `sangam-server/src/main/resources/application.yaml` (13417 bytes)
- kept `sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (28118 bytes)
- kept `sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12974 bytes)
- kept `sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out 146 third-party or build-output files

## wt-sg36

- kept `sangam/TESTING-GAP-REGISTER.md` (82423 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)

## wt-sl84

- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (102592 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/store/JdbcConfigStore.java` (11199 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-integration/src/test/java/org/sangam/integration/connector/http/LiveHttpConnectorsTest.java` (31248 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (96348 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java` (16003 bytes)
- kept `sangam/sangam-persistence/src/main/resources/db/changelog/h2/sangam-0001-baseline.sql` (204345 bytes)
- kept `sangam/sangam-persistence/src/main/resources/db/changelog/mssql/sangam-0001-baseline.sql` (199134 bytes)
- kept `sangam/sangam-persistence/src/main/resources/db/changelog/mysql/sangam-0001-baseline.sql` (211219 bytes)
- kept `sangam/sangam-persistence/src/main/resources/db/changelog/oracle/sangam-0001-baseline.sql` (214000 bytes)
- kept `sangam/sangam-persistence/src/main/resources/db/changelog/postgresql/sangam-0001-baseline.sql` (201783 bytes)
- kept `sangam/sangam-persistence/tools/generate_changelogs.py` (188015 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-server/pom.xml` (11577 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (13371 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12862 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25447 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out 146 third-party or build-output files

## wt-sl88

- kept `.gitattributes` (543 bytes)
- kept `HANDOFF.md` (29587 bytes)
- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (109762 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-core/src/main/java/org/sangam/core/api/LeaseTable.java` (17668 bytes)
- kept `sangam/sangam-core/src/main/java/org/sangam/core/layer/Jdbc.java` (7971 bytes)
- kept `sangam/sangam-core/src/main/java/org/sangam/core/layer/NullTypes.java` (5569 bytes)
- kept `sangam/sangam-core/src/test/java/org/sangam/core/layer/JdbcNullBindTest.java` (11080 bytes)
- kept `sangam/sangam-core/src/test/java/org/sangam/core/layer/JdbcSetNullTest.java` (1356 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/db/EngineDialect.java` (6738 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineRunner.java` (36785 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/README.md` (114925 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-authority.dmn` (1350 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-powers.dmn` (2525 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/pack.json` (3028 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/sangam-ifms-budget-allocation.bpmn` (18780 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/forms/proposal.form.json` (1640 bytes)
- kept `sangam/sangam-packs/src/main/resources/profiles/in/states/RJ.yaml` (10404 bytes)
- kept `sangam/sangam-packs/src/test/java/org/sangam/packs/SectorConformanceTest.java` (22038 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (98205 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-packs/tools/profilegen/states.py` (38441 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java` (69737 bytes)
- kept `sangam/sangam-server/pom.xml` (11577 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (28118 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12862 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25447 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out `.git` (87 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite` (4096 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite-shm` (32768 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite-wal` (185432 bytes)
- left out 146 third-party or build-output files

## wt-sl92

- kept `.gitattributes` (543 bytes)
- kept `HANDOFF.md` (29587 bytes)
- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (113606 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/README.md` (114925 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-authority.dmn` (1350 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-powers.dmn` (2525 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/pack.json` (3028 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/sangam-ifms-budget-allocation.bpmn` (18780 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/forms/proposal.form.json` (1640 bytes)
- kept `sangam/sangam-packs/src/main/resources/profiles/in/states/RJ.yaml` (10404 bytes)
- kept `sangam/sangam-packs/src/test/java/org/sangam/packs/SectorConformanceTest.java` (22038 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (98205 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-packs/tools/profilegen/states.py` (38441 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (28118 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12862 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25447 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)
- left out `.git` (87 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite` (4096 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite-shm` (32768 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite-wal` (185432 bytes)
- left out 146 third-party or build-output files

## wt-sl93

- kept `sl93-compile.log` (1102 bytes)
- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (118670 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16010 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/README.md` (114925 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-authority.dmn` (1350 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-powers.dmn` (2525 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/pack.json` (3028 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/sangam-ifms-budget-allocation.bpmn` (18780 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/forms/proposal.form.json` (1640 bytes)
- kept `sangam/sangam-packs/src/main/resources/profiles/in/states/RJ.yaml` (10404 bytes)
- kept `sangam/sangam-packs/src/test/java/org/sangam/packs/SectorConformanceTest.java` (22038 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (98205 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-packs/tools/profilegen/states.py` (38441 bytes)
- kept `sangam/sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java` (16003 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (28118 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12862 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25447 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)

## wt-sl94

- kept `sangam/pom.xml` (13737 bytes)
- kept `sangam/TESTING-GAP-REGISTER.md` (116278 bytes)
- kept `sangam/deploy/README.md` (47146 bytes)
- kept `sangam/deploy/airgap/generate-sbom.sh` (3626 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java` (14363 bytes)
- kept `sangam/sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java` (12075 bytes)
- kept `sangam/sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java` (11260 bytes)
- kept `sangam/sangam-engine/PATCHES.md` (16395 bytes)
- kept `sangam/sangam-engine/README.md` (26581 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineConfigurer.java` (8464 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java` (24237 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` (18709 bytes)
- kept `sangam/sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` (8156 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/EngineInternalsPinTest.java` (12795 bytes)
- kept `sangam/sangam-engine/src/test/java/org/sangam/engine/TenantScopedCreationH2Test.java` (16553 bytes)
- kept `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java` (5881 bytes)
- kept `sangam/sangam-onboarding/src/test/resources/acceptance/AT-1.json` (1207 bytes)
- kept `sangam/sangam-packs/README.md` (114925 bytes)
- kept `sangam/sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java` (16758 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn` (42486 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-authority.dmn` (1350 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/ifms-allocation-powers.dmn` (2525 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/pack.json` (3028 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/sangam-ifms-budget-allocation.bpmn` (18780 bytes)
- kept `sangam/sangam-packs/src/main/resources/packs/ifms-budget-allocation/forms/proposal.form.json` (1640 bytes)
- kept `sangam/sangam-packs/src/main/resources/profiles/in/states/RJ.yaml` (10404 bytes)
- kept `sangam/sangam-packs/src/test/java/org/sangam/packs/SectorConformanceTest.java` (22038 bytes)
- kept `sangam/sangam-packs/tools/packgen/gov_ifms.py` (98205 bytes)
- kept `sangam/sangam-packs/tools/packgen/packs.py` (12798 bytes)
- kept `sangam/sangam-packs/tools/profilegen/states.py` (38441 bytes)
- kept `sangam/sangam-rest/api/sangam-api.yaml` (1666598 bytes)
- kept `sangam/sangam-server/pom.xml` (11577 bytes)
- kept `sangam/sangam-server/README.md` (45183 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java` (27536 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java` (3309 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java` (28118 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/isolation/CrossTenantIsolationIT.java` (43784 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java` (12862 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java` (25447 bytes)
- kept `sangam/sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json` (1207 bytes)

## cwf-docs-wt

- kept `sangam/deploy/docker/server.Dockerfile` (9300 bytes)
- kept `sangam/deploy/helm/sangam/values.yaml` (16009 bytes)
- kept `sangam/sangam-integration/src/main/java/org/sangam/integration/connector/http/HttpRestConnector.java` (12249 bytes)
- kept `sangam/sangam-server/src/main/resources/application.yaml` (11356 bytes)
- left out `.git` (70 bytes)

## wt-fwt

- kept `sangam/sangam-server/src/main/java/org/sangam/server/web/FlowableWorkerCompatController.java` (11373 bytes)
- kept `sangam/sangam-server/src/main/java/org/sangam/server/web/FlowableWorkerCompatProblemAdvice.java` (1325 bytes)
- left out `.git` (65 bytes)

## wt-scale

- left out `.git` (67 bytes)
- left out `tscale-base/tscale.jfr` (41320801 bytes)
- left out `tscale-smoke/ev.json` (1640064738 bytes)
- left out `tscale-smoke/tscale.jfr` (7114063 bytes)
