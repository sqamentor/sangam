# SPDX-License-Identifier: Apache-2.0
# M-135, owner decision 169: Flowable live-state migration. REST shape over sangam-ops
# org.sangam.ops.flowable.FlowableMigrationPipeline (W4-G REQUEST 2026-11-12T19:19:22Z; contract first, the controller
# and wiring are not W4-M's). Executed inside gen_openapi.py.
#
# A run moves live Flowable process instances of one source tenant into the caller's tenant (the tenant comes from
# the token, never from the body). Run states (FlowableMigrationStore.RunState): DRY_RUN, FROZEN, APPLYING, APPLIED,
# RECONCILED, RECONCILE_FAILED, ROLLED_BACK, CUT_OVER. A step not allowed in the run's state is 409
# FLOWABLE_MIGRATION_STATE (detail names the state and the allowed states); a lost compare-and-set is 409
# CONCURRENT_MODIFICATION. Every command is x-sangam-idempotent. The server needs a Flowable DataSource and a
# WorkerFreeze binding: until both are wired every operation answers 501 CAPABILITY_NOT_INSTALLED. Status: built on a
# synthetic corpus only (W4-G DONE 19:39:02Z, PARTIAL); no production-readiness claim.

_FM_TAG = "Tenant operations"
_FM_REQ = ["M-135", "R41", "R42", "VI.5"]
_FM_PERM = "ops.tenant.admin"
_FM_ADMIN = " Tenant administrators only (" + _FM_PERM + ")."
_FM_501 = {"description": "CAPABILITY_NOT_INSTALLED: the Flowable migration is not wired on this server (no Flowable "
                          "DataSource or WorkerFreeze binding)", "content": PROBLEM}
_FM_409 = {"description": "FLOWABLE_MIGRATION_STATE: the step is not allowed in the run's state (detail names the "
                          "state and the allowed states); CONCURRENT_MODIFICATION: another step changed the run first; "
                          "or an idempotency conflict as for every command", "content": PROBLEM}
_FM_ITEM_STATES = ["PLANNED", "REJECTED", "APPLYING", "APPLIED", "FAILED", "MATCHED", "MISMATCHED", "ROLLED_BACK"]
_FM_RUN_STATES = ["DRY_RUN", "FROZEN", "APPLYING", "APPLIED", "RECONCILED", "RECONCILE_FAILED", "ROLLED_BACK",
                  "CUT_OVER"]


def _fm_nullable(schema_name, *fields):
    for f in fields:
        p = SCHEMAS[schema_name]["properties"][f]
        if "$ref" in p:
            SCHEMAS[schema_name]["properties"][f] = {"anyOf": [{"$ref": p["$ref"]}, {"type": "null"}]}
        else:
            p["type"] = [p["type"], "null"]


schema("FlowableDefinitionMapping", "Maps one Flowable process definition onto a deployed Sangam definition.",
       "flowableKey flowableVersion:int targetKey targetVersion:int activityMap:smap",
       ["flowableKey", "flowableVersion", "targetKey", "targetVersion"],
       descs={"flowableKey": "Flowable process definition key",
              "flowableVersion": "Flowable definition version; null maps every version of the key",
              "targetKey": "Sangam process definition key", "targetVersion": "Sangam process definition version",
              "activityMap": "Flowable activity id to Sangam activity id, where they differ"})
_fm_nullable("FlowableDefinitionMapping", "flowableVersion")
schema("FlowableMigrationRequest", "A Flowable migration run (decision 169).",
       "runId sourceSystem sourceTenant sourceZone mappings:list:FlowableDefinitionMapping",
       ["runId", "sourceSystem", "sourceTenant", "sourceZone", "mappings"],
       descs={"runId": "Caller-chosen run id, unique in the tenant",
              "sourceSystem": "Name of the Flowable source system",
              "sourceTenant": "Flowable tenant id to read; null for Flowable's default (no) tenant",
              "sourceZone": "IANA time zone of the source's local timestamps (explicit; a DST fold or gap is "
                            "rejected per instance)"})
SCHEMAS["FlowableMigrationRequest"]["properties"]["sourceSystem"]["pattern"] = "^[A-Za-z0-9._-]{1,64}$"
_fm_nullable("FlowableMigrationRequest", "sourceTenant")
schema("FlowableMigrationReport", "State and counts of a run (or of a dry run, which writes nothing).",
       "runId state instances:long supported:long rejected:long rejectionsByCode:lmap warningsByCode:lmap "
       "tableCounts:lmap outOfScope:list:s applied:long failed:long matched:long mismatched:long problems:list:s",
       ["runId", "state", "instances", "supported", "rejected", "rejectionsByCode", "warningsByCode", "tableCounts",
        "outOfScope", "applied", "failed", "matched", "mismatched", "problems"],
       enums={"state": _FM_RUN_STATES},
       descs={"state": "Run state; null on a dry run",
              "rejectionsByCode": "Rejected instances per reject code",
              "warningsByCode": "Warnings per warn code",
              "tableCounts": "Rows per Flowable table read",
              "outOfScope": "Source content this migration does not carry (named, never silently dropped)",
              "problems": "Problems of the last step, in order"})
_fm_nullable("FlowableMigrationReport", "state")
schema("FlowableMigrationFinding", "One classification finding of an instance.",
       "severity code elementId message", ["severity", "code"], enums={"severity": ["REJECT", "WARN"]})
schema("FlowableMigrationItem", "One source instance of a run.",
       "sourceInstanceId status definitionKey targetInstanceId attempts:int error "
       "findings:list:FlowableMigrationFinding",
       ["sourceInstanceId", "status", "definitionKey", "targetInstanceId", "attempts", "error", "findings"],
       enums={"status": _FM_ITEM_STATES},
       descs={"targetInstanceId": "The Sangam instance; null before it is applied",
              "error": "Last apply or reconcile error; null when none"})
_fm_nullable("FlowableMigrationItem", "targetInstanceId", "error")
schema("FlowableMigrationRollbackRequest", "Roll a run back: the reason is audited.", "reason", ["reason"])
SCHEMAS["FlowableMigrationRollbackRequest"]["properties"]["reason"].update({"minLength": 1, "maxLength": 1000})

_FM = "/ops/flowable-migrations"
_FM_ERR = ("BadRequest", "Unauthorized", "Forbidden")
_FM_ID = pp("id", "Run id")


def _fm_extra(path, method, conflict=True):
    o = PATHS[path][method]
    o["responses"]["501"] = _FM_501
    if conflict:
        o["responses"]["409"] = _FM_409


op("post", _FM + "/dry-run", "dryRunFlowableMigration", _FM_TAG, "Dry-run a Flowable migration",
   "Reads the source and classifies every instance; writes nothing (state null)." + _FM_ADMIN, _FM_REQ,
   body="FlowableMigrationRequest", resp=(200, "FlowableMigrationReport"), cmd="ops.flowable-migration.dry-run",
   perm=_FM_PERM, errors=_FM_ERR)
_fm_extra(_FM + "/dry-run", "post", conflict=False)
op("post", _FM, "createFlowableMigration", _FM_TAG, "Create a Flowable migration run",
   "Plans the run (state DRY_RUN) and answers 201. 409 when the runId exists." + _FM_ADMIN, _FM_REQ,
   body="FlowableMigrationRequest", resp=(201, "FlowableMigrationReport"), created=True,
   cmd="ops.flowable-migration.create", perm=_FM_PERM, errors=_FM_ERR)
_fm_extra(_FM, "post")
op("get", _FM + "/{id}", "getFlowableMigration", _FM_TAG, "Get a Flowable migration run", "The run's report." + _FM_ADMIN,
   _FM_REQ, params=[_FM_ID], resp=(200, "FlowableMigrationReport"), perm=_FM_PERM,
   errors=("Unauthorized", "Forbidden", "NotFound"))
_fm_extra(_FM + "/{id}", "get", conflict=False)
op("get", _FM + "/{id}/items", "listFlowableMigrationItems", _FM_TAG, "Items of a Flowable migration run",
   "Source instances of the run with status and findings." + _FM_ADMIN, _FM_REQ, list_=True,
   params=[_FM_ID, {"name": "status", "in": "query", "required": False, "description": "Only items in this status",
                    "schema": {"type": "string", "enum": _FM_ITEM_STATES}}],
   resp=(200, "array:FlowableMigrationItem"), perm=_FM_PERM, errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound"))
_fm_extra(_FM + "/{id}/items", "get", conflict=False)
for _step, _opid, _summary, _desc in (
        ("freeze", "freezeFlowableMigration", "Freeze the source",
         "From DRY_RUN: freezes the Flowable workers and rechecks every planned instance's fingerprint. When the "
         "workers are not drained or an instance changed or vanished, the problems are listed and the run stays "
         "DRY_RUN; otherwise FROZEN."),
        ("apply", "applyFlowableMigration", "Apply the run",
         "From FROZEN, APPLYING or APPLIED: creates the Sangam instances, idempotent per item (APPLYING, then "
         "APPLIED)."),
        ("reconcile", "reconcileFlowableMigration", "Reconcile the run",
         "Compares every applied instance with its plan (RECONCILED or RECONCILE_FAILED)."),
        ("cut-over", "cutOverFlowableMigration", "Cut over",
         "From RECONCILED: CUT_OVER. Then end the migrated instances on Flowable and release; rejected instances "
         "stay on Flowable."),
        ("release", "releaseFlowableMigration", "Release the source freeze",
         "From DRY_RUN, FROZEN (back to DRY_RUN), ROLLED_BACK or CUT_OVER: lifts the Flowable freeze. After CUT_OVER "
         "it is refused, with a problem, while a migrated instance still exists on Flowable.")):
    op("post", _FM + "/{id}/" + _step, _opid, _FM_TAG, _summary,
       _desc + " No body; 200 with the report and its problems list." + _FM_ADMIN, _FM_REQ, params=[_FM_ID],
       resp=(200, "FlowableMigrationReport"), cmd="ops.flowable-migration." + _step, perm=_FM_PERM,
       errors=("Unauthorized", "Forbidden", "NotFound"))
    _fm_extra(_FM + "/{id}/" + _step, "post")
op("post", _FM + "/{id}/rollback", "rollbackFlowableMigration", _FM_TAG, "Roll back the run",
   "From APPLYING, APPLIED, RECONCILED or RECONCILE_FAILED, while Flowable is still frozen: deletes the run's Sangam "
   "instances (ROLLED_BACK). Refused as a whole, with problems and no change, when Flowable is no longer frozen or a "
   "target instance has moved on. 400 REASON_REQUIRED without a reason."
   + _FM_ADMIN, _FM_REQ, params=[_FM_ID], body="FlowableMigrationRollbackRequest",
   resp=(200, "FlowableMigrationReport"), cmd="ops.flowable-migration.rollback", perm=_FM_PERM,
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound"))
_fm_extra(_FM + "/{id}/rollback", "post")
