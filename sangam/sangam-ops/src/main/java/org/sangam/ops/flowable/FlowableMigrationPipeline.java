// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.flowable.FlowableMigrationStore.Item;
import org.sangam.ops.flowable.FlowableMigrationStore.ItemStatus;
import org.sangam.ops.flowable.FlowableMigrationStore.Run;
import org.sangam.ops.flowable.FlowableMigrationStore.RunState;
import org.sangam.ops.flowable.FlowableSnapshot.Instance;
import org.sangam.ops.flowable.InstancePlan.Finding;
import org.sangam.ops.flowable.InstancePlan.Severity;
import org.sangam.ops.flowable.OperatonStateWriter.TargetState;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * M-135 (R48 / E20, owner decision 169): live execution-state migration from Flowable to Operaton as an explicit,
 * resumable state machine. Built and tested on a synthetic Flowable corpus; validation on real data waits for the
 * owner's corpus, and until then nothing here claims production readiness.
 *
 * <pre>
 *   dryRun ............ inventory + classification, writes nothing anywhere
 *   plan .............. (new run) -> DRY_RUN          items PLANNED or REJECTED, fingerprints recorded
 *   freeze ............ DRY_RUN -> FROZEN             WorkerFreeze engaged and drained; every PLANNED instance re-read
 *                                                    and unchanged (else refused: plan again)
 *   apply ............. FROZEN|APPLYING|APPLIED -> APPLYING -> APPLIED
 *                                                    per item: still frozen, fingerprint unchanged, not migrated by
 *                                                    another run, provenance lookup (adopt, never start twice), one
 *                                                    engine transaction; FAILED items are retried by a later apply
 *   reconcile ......... APPLIED|RECONCILE_FAILED -> RECONCILED | RECONCILE_FAILED
 *   rollback .......... APPLYING|APPLIED|RECONCILED|RECONCILE_FAILED -> ROLLED_BACK
 *                                                    only while Flowable is still frozen and only if no migrated
 *                                                    instance has moved on the target (no finished task, no executed
 *                                                    job, the same active activities); otherwise refused as a whole
 *   cutOver ........... RECONCILED -> CUT_OVER        no rollback afterwards
 *   release ........... DRY_RUN|FROZEN -> DRY_RUN (abort), ROLLED_BACK, or CUT_OVER once no migrated instance is
 *                                                    left on Flowable (the operator ends them there; this tool never
 *                                                    writes to Flowable)
 * </pre>
 *
 * Duplicate-effect protection: the target instance starts at wait states only, without custom listeners or IO mappings
 * ({@link OperatonStateWriter#apply}); a failed or locked source job rejects the instance (its effect may have
 * happened); each source instance is applied at most once across runs of the same source system; Flowable stays frozen
 * until the migrated instances are gone from it. Ending them on Flowable runs the source model's delete task listeners
 * and process event listeners (decision 169). Owner decision 231: an instance whose model declares such listeners is
 * REJECTED (SOURCE_DELETE_LISTENER, SOURCE_EVENT_LISTENER) unless an operator acknowledged that definition's listeners
 * ({@link #acknowledgeListeners}), and then they are WARN findings on its item; an unreadable model always rejects; the
 * cut-over report states what the model cannot show. Every transition writes an audit event on the chain
 * {@code migration:flowable:<runId>} in the same transaction as the run or item change.
 */
public final class FlowableMigrationPipeline {

    public static final String EVENT_PLANNED = "FLOWABLE_MIGRATION_PLANNED";
    public static final String EVENT_FROZEN = "FLOWABLE_MIGRATION_FROZEN";
    public static final String EVENT_INSTANCE_MIGRATED = "FLOWABLE_INSTANCE_MIGRATED";
    public static final String EVENT_RECONCILED = "FLOWABLE_MIGRATION_RECONCILED";
    public static final String EVENT_ROLLED_BACK = "FLOWABLE_MIGRATION_ROLLED_BACK";
    public static final String EVENT_CUT_OVER = "FLOWABLE_MIGRATION_CUT_OVER";
    public static final String EVENT_RELEASED = "FLOWABLE_MIGRATION_RELEASED";
    /** Decision 231; name proposed to INT-H2 for SANGAM.md X.4.0.3. */
    public static final String EVENT_LISTENERS_ACKNOWLEDGED = "FLOWABLE_MIGRATION_LISTENERS_ACKNOWLEDGED";

    /** What to migrate: one source tenant of one Flowable system, with its definition mappings. */
    public record Request(String runId, String tenantId, String sourceSystem, String sourceTenant, ZoneId sourceZone,
                          List<DefinitionMapping> mappings) {
        public Request {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(sourceSystem, "sourceSystem");
            Objects.requireNonNull(sourceZone, "sourceZone");
            mappings = List.copyOf(mappings);
            if (!sourceSystem.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("sourceSystem: 1-64 letters, digits, '.', '_' or '-'");
            }
        }
    }

    /** The outcome of a step: counts, rejection and warning codes, and the problems that stopped anything. */
    public record Report(String runId, RunState state, long instances, long supported, long rejected,
                         Map<String, Long> rejectionsByCode, Map<String, Long> warningsByCode, Map<String, Long> tableCounts,
                         List<String> outOfScope, long applied, long failed, long matched, long mismatched,
                         List<String> problems) {
    }

    private final FlowableRows source;
    private final OperatonStateWriter writer;
    private final OperatonStateWriter.TargetModel model;
    private final WorkerFreeze freeze;
    private final Transactions tx;
    private final FlowableMigrationStore store;
    private final AuditChain audit;
    private final Clock clock;
    private final int defaultRetries;

    public FlowableMigrationPipeline(FlowableRows source, OperatonStateWriter writer, OperatonStateWriter.TargetModel model,
                                     WorkerFreeze freeze, Transactions tx, Dialect dialect, AuditChain audit, Clock clock,
                                     int defaultRetries) {
        this.source = Objects.requireNonNull(source, "source");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.model = Objects.requireNonNull(model, "model");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.store = new FlowableMigrationStore(dialect);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultRetries = defaultRetries;
    }

    // ------------------------------------------------------------------ dry run and plan

    /** Inventory and classification only: nothing is written to Flowable, Operaton or the product database. */
    public Report dryRun(Request r) {
        Classified c = classify(r);
        return report(r.runId(), null, c, List.of());
    }

    /** Records the dry run as a new run (DRY_RUN) with one item per instance. */
    public Report plan(Request r, String actor) {
        Classified c = classify(r);
        tx.inTransaction(conn -> {
            if (store.run(conn, r.runId()).isPresent()) {
                throw new IllegalStateException("run " + r.runId() + " already exists");
            }
            long supported = c.plans.stream().filter(p -> p.status() == InstancePlan.Status.SUPPORTED).count();
            store.createRun(conn, new Run(r.runId(), r.tenantId(), r.sourceSystem(), r.sourceTenant(), r.sourceZone().getId(),
                    RunState.DRY_RUN, r.mappings(), supported, c.plans.size() - supported, 0, 0, 0, 0, 1), actor);
            store.insertItems(conn, r.runId(), c.plans, r.tenantId());
            Report rep = report(r.runId(), RunState.DRY_RUN, c, List.of());
            store.report(conn, r.runId(), FlowableMigrationStore.write(rep));
            audit.append(conn, r.tenantId(), chain(r.runId()), EVENT_PLANNED, payload(r.runId(), actor, "instances",
                    c.plans.size(), "supported", supported, "sourceSystem", r.sourceSystem(), "rejections", rep.rejectionsByCode()));
            return null;
        });
        return report(r.runId(), RunState.DRY_RUN, c, List.of());
    }

    private record Classified(FlowableSnapshot snapshot, List<InstancePlan> plans) {
    }

    // ------------------------------------------------------------------ decision 231: listener acknowledgement

    /**
     * Records that the operator accepts the model listeners of one source process definition firing when its migrated
     * instances are ended on Flowable after cut-over (owner decision 231). Bound to the fingerprint of the listeners
     * read now; written with audit event {@value #EVENT_LISTENERS_ACKNOWLEDGED} in the same transaction. Takes effect
     * for runs planned afterwards (a planned run keeps its classification). Refused when the definition is not in the
     * source tenant, when its model cannot be read (SOURCE_MODEL_UNREAD always rejects) and when it declares no
     * listener that ending an instance fires. Repeating it for the same listeners changes nothing.
     *
     * @return the stored acknowledgement
     */
    public FlowableMigrationStore.Acknowledgement acknowledgeListeners(String tenantId, String sourceSystem, String sourceTenant,
                                                                       String definitionId, String actor, String reason) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceSystem, "sourceSystem");
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("an acknowledgement needs an actor and a reason of 1-1000 characters");
        }
        FlowableInventory.Definition d = new FlowableInventory(source, ZoneId.of("UTC")).definition(definitionId, sourceTenant)
                .orElseThrow(() -> new IllegalArgumentException("no process definition " + definitionId + " of source tenant "
                        + sourceTenant + " in " + sourceSystem));
        FlowableModelListeners.Found l = d.listeners();
        if (!l.read()) {
            throw new IllegalStateException("the model of " + definitionId + " was not read (" + l.problem() + "): "
                    + StateClassifier.SOURCE_MODEL_UNREAD + " cannot be acknowledged");
        }
        if (!l.firesOnEnd()) {
            throw new IllegalArgumentException("definition " + definitionId + " declares no listener that ending an instance "
                    + "fires; nothing to acknowledge");
        }
        return tx.inTransaction(c -> {
            java.util.Optional<FlowableMigrationStore.Acknowledgement> old = store.acknowledgement(c, tenantId, sourceSystem, definitionId);
            if (old.isPresent() && old.get().listenersSha256().equals(l.fingerprint())) {
                return old.get();
            }
            FlowableMigrationStore.Acknowledgement a = new FlowableMigrationStore.Acknowledgement(tenantId, sourceSystem,
                    definitionId, d.key(), d.version(), l.fingerprint(), FlowableMigrationStore.write(Map.of(
                    "taskListeners", new java.util.TreeMap<>(l.taskListeners()), "processListeners", l.processListeners())),
                    reason, actor, org.sangam.core.layer.Jdbc.dbNow(c, store.dialect()));
            store.acknowledge(c, a);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("actor", actor);
            p.put("sourceSystem", sourceSystem);
            p.put("definitionId", definitionId);
            p.put("definitionKey", d.key());
            p.put("definitionVersion", d.version());
            p.put("listenersSha256", l.fingerprint());
            p.put("replaces", old.map(FlowableMigrationStore.Acknowledgement::listenersSha256).orElse(null));
            p.put("reason", reason);
            audit.append(c, tenantId, ackChain(sourceSystem, definitionId), EVENT_LISTENERS_ACKNOWLEDGED, p);
            return a;
        });
    }

    /** Audit root of a definition's acknowledgements (at most 64 characters, so a hash of system and definition id). */
    static String ackChain(String sourceSystem, String definitionId) {
        return "flowable-ack:" + org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(sourceSystem + "\n"
                + definitionId)).substring(0, 48);
    }

    private Classified classify(Request r) {
        FlowableSnapshot snap = new FlowableInventory(source, r.sourceZone()).read(r.sourceTenant());
        Map<String, String> acks = tx.inTransaction(c -> store.acknowledgements(c, r.tenantId(), r.sourceSystem()));
        StateClassifier cl = new StateClassifier(r.mappings(), model, defaultRetries, acks);
        List<InstancePlan> plans = new ArrayList<>();
        for (Instance in : snap.instances()) {
            plans.add(cl.classify(in, clock.instant()));
        }
        return new Classified(snap, plans);
    }

    // ------------------------------------------------------------------ freeze

    public Report freeze(String runId, String actor) {
        Run run = require(runId, EnumSet.of(RunState.DRY_RUN));
        freeze.freeze(runId);
        List<String> problems = new ArrayList<>();
        if (!freeze.drained(runId)) {
            problems.add("the Flowable workers are not drained yet; try again when the running jobs have ended");
        }
        List<Item> planned = tx.inTransaction(c -> store.items(c, runId, List.of(ItemStatus.PLANNED)));
        Map<String, Instance> now = inventory(run).reread(planned.stream().map(Item::sourceInstanceId).toList());
        for (Item it : planned) {
            Instance in = now.get(it.sourceInstanceId());
            if (in == null) {
                problems.add(it.sourceInstanceId() + " no longer exists on Flowable");
            } else if (!in.fingerprint().equals(it.fingerprint())) {
                problems.add(it.sourceInstanceId() + " changed after the dry run");
            }
        }
        if (!problems.isEmpty()) {
            return stateReport(runId, problems); // run stays DRY_RUN; plan a new run when the source changed
        }
        transition(run, RunState.FROZEN, EVENT_FROZEN, actor, Map.of("items", planned.size()));
        return stateReport(runId, List.of());
    }

    // ------------------------------------------------------------------ apply

    public Report apply(String runId, String actor) {
        Run run = require(runId, EnumSet.of(RunState.FROZEN, RunState.APPLYING, RunState.APPLIED));
        if (!freeze.frozen(runId)) {
            return stateReport(runId, List.of("Flowable is not frozen for run " + runId + "; nothing applied"));
        }
        if (run.state() != RunState.APPLYING) {
            run = transition(run, RunState.APPLYING, null, actor, Map.of());
        }
        List<Item> todo = tx.inTransaction(c -> store.items(c, runId, List.of(ItemStatus.PLANNED, ItemStatus.FAILED, ItemStatus.APPLYING)));
        Map<String, Instance> current = inventory(run).reread(todo.stream().map(Item::sourceInstanceId).toList());
        List<String> problems = new ArrayList<>();
        for (Item it : todo) {
            if (!stillApplying(runId)) {
                problems.add("run " + runId + " left APPLYING; stopped");
                break;
            }
            applyOne(run, it, current.get(it.sourceInstanceId()), actor, problems);
        }
        if (stillApplying(runId)) {
            Run latest = require(runId, EnumSet.of(RunState.APPLYING));
            transition(latest, RunState.APPLIED, null, actor, Map.of());
        }
        return stateReport(runId, problems);
    }

    private void applyOne(Run run, Item it, Instance now, String actor, List<String> problems) {
        String sid = it.sourceInstanceId();
        String provenance = run.sourceSystem() + ":" + sid;
        String refusal = null;
        if (!freeze.frozen(run.runId())) {
            refusal = "Flowable was released during apply";
        } else if (now == null) {
            refusal = "the source instance no longer exists";
        } else if (!now.fingerprint().equals(it.fingerprint())) {
            refusal = "the source instance changed while frozen";
        } else {
            refusal = tx.inTransaction(c -> store.appliedElsewhere(c, run.sourceSystem(), sid, run.runId())
                    .map(t -> "already migrated by another run (target " + t + ")").orElse(null));
        }
        if (refusal != null) {
            String why = refusal;
            tx.inTransaction(c -> {
                store.finish(c, run.runId(), sid, ItemStatus.FAILED, null, why);
                return null;
            });
            problems.add(sid + ": " + why);
            return;
        }
        boolean begun = tx.inTransaction(c -> store.begin(c, run.runId(), sid, it.status()));
        if (!begun) {
            problems.add(sid + ": changed by another apply; skipped");
            return;
        }
        String target;
        boolean adopted;
        try {
            Optional<String> existing = writer.findMigrated(provenance);
            adopted = existing.isPresent();
            target = existing.orElseGet(() -> writer.apply(it.plan(), provenance));
        } catch (RuntimeException e) {
            tx.inTransaction(c -> {
                store.finish(c, run.runId(), sid, ItemStatus.FAILED, null, String.valueOf(e.getMessage()));
                return null;
            });
            problems.add(sid + ": " + e.getMessage());
            return;
        }
        boolean kept = tx.inTransaction(c -> {
            Optional<Run> r = store.run(c, run.runId());
            if (r.isEmpty() || r.get().state() != RunState.APPLYING) {
                return false;
            }
            store.finish(c, run.runId(), sid, ItemStatus.APPLIED, target, null);
            audit.append(c, run.tenantId(), chain(run.runId()), EVENT_INSTANCE_MIGRATED, payload(run.runId(), actor,
                    "sourceInstance", sid, "targetInstance", target, "provenance", provenance, "adopted", adopted,
                    "fingerprint", it.fingerprint()));
            return true;
        });
        if (!kept) {
            // the run was rolled back concurrently: the instance created now must not survive it
            writer.delete(target, "migration run " + run.runId() + " rolled back during apply");
            tx.inTransaction(c -> {
                store.finish(c, run.runId(), sid, ItemStatus.ROLLED_BACK, target, "run left APPLYING during apply");
                return null;
            });
            problems.add(sid + ": run left APPLYING; target deleted");
        }
    }

    private boolean stillApplying(String runId) {
        return tx.inTransaction(c -> store.run(c, runId).map(r -> r.state() == RunState.APPLYING).orElse(false));
    }

    // ------------------------------------------------------------------ reconcile

    public Report reconcile(String runId, String actor) {
        Run run = require(runId, EnumSet.of(RunState.APPLIED, RunState.RECONCILE_FAILED));
        List<Item> applied = tx.inTransaction(c -> store.items(c, runId, List.of(ItemStatus.APPLIED, ItemStatus.MATCHED, ItemStatus.MISMATCHED)));
        List<String> problems = new ArrayList<>();
        for (Item it : applied) {
            List<String> diff = compare(it.plan(), writer.state(it.targetInstanceId()));
            ItemStatus st = diff.isEmpty() ? ItemStatus.MATCHED : ItemStatus.MISMATCHED;
            tx.inTransaction(c -> {
                store.finish(c, runId, it.sourceInstanceId(), st, null, diff.isEmpty() ? null : String.join("; ", diff));
                return null;
            });
            if (!diff.isEmpty()) {
                problems.add(it.sourceInstanceId() + " -> " + it.targetInstanceId() + ": " + String.join("; ", diff));
            }
        }
        long open = tx.inTransaction(c -> (long) store.items(c, runId, List.of(ItemStatus.PLANNED, ItemStatus.APPLYING,
                ItemStatus.FAILED)).size());
        if (open > 0) {
            problems.add(open + " item(s) are not applied (PLANNED, APPLYING or FAILED); apply again or roll back");
        }
        RunState next = problems.isEmpty() ? RunState.RECONCILED : RunState.RECONCILE_FAILED;
        transition(run, next, EVENT_RECONCILED, actor, Map.of("outcome", next.name(), "items", applied.size(), "problems", problems.size()));
        return stateReport(runId, problems);
    }

    /** Plan against target state; every difference is reported, nothing is normalised away. */
    static List<String> compare(InstancePlan p, TargetState t) {
        List<String> d = new ArrayList<>();
        if (!t.exists()) {
            d.add("target instance missing");
            return d;
        }
        if (!p.startActivities().equals(t.activeActivities())) {
            d.add("active activities " + t.activeActivities() + " expected " + p.startActivities());
        }
        if (p.suspended() != t.suspended()) {
            d.add("suspended " + t.suspended() + " expected " + p.suspended());
        }
        if (!p.tasks().equals(t.tasks())) {
            d.add("tasks " + t.tasks() + " expected " + p.tasks());
        }
        if (!p.timers().equals(t.timers())) {
            d.add("timers " + t.timers() + " expected " + p.timers());
        }
        if (!p.messageNames().equals(t.messageNames())) {
            d.add("subscriptions " + t.messageNames() + " expected " + p.messageNames());
        }
        if (!p.variables().equals(t.variables())) {
            d.add("variables " + t.variables() + " expected " + p.variables());
        }
        return d;
    }

    // ------------------------------------------------------------------ rollback, cut-over, release

    public Report rollback(String runId, String actor, String reason) {
        Run run = require(runId, EnumSet.of(RunState.APPLYING, RunState.APPLIED, RunState.RECONCILED, RunState.RECONCILE_FAILED));
        if (!freeze.frozen(runId)) {
            return stateReport(runId, List.of("rollback refused: Flowable is no longer frozen, so it is not the state to return to"));
        }
        List<Item> items = tx.inTransaction(c -> store.items(c, runId, List.of(ItemStatus.APPLYING, ItemStatus.APPLIED,
                ItemStatus.MATCHED, ItemStatus.MISMATCHED, ItemStatus.FAILED)));
        Map<String, String> targets = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Item it : items) {
            String target = it.targetInstanceId() != null ? it.targetInstanceId()
                    : writer.findMigrated(run.sourceSystem() + ":" + it.sourceInstanceId()).orElse(null);
            if (target == null) {
                continue;
            }
            TargetState s = writer.state(target);
            if (s.exists() && (s.finishedTasks() > 0 || s.executedJobs() > 0 || !s.activeActivities().equals(it.plan().startActivities()))) {
                problems.add(it.sourceInstanceId() + " -> " + target + " has moved on the target (finished tasks " + s.finishedTasks()
                        + ", executed jobs " + s.executedJobs() + ", active " + s.activeActivities() + ")");
            }
            targets.put(it.sourceInstanceId(), s.exists() ? target : null);
        }
        if (!problems.isEmpty()) {
            problems.add(0, "rollback refused as a whole; resolve the moved instances by hand first");
            return stateReport(runId, problems);
        }
        Run moved = transition(run, RunState.ROLLED_BACK, null, actor, Map.of());
        for (Map.Entry<String, String> e : targets.entrySet()) {
            if (e.getValue() != null) {
                writer.delete(e.getValue(), "migration run " + runId + " rolled back: " + reason);
            }
            tx.inTransaction(c -> {
                store.finish(c, runId, e.getKey(), ItemStatus.ROLLED_BACK, null, null);
                return null;
            });
        }
        tx.inTransaction(c -> {
            store.recount(c, runId);
            audit.append(c, moved.tenantId(), chain(runId), EVENT_ROLLED_BACK, payload(runId, actor, "reason", reason,
                    "deleted", targets.values().stream().filter(Objects::nonNull).count()));
            return null;
        });
        return stateReport(runId, List.of());
    }

    public Report cutOver(String runId, String actor) {
        Run run = require(runId, EnumSet.of(RunState.RECONCILED));
        transition(run, RunState.CUT_OVER, EVENT_CUT_OVER, actor, Map.of());
        return stateReport(runId, List.of("end the migrated instances on Flowable, then release; rejected instances stay on Flowable",
                CUT_OVER_LISTENER_NOTE));
    }

    /**
     * Decision 169: what ending a migrated instance on Flowable runs, from the Flowable source. The per-instance plan
     * findings SOURCE_DELETE_LISTENER, SOURCE_EVENT_LISTENER and SOURCE_MODEL_UNREAD name the model listeners; what the
     * model cannot show stays an explicit gap.
     */
    static final String CUT_OVER_LISTENER_NOTE = "ending an instance on Flowable deletes its open tasks with their delete "
            + "and all task listeners and dispatches engine events (ExecutionEntityManagerImpl.java:1003-1009, "
            + "TaskHelper.java:396-445); a migrated instance carries such listeners only when an operator acknowledged them for its "
            + "definition (decision 231): see the SOURCE_DELETE_LISTENER and SOURCE_EVENT_LISTENER WARN findings of each item. "
            + "Not established from the model: engine-wide event listeners, an EndProcessInstanceInterceptor "
            + "(ExecutionEntityManagerImpl.java:455) and execution end listeners on cancel; check the Flowable engine "
            + "configuration, or end the instances with listeners disabled there, before ending them";

    public Report release(String runId, String actor) {
        Run run = require(runId, EnumSet.of(RunState.DRY_RUN, RunState.FROZEN, RunState.ROLLED_BACK, RunState.CUT_OVER));
        if (run.state() == RunState.CUT_OVER) {
            List<Item> migrated = tx.inTransaction(c -> store.items(c, runId, List.of(ItemStatus.MATCHED)));
            Map<String, Instance> left = inventory(run).reread(migrated.stream().map(Item::sourceInstanceId).toList());
            if (!left.isEmpty()) {
                return stateReport(runId, List.of("release refused: " + left.size() + " migrated instance(s) still exist on "
                        + "Flowable and would run twice: " + new java.util.TreeSet<>(left.keySet())));
            }
        }
        freeze.release(runId);
        if (run.state() == RunState.FROZEN) {
            run = transition(run, RunState.DRY_RUN, null, actor, Map.of());
        }
        String tenant = run.tenantId();
        tx.inTransaction(c -> audit.append(c, tenant, chain(runId), EVENT_RELEASED, payload(runId, actor)));
        return stateReport(runId, List.of());
    }

    // ------------------------------------------------------------------ helpers

    private FlowableInventory inventory(Run run) {
        return new FlowableInventory(source, ZoneId.of(run.sourceZone()));
    }

    private Run require(String runId, Set<RunState> allowed) {
        Run run = tx.inTransaction(c -> store.run(c, runId)).orElseThrow(() -> new IllegalArgumentException("no migration run " + runId));
        if (!allowed.contains(run.state())) {
            throw new IllegalStateException("run " + runId + " is " + run.state() + "; this step needs " + allowed);
        }
        return run;
    }

    /** Compare-and-set transition with its audit event (when named) in one transaction; refused if another actor moved first. */
    private Run transition(Run run, RunState next, String event, String actor, Map<String, ?> extra) {
        return tx.inTransaction(c -> {
            if (!store.transition(c, run.runId(), run.state(), run.version(), next)) {
                throw new IllegalStateException("run " + run.runId() + " was changed concurrently; reload it");
            }
            store.recount(c, run.runId());
            if (event != null) {
                Map<String, Object> p = payload(run.runId(), actor, "from", run.state().name(), "to", next.name());
                p.putAll(extra);
                audit.append(c, run.tenantId(), chain(run.runId()), event, p);
            }
            return store.run(c, run.runId()).orElseThrow();
        });
    }

    private Report stateReport(String runId, List<String> problems) {
        Run r = tx.inTransaction(c -> {
            store.recount(c, runId);
            return store.run(c, runId).orElseThrow();
        });
        return new Report(runId, r.state(), r.planned() + r.rejected(), r.planned(), r.rejected(), Map.of(), Map.of(), Map.of(),
                List.of(), r.applied(), r.failed(), r.matched(), r.mismatched(), List.copyOf(problems));
    }

    private static Report report(String runId, RunState state, Classified c, List<String> problems) {
        Map<String, Long> rej = new TreeMap<>();
        Map<String, Long> warn = new TreeMap<>();
        long supported = 0;
        for (InstancePlan p : c.plans) {
            if (p.status() == InstancePlan.Status.SUPPORTED) {
                supported++;
            }
            for (Finding f : p.findings()) {
                (f.severity() == Severity.REJECT ? rej : warn).merge(f.code(), 1L, Long::sum);
            }
        }
        return new Report(runId, state, c.plans.size(), supported, c.plans.size() - supported, rej, warn,
                c.snapshot.tableCounts(), c.snapshot.outOfScope(), 0, 0, 0, 0, problems);
    }

    static String chain(String runId) {
        return "migration:flowable:" + runId;
    }

    private static Map<String, Object> payload(String runId, String actor, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run", runId);
        m.put("actor", actor);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
