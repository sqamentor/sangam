# SPDX-License-Identifier: Apache-2.0
# M-102, owner decision 155 (W4Q-08): the backup console. 0.1.0 lists the backup targets, triggers a backup now and
# shows the run history; restore stays the documented offline procedure (no restore route). Shapes from W4-I (REQUEST
# 2026-11-12T18:55:50Z, corrected 19:10:07Z, addendum 19:49:56Z): the console service in sangam-backup over the shared sangam-kube client.
# Executed inside gen_openapi.py.
#
# A target is one backup CronJob of the Helm chart (templates/backup-cronjobs.yaml); a run is one Kubernetes Job of a
# target (scheduled by the CronJob, or created from it by triggerBackup). The server's namespaced Role is off by default:
# while the console is not configured every operation answers 501 BACKUP_CONSOLE_NOT_CONFIGURED; a server without the
# sangam-backup module answers 501 CAPABILITY_NOT_INSTALLED.
# Owner decision 220 (W4-I CHANGE 2026-11-13T00:14:02Z): a VM/compose install gets the same console through
# ScriptBackupTargets: a target is an operator-configured backup command (the documented backup script; no shell, fixed
# arguments, cleared environment, timeout) and a run is one execution of it, on demand, with its run history recorded
# by the server. The operations and shapes are the same for both install types.

_BK_TAG = "Tenant operations"
_BK_REQ = ["R19", "VI.5", "M-102"]
_BK_501 = {"description": "BACKUP_CONSOLE_NOT_CONFIGURED: the backup console is off (on Kubernetes set "
                          "sangam.backup.console.kubernetes.enabled; on a VM or compose install configure the backup "
                          "command; the default is off), or CAPABILITY_NOT_INSTALLED: the sangam-backup module is not "
                          "installed", "content": PROBLEM}
_BK_502_READ = {"description": "BACKUP_PLATFORM_ERROR: the Kubernetes API failed or refused the service account",
                "content": PROBLEM}


def _bk_nullable(schema_name, *fields):
    for f in fields:
        SCHEMAS[schema_name]["properties"][f]["type"] = [SCHEMAS[schema_name]["properties"][f]["type"], "null"]


schema("BackupTarget", "A backup target: one backup CronJob (Kubernetes) or one configured backup command "
                       "(VM/compose, decision 220).",
       "id name schedule timeZone suspended:bool active:int lastScheduleTime:dt lastSuccessfulTime:dt",
       ["id", "name", "schedule", "timeZone", "suspended", "active", "lastScheduleTime", "lastSuccessfulTime"],
       descs={"id": "The CronJob name, or the configured command's target name", "name": "The chart's backup name, for example pg-full",
              "schedule": "Cron schedule of the CronJob", "timeZone": "Time zone of the schedule; null when unset",
              "suspended": "True when the CronJob is suspended", "active": "Jobs of the target running now",
              "lastScheduleTime": "Last time the CronJob started a run; null before the first",
              "lastSuccessfulTime": "Last successful completion; null before the first"})
_bk_nullable("BackupTarget", "timeZone", "lastScheduleTime", "lastSuccessfulTime")
schema("BackupTriggerRequest", "Back up now: the reason is audited.", "reason", ["reason"])
SCHEMAS["BackupTriggerRequest"]["properties"]["reason"].update({"minLength": 1, "maxLength": 1000})
schema("BackupRun", "One backup run: a Kubernetes Job of a target, or one execution of a configured backup "
                    "command.",
       "id target trigger status startedAt:dt finishedAt:dt requestedBy message",
       ["id", "target", "trigger", "status"],
       enums={"trigger": ["SCHEDULED", "MANUAL"], "status": ["PENDING", "RUNNING", "SUCCEEDED", "FAILED"]},
       descs={"id": "The Job name, or the run id the server recorded for a command run",
              "target": "Target id",
              "startedAt": "When the Job started; null while PENDING",
              "finishedAt": "When the Job succeeded or failed; null before",
              "requestedBy": "Authenticated principal of a MANUAL run; null for SCHEDULED runs",
              "message": "Short outcome text from the Job status or the command's exit; never log content or "
                         "credentials"})
_bk_nullable("BackupRun", "startedAt", "finishedAt", "requestedBy", "message")

op("get", "/ops/backup/targets", "listBackupTargets", _BK_TAG, "Backup targets",
   "The deployment's backup targets with schedule and last run times. Platform operators.", _BK_REQ,
   resp=(200, "array:BackupTarget"), perm="ops.read", errors=("Unauthorized", "Forbidden"))
PATHS["/ops/backup/targets"]["get"]["responses"]["501"] = _BK_501
PATHS["/ops/backup/targets"]["get"]["responses"]["502"] = _BK_502_READ
op("post", "/ops/backup/targets/{id}/runs", "triggerBackup", _BK_TAG, "Back up now",
   "Starts one run of the target now (a Job from its CronJob, or one execution of its configured backup command) "
   "and answers 202 with it. 400 REASON_REQUIRED without a reason; 404 "
   "for an unknown target; 409 BACKUP_RUNNING while a run of the target is active; 502 BACKUP_TRIGGER_FAILED when "
   "the run could not be started (Kubernetes refused or failed the Job creation, or the backup command could not be "
   "started). BACKUP_TRIGGERED is audited before the run is started. Platform "
   "operators.", _BK_REQ + ["R41", "R42"], params=[pp("id", "Target id")],
   body="BackupTriggerRequest", resp=(202, "BackupRun"), cmd="ops.backup.trigger", perm="ops.write",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound"))
PATHS["/ops/backup/targets/{id}/runs"]["post"]["responses"]["501"] = _BK_501
PATHS["/ops/backup/targets/{id}/runs"]["post"]["responses"]["502"] = {
    "description": "BACKUP_TRIGGER_FAILED: the run could not be started (Kubernetes refused or failed the Job "
                   "creation, or the backup command could not be started)", "content": PROBLEM}
op("get", "/ops/backup/runs", "listBackupRuns", _BK_TAG, "Backup run history",
   "Runs still known to the cluster (scheduled and manual), newest first. Platform operators.", _BK_REQ, list_=True,
   params=[q("target", desc="Only runs of this target id")], resp=(200, "array:BackupRun"), perm="ops.read",
   errors=("Unauthorized", "Forbidden"))
PATHS["/ops/backup/runs"]["get"]["responses"]["501"] = _BK_501
PATHS["/ops/backup/runs"]["get"]["responses"]["502"] = _BK_502_READ
