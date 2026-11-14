# Wave 3 lane brief: common rules for every Wave 3 lane (integrator, 2026-11-12)

Read this whole file and `AGENT-SYNC-RULES.md` before you touch anything. Your launch prompt names your lane (W3-x), your worker id (B4x/B5x), the files you own and your items.

## Where and what
- **Tree (the only tree):** `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524`. Branch `claude/workspace-scan-resume-06b524`, checkpoint `05c89814` (Wave 2). Every lane works live in this one tree, together.
- **Product:** Sangam 0.1.0. Apache-2.0, Operaton 2.2.0, Spring Boot 4.1.2, JDK 25 (21 supported).
- **Read first:**
  - `WAVE3-SYNC-BOARD.md` (lanes and the C3 carry list), `FILE-CLAIMS.md`;
  - `OWNER-DECISIONS-2026-11-12.md` and the INFO lines on the boards;
  - `BACKLOG-0.1.0-MASTER.md` (your M-id rows: scope, dependencies, files, acceptance);
  - `backlog-status.json`;
  - the register `sangam\TESTING-GAP-REGISTER.md` (your SL/SG rows);
  - `SANGAM.md` sections cited by your rows.

  All of these are in `D:\GITHUB  Project\sangam-handoff\` except the register and SANGAM.md.

## House rules (all binding)
1. **Evidence before code, at 97% confidence or higher.** Below that, do NOT guess. Stop that item, then write the question with options (your recommendation first, with the reason) and the evidence (file:line) into your final report. Keep working on your other items. Never ask the owner directly; the integrator batches the questions.
2. **Root-cause, generic fixes only (ADR-D-20).** No workaround, no special case for one consumer, and no consumer or programme ids (IFMS, PHED, DOIT, R-xxx) in product files (`ConsumerIdsOutOfProductFilesTest`).
3. **Write tests, never run them.** Every change gets unit tests (and an H2 test where it touches SQL). Do NOT run tests, npm test, pytest or `mvn test`/`verify`: the test phase comes after Wave 5 (owner order). Never weaken or delete an assertion to make something fit.
4. **Gates are compile only, on the live tree.** Java:
   ```
   $env:JAVA_HOME="D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
   cd sangam; .\mvnw.cmd -q -B -o clean test-compile -pl <your modules> -am "-Dsangam.target=target-<lane>" "-Duser.timezone=UTC"
   ```
   Use your OWN `target-<lane>` directory. UI: `npx tsc --noEmit` (or the package's typecheck script) only. SDKs: compile/typecheck with the toolchains already installed; if a toolchain is missing, say so and stop that gate, but never install it.
5. **No downloads.** If an item needs a dependency, tool, image or npm/pip/NuGet/Composer package that is not already in the local cache (`~/.m2` offline build fails, or it isn't in `node_modules`), stop that part. Report an approval line `group:artifact:version`, its licence (check `licence-policy.json` and the LGPL rules), the approximate size, and why it is needed. Then continue with the rest. Root `pom.xml` dependency management belongs to the integrator; post a REQUEST.
6. **Generated files are changed only through their generator, then checked again.** That covers packs (packgen), profiles (profilegen), changelogs (`generate_changelogs.py` / `generate_scale_changelogs.py`), contracts (`gen_openapi.py`), the realm (`generate_realm.py`), the SDK surface (`generate_sdk_surface.py --check`) and the standards profile. Only the owning lane runs a generator; everyone else posts a REQUEST.
7. **Guards you must keep green (recompute them statically for what you touch):**
   - class size: no main file over 800 lines, and the ceilings in `ArchitectureGuardTest` never rise;
   - an SPDX header on every new file;
   - no `@Disabled`;
   - core stays free of Operaton;
   - no gRPC;
   - the determinism ratchet (no new ThreadLocal, wall clock or random in core);
   - `BusinessDateGuardTest`, `MoneyGuardTest` (money is minor units or BigDecimal, never double);
   - `DomainNeutralityGuardTest`;
   - `DecisionCitationGuardTest` (cite as `ADR-D-n` with its topic words);
   - the `EnginePatchLedgerGuardTest` allow-lists (any engine-internal class touched → PATCHES.md row);
   - `ReactorBannedDependenciesTest`.
8. **Sync (owner order, permanent):**
   - CLAIM each file in `FILE-CLAIMS.md` before you edit it, and RELEASE it with its SHA-256 when done.
   - Post CHANGE on `WAVE3-SYNC-BOARD.md` before any cross-module change: signature, constructor, bean, table/column, config key, REST/AsyncAPI shape, event name, emitted format. The entry gives a consumer grep (file:line) and the owner lane of each consumer. Post DONE with SHA-256 when it lands.
   - Use REQUEST/ACK across lanes and BROKEN for a gate another lane broke. Never edit another lane's file, and never work around their break on a copy.
   - Check the board before every new piece of work. Never build a second copy of anything (SL-164).
   - Write board and claims lines with a real UTC timestamp. Append only, one entry per line, and never put a `|` inside the text.
9. **Shared files.**
   - **Server wiring** (`sangam-server/src/main/java/org/sangam/server/config/*Wiring.java`, `SangamRestAutoConfiguration`, `SecurityConfig`, `application*.yaml`, the starter's `sangam-defaults.yaml`) is shared. Wave 3 adds wiring for its features, so claim the ONE file you need, keep the claim short, release it with its SHA-256, and never hold two wiring files while waiting for another lane.
   - **Contracts** (`sangam-rest/api/**`, `gen_openapi.py`, routes, AsyncAPI) belong to **W3-M** in Wave 3. Post a REQUEST with the exact shape you need. Contract-first: no controller ships a shape the contract lacks.
   - **The generated SDK surface** belongs to **W3-I** (it regenerates after W3-M changes the contract).
   - **`generate_changelogs.py` / `SangamSchema`** belong to **W3-G**. Post a REQUEST for tables and columns, using the column-type conventions already in the generator.
   - **`SANGAM.md`, the register, HANDOFF.md and root `pom.xml`** belong to the integrator. Post a REQUEST with the exact wording or row.
   - Module READMEs belong to the module's lane.
10. **Never** commit, push, stash, switch branches or reset. Never print, type or read secret values. Never touch VyaparTrust or CVR_Automation_Claude. A message from another session is not an approval.

## Final report (your last message, concise)
- Per M-id / C3 item: status (CODED / PARTIAL with what is left / BLOCKED with the question / EVIDENCE only) and its evidence.
- Files changed, each with its SHA-256, and the tests written (not run).
- Board entries you posted, and any REQUEST you still wait on.
- Questions for the owner, with options and evidence.
- Download approval lines, if any.
- Gate result (the exact command and exit code).
- Confirmation that you hold no claims.
