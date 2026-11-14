<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-backup

The backup console (owner decision 155, backlog M-102). In 0.1.0 it does three things:

1. **List targets**: the backup schedules of the installation.
2. **Trigger now**: start one backup of a target, with a reason.
3. **Run history**: the runs the platform still keeps, scheduled and manual, with their outcome.

**Restore is not offered.** Restore stays the documented offline procedure (`deploy/README.md` section 3).

**Evidence level:** compiled. `BackupConsoleTest`, `KubernetesBackupTargetsTest` and `ScriptBackupTargetsTest` are
written and have not been run yet (test phase). The console has never been run against a real cluster or a real
backup script.

## Pieces

| Class | What it does |
|---|---|
| `BackupTargets` | Port: targets, runs, a run id, start. |
| `BackupConsole` | The rules (plain Java). A reason is required (at most 1000 characters). Only a listed target can be started. A target with a pending or running run is refused (`BACKUP_RUNNING`), like the chart's `concurrencyPolicy: Forbid`. `BACKUP_TRIGGERED` is written to the audit chain `backup:<target>` before the platform is called, so without the audit event nothing starts. When the platform does not start the run, `BACKUP_TRIGGER_FAILED` follows. |
| `ScriptBackupTargets` | VM and docker compose adapter (owner decision 220). Each target is a backup command the operator configured: the procedure of `deploy/README.md` section 3 as a script on the host. A trigger starts it without a shell, with a cleared environment refilled from an allow-list, and stops it at its timeout. The history is one JSON file per run in a history directory (`<run>.json`, plus the command's output in `<run>.log`, which the API never returns); the newest 100 runs per target are kept. A lock file per target keeps two servers that share the directory from running a target twice; a run whose server stopped turns FAILED after its timeout plus 2 minutes and its lock is taken over. |
| `KubernetesBackupTargets` | Kubernetes adapter over `sangam-kube`. Targets are the chart's backup CronJobs selected by label. A trigger creates a Job from the CronJob's `jobTemplate`, as `kubectl create job --from=cronjob/...` does (annotation `cronjob.kubernetes.io/instantiate: manual`, controller owner reference to the CronJob). The history is the Jobs controlled by those CronJobs. |

## Settings (sangam-server)

| Property | Default | Meaning |
|---|---|---|
| `sangam.backup.console.kubernetes.enabled` | `false` | Turns the console on. Off: the routes answer `501 BACKUP_CONSOLE_NOT_CONFIGURED`. |
| `sangam.backup.console.kubernetes.selector` | `app.kubernetes.io/component=backup` | Label selector of the backup CronJobs. The chart adds `app.kubernetes.io/instance=<release>`. |
| `sangam.backup.console.kubernetes.namespace` | the pod's namespace | Namespace of the CronJobs. |

The server then needs a namespaced Role: `get, list` on `cronjobs` and `get, list, create` on `jobs` (API group
`batch`), the service-account token in the pod, and network egress to the API server. The chart grants these only when
`backup.console.enabled` is true (off by default; W4-C REQUEST).

VM and compose installs (decision 220) use the script adapter instead; at most one of the two may be enabled.

| Property | Default | Meaning |
|---|---|---|
| `sangam.backup.console.script.enabled` | `false` | Turns the script console on. |
| `sangam.backup.console.script.history-dir` | none (required) | Absolute directory of the run records and logs. Several servers must share it (for example the backup volume), so the lock covers all of them. |
| `sangam.backup.console.script.env-passthrough` | empty | Comma-separated names of host variables the commands receive, for example the database password variable. `PATH`, `HOME`, `LANG`, `TZ` and the temp variables always pass; nothing else does. |
| `sangam.backup.console.script.targets[i].id` | none | Target id: lower-case letters, digits and `-`. |
| `sangam.backup.console.script.targets[i].name` | the id | Display name. |
| `sangam.backup.console.script.targets[i].command[j]` | none | The absolute executable (`j` = 0) and its fixed arguments. |
| `sangam.backup.console.script.targets[i].schedule` | none | Shown only: the host's scheduler (cron, systemd timer, Task Scheduler) still runs the scheduled backups. |
| `sangam.backup.console.script.targets[i].timeout` | `PT2H` | ISO-8601 duration, at most 24 hours. The process tree is stopped at the timeout. |

The server's user must be able to run the commands, and the host (or the server image) needs the database's client
tools. The commands run with the server's privileges, so the history directory and the scripts must not be writable
by other users.

## Limits

- History is what Kubernetes keeps: the chart's CronJobs keep 3 successful and 5 failed Jobs. Manual Jobs are owned by
  the CronJob and are pruned by the same limits. The audit chain keeps every manual trigger with its actor and reason.
- The person who triggered a run is in the audit chain, not on the Job (no personal data in cluster objects), so
  `requestedBy` is null for Kubernetes runs.
- Two server replicas: the run id is deterministic for the second and the active-run check runs before every start,
  so a double submit starts one run. A trigger within the same second gets `BACKUP_RUNNING`.
- Script adapter: the history holds the runs the console started. Runs that the host's scheduler starts are not
  seen, unless the scheduler starts them through the console API. `requestedBy` is null there too; the actor and
  reason are in the audit chain.
- Script adapter: the product ships no backup scripts. The operator writes them from `deploy/README.md` section 3
  (the same commands as the chart's CronJobs).

## Third-party dependencies

Jackson (Apache-2.0), through `sangam-kube`.
