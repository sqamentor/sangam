<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-script-worker

BPMN script tasks (JavaScript, Python) run **outside the engine**. This module is a Java SDK worker on the lease
protocol (VI.3). It runs every script as a child process of Node.js or CPython inside **nsjail**. That is ADR-D-8
(script sandbox) and owner decision 53 (Q-07 = option A). No script engine runs in any JVM: GraalJS, Rhino, Nashorn,
Jython and JRuby stay banned. One container is enough, so a VM needs only Docker or Podman.

| File | Role |
|---|---|
| `ScriptTopics` | The topic and variable convention that the importer rewrite produces |
| `ScriptRequest` | Reads and checks one work item: language, Base64 source, SHA-256 integrity, size, result-variable name |
| `NsjailSandbox` | Builds the nsjail command line (isolation flags below) |
| `ScriptRunner` | Child process I/O: stdin request, capped stdout/stderr, wall time and cancellation enforced from outside |
| `ScriptTaskHandler` | Maps each outcome onto the lease protocol: complete, BPMN error, incident, retry, cancel |
| `ScriptWorkerMain` | Container entry point (environment configuration) |
| `src/main/runtime/runner.js`, `runner.py` | Harnesses inside the jail: script bindings and the answer protocol |
| `src/main/runtime/sangam-script.kafel` | seccomp policy |
| `deploy/docker/script-worker.Dockerfile`, `deploy/docker/script-worker/entrypoint.sh` | Image and cgroup v2 setup |

## Convention: what the importer rewrite produces

A `scriptTask` whose `scriptFormat` maps to a language becomes a `serviceTask` with `operaton:type="external"`. The
mapping is `javascript`, `js` or `ecmascript` to `javascript`, and `python`, `python3` or `py` to `python`. Any other
format, including engine dialects such as nashorn, graal.js, jython and groovy, is **not** rewritten. It stays the
`SCRIPT_IN_ENGINE` deployment error.

| Element | Value |
|---|---|
| `operaton:topic` | `sangam.script.<language>` (`sangam.script.javascript`, `sangam.script.python`) |
| input parameter `sangamScriptB64` | The inline script text as UTF-8, Base64-encoded. Base64 keeps the engine's expression language from evaluating `${...}` inside the script. |
| input parameter `sangamScriptSha256` | Lower-case hex SHA-256 of that UTF-8 text. The worker refuses a mismatch. |
| input parameter `sangamScriptResultVariable` | Optional: the original `operaton:resultVariable` |

A script given as an external resource (`operaton:resource`) is inlined from the package at import time, so the frozen
package hash covers it. The rewrite runs when a package is deployed (owner decision 89; `ScriptTaskDeployRewrite` in sangam-rest, tracked
item SW-3), so the stored package keeps the script as authored and its hash covers it. The package tools
(`OperatonPackageToolsAdapter`) report a convertible script task as `SCRIPT_REWRITTEN_AT_DEPLOY` (INFO) and any other
as `SCRIPT_IN_ENGINE` (ERROR); they report `SCRIPT_CONVENTION_INVALID` for an external task on a script topic with an
unknown language, a missing or non-Base64 source or a wrong hash; the assessment maps convertible formats (MAPPED)
and rejects the rest.

## What a script sees

The script sees the following bindings. Both languages have the same set.

- **Each process variable by name**, when the name is a valid identifier. Assigning to that name does not store it,
  which matches the engine's default script bindings.
- **`variables`**: all inputs.
- **`context`**: `workId`, `topic`, `operationId`, `processInstanceId` and `businessKey`.
- **`execution`**: the methods `getVariable`, `hasVariable`, `setVariable`, `getVariables`, `getBusinessKey` and
  `getProcessInstanceId`. Only `setVariable` and the result variable reach the process.
- **`BpmnError(code, message, variables)`**: throw it or raise it to route through a BPMN error boundary.

The value of the last expression becomes the result variable when one is set. Python reads fractional numbers as
`Decimal` and writes them back exactly. The worker returns top-level decimals as typed `Decimal`. JavaScript numbers
are IEEE doubles, as in every JS engine, so amounts belong in Python scripts or in strings.

`print` and `console` output goes to stderr. The worker keeps up to `SANGAM_SCRIPT_STDERR_BYTES` of it for the
incident. stdout carries only the answer.

## Outcomes

| Script | Work item |
|---|---|
| Finished | Completed with `execution.setVariable` values plus the result variable |
| Threw `BpmnError` with a code | BPMN error |
| Threw anything else | Failure with 0 retries: an incident at once. The same script on the same input fails the same way. |
| Hit a limit (CPU, memory, processes, wall time, output) | Failure with 0 retries: an incident. Limits are configuration. |
| Malformed work item (no source, bad hash, unknown topic) | Failure with 0 retries: an incident |
| Sandbox did not start or gave no answer | Failure with the subscription's retries (`SANGAM_SCRIPT_RETRIES`, default 3). A jailed script has no outside effect, so a retry is safe. |
| Cancel requested, lease lost or shutdown | The jail is killed and the work item is acknowledged or unlocked |

## Isolation (NsjailSandbox; proven by `ScriptSandboxEscapeIT`)

- **Network:** a new network namespace without even loopback (`--iface_no_lo`).
- **Namespaces:** its own PID, mount, IPC, UTS and user namespaces. The script runs as uid/gid 65534 with no
  capabilities and `no_new_privs`.
- **File system:** no chroot of the host. The jail root is empty, with `/usr`, `/etc/ld.so.cache` and the harness
  directory mounted read-only, plus a private size-limited `/tmp`. Nothing of the worker is visible: not its token,
  its configuration or its environment. Only `HOME`, `LANG`, `TZ` and two Python settings enter the jail.
- **seccomp:** `sangam-script.kafel` denies ptrace, process_vm_*, mount and the new mount API, unshare/setns, modules,
  kexec, keyrings, BPF, perf, userfaultfd, io_uring, I/O ports, clock setting and hostname changes. `clone3` answers
  ENOSYS so the C library falls back to `clone`.
- **Limits:** cgroup v2 limits on memory, PIDs and a one-CPU share; rlimits on CPU time, file size, open files and
  core dumps; a wall-time limit set in nsjail and enforced again by the worker; caps on stdout and stderr; and a
  maximum script size.

| Variable | Default |
|---|---|
| `SANGAM_SCRIPT_CPU_SECONDS` | 5 |
| `SANGAM_SCRIPT_WALL_TIME` | PT10S |
| `SANGAM_SCRIPT_MEMORY_BYTES` | 268435456 (256 MiB) |
| `SANGAM_SCRIPT_MAX_PROCESSES` | 16 |
| `SANGAM_SCRIPT_TMP_BYTES` | 16777216 (16 MiB) |
| `SANGAM_SCRIPT_STDOUT_BYTES` | 1048576 (more output fails the script; it is never truncated) |
| `SANGAM_SCRIPT_STDERR_BYTES` | 16384 |
| `SANGAM_SCRIPT_SOURCE_BYTES` | 262144 |
| `SANGAM_SCRIPT_LANGUAGES` | `javascript,python` |
| `SANGAM_SCRIPT_MAX_CONCURRENCY` | Number of CPUs |
| `SANGAM_URL`, `SANGAM_TOKEN`, or `SANGAM_OIDC_TOKEN_URL` + `SANGAM_CLIENT_ID` + `SANGAM_CLIENT_SECRET` | As for every SDK worker |

The worker refuses to start when nsjail, the seccomp policy or a harness is missing. The entry point refuses to start
without a writable cgroup v2. Neither one ever runs a script without the sandbox.

## Container privileges (owner decision 78)

Creating namespaces and cgroups for each jail needs more than the default Docker profile allows. The owner approved
these run flags (decision 78); nsjail is 3.4 from a pinned, approved commit (decision 77):

```
--cgroupns=private --cap-add SYS_ADMIN --security-opt seccomp=unconfined
--security-opt apparmor=unconfined --security-opt systempaths=unconfined
```

`--privileged` is not used: it grants much more. The worker container holds only the worker. It has no database
credentials. Give it a worker identity of its own, used for nothing else. Image inputs (base images, nsjail source,
apt packages) are approved for the test phase only (decision 79); nothing is pulled or built before it.

## Tests

- **Unit tests** (`*Test`) run on any JDK. `ScriptRunnerTest` uses a child JVM that plays the interpreter.
- **Escape tests** (`ScriptSandboxEscapeIT`) need the image. Run them in the `test` target with the privileges above:
  `mvn -Pscript-sandbox -f tools/sangam-script-worker/pom.xml verify`.
- **Not yet run:** the house rule in Wave 3 is that tests are written now and run in the test stage.

## Tracked items

| Id | Item | State | Closed by |
|---|---|---|---|
| SW-1 | seccomp does not filter `clone(CLONE_NEWUSER)` (and the other `CLONE_NEW*` flags) by argument. `unshare`, `setns` and `clone3` are already denied. Kafel's argument names for `clone` were not verified offline, so no rule was written blind. | OPEN | The test phase: `ScriptSandboxEscapeIT.kernelSurfaceCallsAreDenied` (nested namespaces) runs in the image. If a nested namespace through `clone` succeeds there, add a kafel argument rule on the clone flags (mask `0x7E020000`) and rerun the escape tests. |
| SW-2 | The kafel syntax (`//` comments, `SYSCALL[n]`) and the nsjail 3.4 flags are confirmed only by an image run. | OPEN | The test phase: the escape tests in the image. |
| SW-3 | Deploy-time rewrite (owner decision 89): `ScriptTaskDeployRewrite.rewrite(resources)` in sangam-rest (`adapter/jdbc`) turns each convertible `scriptTask` into the external task of this convention just before a package deploy; the stored package stays as authored. Unsupported formats, empty scripts, and scripts outside the package (classpath, unprefixed or expression resources) are refused with 422. Direct deploys without a package still hit the engine's script-task rejection. | CODED; the one-line call in `JdbcPackageAdapter` (deploy case) is W3-G's | W3-G's call lands; `ScriptTaskDeployRewriteTest` (written, not run) runs in the test phase. |
