# SPDX-License-Identifier: Apache-2.0
# M-056, owner decision 146 (W4Q-01): the executor partition map (SANGAM.md X.2.1) and a controlled change of the
# partition count (X.2.1.7). Contract-first for W4-E (engine: org.sangam.engine.job.PartitionLeaseManager, EXEC_MAP,
# EXEC_PARTITION, EXEC_SLOT) and the REST host. Executed inside gen_openapi.py.
#
# Binding rules of decision 146 that this contract carries:
#   - a resize is a controlled ownership transition between map epochs (allocation generations): logical slot ids stay
#     stable, only the slots needed for the new balanced allocation move (minimal movement), and a partition may own
#     non-contiguous slot ranges; every slot has exactly one owner partition in each committed map;
#   - a stale executor cannot claim or commit after losing ownership (epoch fencing); partition ownership is never
#     treated as exactly-once, so external side effects keep their stable idempotency keys;
#   - the request is durable, idempotent (Idempotency-Key) and concurrency-controlled: while a resize is REQUESTED or
#     IN_PROGRESS another one is refused with 409, and expectedMapEpoch lets a caller refuse to act on a stale view;
#   - platform-level authorisation (ADMIN_EXECUTOR, X.2.1.7), a mandatory reason, and audit of actor, reason, old and
#     new counts, map epoch, moved slots and outcome; EXECUTOR_PARTITION_COUNT_CHANGED is written when the new map
#     commits (state COMPLETED), never before;
#   - any handover pause is reported, never hidden: the server makes no claim of uninterrupted execution.

TAGS.append(("Executor", "Executor partition map and partition-count changes (X.2.1, M-056, decision 146); platform "
                         "operators only"))
_EX_REQ = ["X.2.1", "X.2.1.7", "M-056", "R42"]
_EX_PERM = "executor.admin"
_EX_PERM_TEXT = (" Platform-level permission executor.admin (ADMIN_EXECUTOR, X.2.1.7); a tenant administrator never "
                 "holds it.")

schema("ExecutorSlotRange", "An inclusive range of logical slot ids. Slot ids are stable for the life of the cluster.",
       "first:int last:int", ["first", "last"])
schema("ExecutorPartitionView", "One partition (EXEC_PARTITION row) and the slots it owns in the committed map M. "
                                "While a resize runs, a slot of the stored plan stays under its fromPartition until "
                                "M+1 commits; a target partition without committed slots has slots empty.",
       "partitionId:int state ownerNode ownerEpoch:long leaseExpiresAt:dt slots:list:ExecutorSlotRange",
       ["partitionId", "state", "ownerEpoch", "slots"],
       enums={"state": ["UNOWNED", "ACTIVE", "DRAINING", "RETIRED", "SHARED"]},
       descs={"partitionId": "1..partitionCount; 0 is the virtual SHARED partition when partitioning is off",
              "ownerNode": "Executor node holding the lease; absent when UNOWNED, RETIRED or SHARED",
              "ownerEpoch": "Fencing epoch of the current owner; strictly increasing, never reused",
              "leaseExpiresAt": "Lease expiry on the engine database clock; absent without an owner",
              "slots": "Owned slot ranges, sorted; they need not be contiguous after a minimal-movement resize"})
schema("ExecutorSlotMove", "A slot range that changes partition in a resize (the stored plan, PartitionAllocation.Move). "
                           "While the resize runs a moving slot takes no new acquisitions until it is STABLE under "
                           "the target; per-slot progress is not part of this view.",
       "first:int last:int fromPartition:int toPartition:int",
       ["first", "last", "fromPartition", "toPartition"])
schema("ExecutorPartitionResize", "A partition-count change: a controlled transition from map epoch M to M+1.",
       "id state fromPartitionCount:int toPartitionCount:int fromMapEpoch:long toMapEpoch:long algorithmVersion "
       "movedSlotCount:int movedSlots:list:ExecutorSlotMove actor reason requestedAt:dt startedAt:dt completedAt:dt "
       "failure handoverPauseMs:long",
       ["id", "state", "fromPartitionCount", "toPartitionCount", "fromMapEpoch", "toMapEpoch", "algorithmVersion",
        "actor", "reason", "requestedAt"],
       enums={"state": ["REQUESTED", "IN_PROGRESS", "COMPLETED", "FAILED"]},
       descs={"id": "Resize id (the audit and recovery key), 1..64 characters, chosen by the server",
              "movedSlotCount": "Number of slots that change partition; absent while REQUESTED (the plan is made "
                                "when the coordinator starts the resize)",
              "toPartitionCount": "0 turns partitioning off (the single SHARED partition 0); otherwise 1..slotCount",
              "toMapEpoch": "fromMapEpoch + 1; committed only when state is COMPLETED",
              "algorithmVersion": "Version of the deterministic minimal-movement allocation algorithm used",
              "movedSlots": "Slot ranges that change partition; empty while REQUESTED and in list views",
              "actor": "Authenticated platform principal that requested the resize (server-side identity)",
              "startedAt": "When the first slot started MOVING; absent while REQUESTED",
              "completedAt": "When the new map committed (COMPLETED) or the resize stopped (FAILED)",
              "failure": "Why the resize failed; the previous map stays committed and every slot keeps one owner",
              "handoverPauseMs": "Longest time a moved slot had no acquiring owner, as measured; absent until "
                                 "COMPLETED. Reported, never assumed to be zero"})
schema("ExecutorPartitionMap", "The committed executor map of the cluster (EXEC_MAP) and its partitions.",
       "clusterId state currentMapEpoch:long targetMapEpoch:long partitionCount:int slotCount:int algorithmVersion "
       "partitions:list:ExecutorPartitionView activeResize:ExecutorPartitionResize",
       ["clusterId", "state", "currentMapEpoch", "partitionCount", "slotCount", "algorithmVersion", "partitions"],
       enums={"state": ["STABLE", "TRANSITION"]},
       descs={"targetMapEpoch": "currentMapEpoch + 1 while state is TRANSITION; absent when STABLE",
              "partitionCount": "Partitions owning slots in the committed map; 0 when partitioning is off (SHARED)",
              "slotCount": "S, the fixed number of logical slots",
              "algorithmVersion": "Allocation that produced the committed map: the newest COMPLETED resize's (MM-1), "
                                  "or CONTIGUOUS-1 for a map never resized (contiguous balanced ranges, X.2.1.7 step 1)",
              "partitions": "Every EXEC_PARTITION row sorted by partitionId, RETIRED and SHARED included",
              "activeResize": "The REQUESTED or IN_PROGRESS resize, with its moved slot ranges; absent when none"})
schema("ExecutorPartitionResizeRequest", "Change the partition count (X.2.1.7).",
       "partitionCount:int reason expectedMapEpoch:long", ["partitionCount", "reason"],
       descs={"partitionCount": "New count: 0 (partitioning off) or 1..slotCount; the current count is 409 "
                                "INVALID_TRANSITION",
              "reason": "Mandatory reason for the audit record (R42)",
              "expectedMapEpoch": "Optional optimistic check: when given and not the current map epoch, 409 "
                                  "REVISION_CONFLICT and nothing changes"})
SCHEMAS["ExecutorPartitionResizeRequest"]["properties"]["partitionCount"]["minimum"] = 0
SCHEMAS["ExecutorPartitionResizeRequest"]["properties"]["reason"]["minLength"] = 1
SCHEMAS["ExecutorPartitionResizeRequest"]["properties"]["reason"]["maxLength"] = 1000

op("get", "/admin/executor/partitions", "getExecutorPartitionMap", "Executor", "Executor partition map",
   "The committed map, its partitions with owners, epochs and slot ranges, and the active resize if any, read in one "
   "transaction (PartitionResize.map). 409 NOT_INITIALISED when the cluster has no executor map "
   "(sangam.engine.executor-partitioning was never on)." + _EX_PERM_TEXT,
   _EX_REQ, resp=(200, "ExecutorPartitionMap"), perm=_EX_PERM, errors=("Unauthorized", "Forbidden", "Conflict"))
PATHS["/admin/executor/partitions"]["get"]["responses"]["409"] = {
    "description": "NOT_INITIALISED: the cluster has no executor map (partitioning was never on)", "content": PROBLEM}
op("post", "/admin/executor/partitions", "resizeExecutorPartitions", "Executor", "Change the partition count",
   "Starts a controlled resize and answers 202 with it in state REQUESTED; poll getExecutorPartitionResize. Minimal "
   "movement: only the slots the new balanced allocation needs move, each drained or recovered (job state, retry "
   "counts, deadlines and tenant context kept) before its new owner acquires from it, under epoch fencing. 409 "
   "INVALID_TRANSITION when another resize is REQUESTED or IN_PROGRESS (also one started concurrently) or the count "
   "is unchanged; 409 REVISION_CONFLICT on a stale expectedMapEpoch; 409 NOT_INITIALISED when the cluster has no "
   "executor map (sangam.engine.executor-partitioning is off on every node); 400 VALIDATION_FAILED when the count "
   "is outside 0..slotCount or the reason is blank or longer than 1000 characters. A retry with the same "
   "Idempotency-Key returns the same resize. Audited: EXECUTOR_PARTITION_RESIZE_REQUESTED at the request, "
   "EXECUTOR_PARTITION_COUNT_CHANGED when the new map commits, EXECUTOR_PARTITION_RESIZE_FAILED when it fails (the "
   "previous map stays committed)." + _EX_PERM_TEXT,
   _EX_REQ + ["R41"], body="ExecutorPartitionResizeRequest", resp=(202, "ExecutorPartitionResize"),
   cmd="executor.partitions.resize", perm=_EX_PERM)
op("get", "/admin/executor/partitions/resizes", "listExecutorPartitionResizes", "Executor", "Partition-count changes",
   "Resizes, newest first; movedSlots is empty in the list." + _EX_PERM_TEXT, _EX_REQ, list_=True,
   params=[q("state", enum=["REQUESTED", "IN_PROGRESS", "COMPLETED", "FAILED"])],
   resp=(200, "array:ExecutorPartitionResize"), perm=_EX_PERM, errors=STD_R)
op("get", "/admin/executor/partitions/resizes/{id}", "getExecutorPartitionResize", "Executor",
   "A partition-count change", "One resize with its moved slot ranges; 404 NOT_FOUND for an unknown id." +
   _EX_PERM_TEXT, _EX_REQ,
   params=[ID], resp=(200, "ExecutorPartitionResize"), perm=_EX_PERM, errors=GET_E)

# Integrator ruling 2026-11-13T01:26:13Z: an absent optional module is 501 CAPABILITY_NOT_INSTALLED (not retryable).
# ExecutorPartitionController answers it on every route when the host has no engine partition port bound, after the
# permission check (so 401 and 403 come first).
_EX_OFF = {"description": "CAPABILITY_NOT_INSTALLED: this server has no executor partition engine (sangam-engine "
                          "port not bound). Not retryable; checked after executor.admin", "content": PROBLEM}
for _p, _m in (("/admin/executor/partitions", "get"), ("/admin/executor/partitions", "post"),
               ("/admin/executor/partitions/resizes", "get"), ("/admin/executor/partitions/resizes/{id}", "get")):
    PATHS[_p][_m]["responses"]["501"] = _EX_OFF
