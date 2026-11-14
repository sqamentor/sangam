# SPDX-License-Identifier: Apache-2.0
# Owner decision 258, FLOWABLE-INTO-OPERATON FC-18: move the history of one ended process instance to a later version
# of its process definition. Engine entry point W4-E org.sangam.engine.history.HistoricInstanceMigration.migrate
# (board DONE 2026-11-13T04:07:01Z); integrator ruling 04:07:40Z: W4-M contract, INT-H2 controller, idempotent command,
# permission history.migrate (W4-B CHANGE 04:13:28Z, Permissions.HISTORY_MIGRATE). Executed inside gen_openapi.py.
#
# Engine rules this contract carries (all checked inside one engine transaction under the root lock):
#   - only PROC_DEF_ID_ and PROC_DEF_KEY_ (PROCESS_DEF_* in ACT_HI_JOB_LOG) of the instance's ACT_HI rows change;
#     activity ids and everything that ran stay as recorded; called-instance histories keep their own definitions;
#   - the audit event PROCESS_HISTORY_MIGRATED (class B) commits in the same transaction;
#   - the instance is visible only in its own tenant; untenanted history only to a platform identity; otherwise 404.

_HM_TAG = "History"
_HM_REQ = ["FC-18", "R41", "R42", "R63"]
_HM_PERM = "history.migrate"
_HM_PATH = "/admin/history/process-instances/{id}/migrate"

SCHEMAS["HistoryInstanceMigrationRequest"] = {
    "type": "object",
    "description": "Move the history of an ended process instance to a later version of its definition (decision 258).",
    "required": ["targetDefinitionId", "reason"],
    "properties": {
        "targetDefinitionId": {"type": "string", "minLength": 1, "maxLength": 64,
                               "description": "Process definition id of the target: a deployed later version of the "
                                              "same key in the same tenant"},
        "reason": {"type": "string", "minLength": 1, "maxLength": 1000,
                   "description": "Mandatory reason for the audit record (R42)"},
    },
}
SCHEMAS["HistoryInstanceMigrationResult"] = {
    "type": "object",
    "description": "The moved history: old and new definition, and the rows changed per history table.",
    "required": ["processInstanceId", "fromDefinitionId", "toDefinitionId", "fromVersion", "toVersion",
                 "rowsByTable"],
    "properties": {
        "processInstanceId": {"type": "string"},
        "fromDefinitionId": {"type": "string", "description": "Definition id the history had before the move"},
        "toDefinitionId": {"type": "string", "description": "Definition id the history has now (the target)"},
        "fromVersion": {"type": "integer", "format": "int32", "description": "Version of the old definition"},
        "toVersion": {"type": "integer", "format": "int32", "description": "Version of the new definition; always "
                                                                           "greater than fromVersion"},
        "rowsByTable": {"type": "object", "additionalProperties": {"type": "integer", "format": "int32", "minimum": 0},
                        "description": "Rows changed per history table (ACT_HI_PROCINST is always 1; ACT_HI_IDENTITYLINK "
                                       "through the instance's history tasks; a table with no rows of the instance "
                                       "shows 0)"},
    },
}

op("post", _HM_PATH, "migrateHistoricProcessInstance", _HM_TAG, "Move an ended instance's history to a later version",
   "Rewrites only the process-definition id and key of the ended instance's history rows (ACT_HI_PROCINST, ACTINST, "
   "TASKINST, VARINST, DETAIL, INCIDENT, OP_LOG, EXT_TASK_LOG, DECINST, JOB_LOG, and IDENTITYLINK through the "
   "instance's history tasks) to the target definition, in one transaction under the root lock, together with the "
   "audit event PROCESS_HISTORY_MIGRATED (actor, reason, old and new definition and version, rows per table). "
   "Activity ids and everything that ran stay as recorded; called instances keep their own definitions. Operaton "
   "migration moves running instances only; this is for history. A retry with the same Idempotency-Key returns the "
   "same result. An instance of another tenant is 404 (no existence leak); untenanted history is visible only to a "
   "platform identity. Needs " + _HM_PERM + " (decision 258): tenant-scoped, held by the tenant administrator among the "
   "tenant roles (a TENANT ADMIN grant, not a history grant); the platform administrator reaches untenanted history. "
   "A replay of the stored response re-checks it.",
   _HM_REQ, params=[pp("id", "Process instance id (history), 1..64 characters")],
   body="HistoryInstanceMigrationRequest", resp=(200, "HistoryInstanceMigrationResult"),
   cmd="history.process-instance.migrate", perm=_HM_PERM)

_r = PATHS[_HM_PATH]["post"]["responses"]
_r["400"] = {"description": "VALIDATION_FAILED: id and targetDefinitionId must be 1..64 characters and reason "
                            "1..1000 characters, and the caller must be a person (not a system identity); or an "
                            "Idempotency-Key problem as for every command", "content": PROBLEM}
_r["404"] = {"description": "NOT_FOUND: no history instance with this id in the caller's tenant (untenanted history "
                            "only for a platform identity)", "content": PROBLEM}
_r["409"] = {"description": "HISTORY_INSTANCE_NOT_ENDED: the instance has not ended; HISTORY_REMOVAL_TIME_SET: the "
                            "history has a removal time; LEGAL_HOLD_ACTIVE: the root process instance is under an "
                            "active legal hold (in any tenant); or an Idempotency-Key conflict as for every command. "
                            "Nothing changed", "content": PROBLEM}
_r["422"] = {"description": "HISTORY_MIGRATION_TARGET_INVALID: the target is not a deployed later version of the same "
                            "key in the same tenant, or the instance's current definition is no longer deployed (its "
                            "version cannot be compared). Nothing changed", "content": PROBLEM}
