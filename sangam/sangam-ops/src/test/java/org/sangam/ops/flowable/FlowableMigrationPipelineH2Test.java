// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.flowable.FlowableMigrationPipeline.Report;
import org.sangam.ops.flowable.FlowableMigrationPipeline.Request;
import org.sangam.ops.flowable.FlowableMigrationStore.ItemStatus;
import org.sangam.ops.flowable.FlowableMigrationStore.RunState;
import org.sangam.ops.flowable.InstancePlan.VariableValue;
import org.sangam.ops.flowable.OperatonStateWriter.TargetState;

import java.sql.Connection;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-135 pipeline state machine on H2 (product tables) with the synthetic corpus and an in-memory target. Covers the
 * dry run writing nothing, freeze/drain, idempotent apply, duplicate-effect protection, reconciliation counts, the
 * enforced rollback conditions and cut-over. Written, NOT_RUN.
 */
class FlowableMigrationPipelineH2Test {

    static final Dialect D = Dialect.POSTGRESQL;

    JdbcDataSource ds;
    Transactions tx;
    MutableRows source;
    FakeWriter writer;
    FakeFreeze freeze;
    FlowableMigrationPipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            FlowableMigrationSchema.create(c, D);
        }
        tx = Transactions.of(ds);
        source = new MutableRows(FlowableCorpus.supported());
        writer = new FakeWriter();
        freeze = new FakeFreeze();
        pipeline = new FlowableMigrationPipeline(source, writer, FlowableCorpus.model(), freeze, tx, D, new AuditChain(D),
                Clock.fixed(FlowableCorpus.NOW, ZoneOffset.UTC), 3);
    }

    static Request request(String runId) {
        return new Request(runId, FlowableCorpus.TENANT, "flowable-prod", FlowableCorpus.TENANT, FlowableCorpus.ZONE,
                FlowableCorpus.MAPPINGS);
    }

    long count(String sql, Object... params) {
        return tx.inTransaction(c -> Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L));
    }

    List<String> events(String runId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE chain_id=? ORDER BY seq",
                rs -> rs.getString(1), FlowableMigrationPipeline.chain(runId)));
    }

    String itemStatus(String runId, String sourceId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT status FROM OPS_FMIG_ITEM WHERE run_id=? AND source_instance_id=?",
                rs -> rs.getString(1), runId, sourceId).orElseThrow());
    }

    Report toApplied(String runId) {
        pipeline.plan(request(runId), "op");
        assertEquals(RunState.FROZEN, pipeline.freeze(runId, "op").state());
        return pipeline.apply(runId, "op");
    }

    // ------------------------------------------------------------------ dry run and plan

    @Test
    void dryRunChangesNothingAnywhere() {
        Report r = pipeline.dryRun(request("run-1"));
        assertNull(r.state());
        assertEquals(8, r.instances());
        assertEquals(8, r.supported());
        assertEquals(0, r.rejected());
        assertEquals(Map.of(StateClassifier.DOUBLE_VARIABLE, 1L, StateClassifier.OVERDUE_TIMER, 1L, StateClassifier.UUID_AS_STRING, 1L),
                r.warningsByCode());
        assertEquals(2, r.outOfScope().size());
        assertEquals(0, count("SELECT COUNT(*) FROM OPS_FMIG_RUN"));
        assertEquals(0, count("SELECT COUNT(*) FROM OPS_FMIG_ITEM"));
        assertEquals(0, count("SELECT COUNT(*) FROM AUDIT_EVENT"));
        assertEquals(0, writer.applyCalls);
        assertTrue(freeze.frozen.isEmpty());
    }

    @Test
    void planRecordsTheRunItemsAndOneAuditEvent() {
        Report r = pipeline.plan(request("run-1"), "op");
        assertEquals(RunState.DRY_RUN, r.state());
        assertEquals(8, count("SELECT COUNT(*) FROM OPS_FMIG_ITEM WHERE run_id='run-1' AND status='PLANNED'"));
        assertEquals(List.of(FlowableMigrationPipeline.EVENT_PLANNED), events("run-1"));
        assertThrows(IllegalStateException.class, () -> pipeline.plan(request("run-1"), "op"), "a run id is used once");
        assertEquals(0, writer.applyCalls);
    }

    /** Owner decision 231: a definition whose model listeners fire on end migrates only after an acknowledgement. */
    @Test
    void acknowledgedListenersLetTheDefinitionMigrateWithAnAuditRecord() {
        assertThrows(IllegalArgumentException.class, () -> pipeline.acknowledgeListeners(FlowableCorpus.TENANT, "flowable-prod",
                FlowableCorpus.TENANT, "order:1:100", "op", "nothing fires"), "create and assignment listeners need no acknowledgement");
        assertThrows(IllegalArgumentException.class, () -> pipeline.acknowledgeListeners(FlowableCorpus.TENANT, "flowable-prod",
                FlowableCorpus.TENANT, "order:1:200", "op", "x"), "a definition of another source tenant");
        assertThrows(IllegalArgumentException.class, () -> pipeline.acknowledgeListeners(FlowableCorpus.TENANT, "flowable-prod",
                FlowableCorpus.TENANT, "order:1:100", "op", " "), "a reason is required");

        source.resources.put("dep-100/order.bpmn20.xml", FlowableSourceEvidenceTest.withListeners("<flowable:eventListener class=\"x.E\"/>")
                .resource("dep-100", "order.bpmn20.xml")); // a process event listener reaches every instance
        Report before = pipeline.plan(request("run-a"), "op");
        assertEquals(0, before.supported(), "every instance is rejected while the listeners are not acknowledged");
        assertTrue(before.rejectionsByCode().containsKey(StateClassifier.SOURCE_DELETE_LISTENER), before.rejectionsByCode().toString());

        FlowableMigrationStore.Acknowledgement a = pipeline.acknowledgeListeners(FlowableCorpus.TENANT, "flowable-prod",
                FlowableCorpus.TENANT, "order:1:100", "op", "ended on Flowable with task listeners disabled");
        assertEquals("order", a.definitionKey());
        String chain = FlowableMigrationPipeline.ackChain("flowable-prod", "order:1:100");
        assertTrue(chain.length() <= 64, chain);
        assertEquals(1, count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type=?", chain,
                FlowableMigrationPipeline.EVENT_LISTENERS_ACKNOWLEDGED));
        pipeline.acknowledgeListeners(FlowableCorpus.TENANT, "flowable-prod", FlowableCorpus.TENANT, "order:1:100", "op2", "again");
        assertEquals(1, count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=?", chain), "the same listeners: no second record");
        assertEquals(1, count("SELECT COUNT(*) FROM OPS_FMIG_ACK"));

        Report after = pipeline.plan(request("run-b"), "op");
        assertEquals(8, after.supported(), after.rejectionsByCode().toString());
        assertTrue(after.warningsByCode().containsKey(StateClassifier.SOURCE_DELETE_LISTENER), after.warningsByCode().toString());
        assertEquals(0, count("SELECT COUNT(*) FROM OPS_FMIG_ITEM WHERE run_id='run-a' AND status='PLANNED'"),
                "a run planned before the acknowledgement keeps its classification");

        assertEquals(8, pipeline.dryRun(new Request("run-c", FlowableCorpus.TENANT, "flowable-other", FlowableCorpus.TENANT,
                FlowableCorpus.ZONE, FlowableCorpus.MAPPINGS)).rejected(), "an acknowledgement belongs to one source system");

        source.resources.put("dep-100/order.bpmn20.xml", FlowableSourceEvidenceTest.withListeners("<flowable:eventListener events=\"TASK_CREATED\" class=\"x.E\"/>")
                .resource("dep-100", "order.bpmn20.xml"));
        assertEquals(8, pipeline.dryRun(request("run-d")).rejected(), "other listeners than the acknowledged ones reject again");
    }

    @Test
    void rejectedCorpusIsPlannedButNothingIsApplied() {
        pipeline = new FlowableMigrationPipeline(FlowableCorpus.rejected(), writer, FlowableCorpus.model(), freeze, tx, D,
                new AuditChain(D), Clock.fixed(FlowableCorpus.NOW, ZoneOffset.UTC), 3);
        Report r = pipeline.plan(request("run-r"), "op");
        assertEquals(FlowableCorpus.REJECTED.size(), r.rejected());
        assertEquals(0, r.supported());
        pipeline.freeze("run-r", "op");
        Report a = pipeline.apply("run-r", "op");
        assertEquals(RunState.APPLIED, a.state());
        assertEquals(0, a.applied());
        assertEquals(0, writer.applyCalls);
    }

    @Test
    void requestRefusesAnUnsafeSourceSystemName() {
        assertThrows(IllegalArgumentException.class, () -> new Request("r", "acme", "a|b", null, FlowableCorpus.ZONE, List.of()));
    }

    // ------------------------------------------------------------------ freeze

    @Test
    void freezeIsRefusedWhenTheSourceChangedAfterTheDryRun() {
        pipeline.plan(request("run-1"), "op");
        source.set(FlowableTables.TASK, "t1", FlowableTables.ASSIGNEE, "mallory");
        Report r = pipeline.freeze("run-1", "op");
        assertEquals(RunState.DRY_RUN, r.state());
        assertTrue(r.problems().stream().anyMatch(p -> p.startsWith("s1 changed")), r.problems().toString());
    }

    @Test
    void freezeIsRefusedUntilTheWorkersAreDrained() {
        pipeline.plan(request("run-1"), "op");
        freeze.drained = false;
        Report r = pipeline.freeze("run-1", "op");
        assertEquals(RunState.DRY_RUN, r.state());
        assertFalse(r.problems().isEmpty());
        freeze.drained = true;
        assertEquals(RunState.FROZEN, pipeline.freeze("run-1", "op").state());
    }

    @Test
    void applyNeedsTheFreeze() {
        pipeline.plan(request("run-1"), "op");
        assertThrows(IllegalStateException.class, () -> pipeline.apply("run-1", "op"), "DRY_RUN cannot apply");
        pipeline.freeze("run-1", "op");
        freeze.frozen.clear();
        Report r = pipeline.apply("run-1", "op");
        assertEquals(RunState.FROZEN, r.state());
        assertEquals(0, writer.applyCalls);
    }

    // ------------------------------------------------------------------ apply

    @Test
    void applyMigratesEverySupportedInstanceOnceAndIsIdempotent() {
        Report r = toApplied("run-1");
        assertEquals(RunState.APPLIED, r.state());
        assertEquals(8, r.applied());
        assertEquals(0, r.failed());
        assertEquals(8, writer.applyCalls);
        Report again = pipeline.apply("run-1", "op");
        assertEquals(RunState.APPLIED, again.state());
        assertEquals(8, writer.applyCalls, "a second apply starts nothing");
        assertEquals(8, events("run-1").stream().filter(FlowableMigrationPipeline.EVENT_INSTANCE_MIGRATED::equals).count());
        assertEquals("flowable-prod:s1", writer.provenanceOf.get(writer.targetOf("s1")));
    }

    @Test
    void aLostAnswerIsAdoptedNeverStartedTwice() {
        writer.loseAnswerFor.add("s2");
        Report r = toApplied("run-1");
        assertEquals(1, r.failed());
        assertEquals("FAILED", itemStatus("run-1", "s2"));
        Report again = pipeline.apply("run-1", "op");
        assertEquals(0, again.failed());
        assertEquals("APPLIED", itemStatus("run-1", "s2"));
        assertEquals(1, writer.created.stream().filter(p -> p.sourceInstanceId().equals("s2")).count());
        assertEquals(2, count("SELECT attempts FROM OPS_FMIG_ITEM WHERE run_id='run-1' AND source_instance_id='s2'"));
    }

    @Test
    void anInstanceThatChangesWhileFrozenIsNotApplied() {
        pipeline.plan(request("run-1"), "op");
        pipeline.freeze("run-1", "op");
        source.set(FlowableTables.TASK, "t4", FlowableTables.ASSIGNEE, "mallory");
        Report r = pipeline.apply("run-1", "op");
        assertEquals(1, r.failed());
        assertEquals("FAILED", itemStatus("run-1", "s4"));
        assertTrue(r.problems().stream().anyMatch(p -> p.startsWith("s4: the source instance changed")), r.problems().toString());
        assertNull(writer.targetOf("s4"));
    }

    @Test
    void aSourceInstanceIsNeverMigratedByTwoRuns() {
        toApplied("run-1");
        pipeline.plan(request("run-2"), "op");
        pipeline.freeze("run-2", "op");
        Report r = pipeline.apply("run-2", "op");
        assertEquals(8, r.failed());
        assertEquals(8, writer.applyCalls);
        assertTrue(r.problems().stream().allMatch(p -> p.contains("already migrated by another run")), r.problems().toString());
    }

    @Test
    void engineFailureLeavesTheItemFailedAndRetryable() {
        writer.failFor.add("s3");
        Report r = toApplied("run-1");
        assertEquals(1, r.failed());
        assertNull(writer.targetOf("s3"));
        writer.failFor.clear();
        assertEquals(0, pipeline.apply("run-1", "op").failed());
        assertNotNull(writer.targetOf("s3"));
    }

    // ------------------------------------------------------------------ reconcile

    @Test
    void reconcileCountsMatchesAndMismatches() {
        toApplied("run-1");
        Report ok = pipeline.reconcile("run-1", "op");
        assertEquals(RunState.RECONCILED, ok.state());
        assertEquals(8, ok.matched());
        assertEquals(0, ok.mismatched());
    }

    @Test
    void reconcileReportsEveryDifference() {
        toApplied("run-1");
        writer.tamper(writer.targetOf("s1"));
        Report r = pipeline.reconcile("run-1", "op");
        assertEquals(RunState.RECONCILE_FAILED, r.state());
        assertEquals(7, r.matched());
        assertEquals(1, r.mismatched());
        assertEquals("MISMATCHED", itemStatus("run-1", "s1"));
        assertTrue(r.problems().get(0).contains("variables"), r.problems().toString());
        assertThrows(IllegalStateException.class, () -> pipeline.cutOver("run-1", "op"), "no cut-over before RECONCILED");
    }

    @Test
    void reconcileFailsWhileItemsAreUnapplied() {
        writer.failFor.add("s5");
        toApplied("run-1");
        Report r = pipeline.reconcile("run-1", "op");
        assertEquals(RunState.RECONCILE_FAILED, r.state());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("not applied")), r.problems().toString());
    }

    @Test
    void compareIsExact() {
        InstancePlan p = new InstancePlan("x", "d", "order", 1, "acme", "t", null, false, List.of("review"), List.of(), List.of(),
                List.of(), List.of(new VariableValue("a", "long", "1")), InstancePlan.Status.SUPPORTED, List.of(), "f");
        assertEquals(List.of(), FlowableMigrationPipeline.compare(p, new TargetState(true, false, List.of("review"), List.of(),
                List.of(), List.of(), List.of(new VariableValue("a", "long", "1")), 0, 0)));
        assertEquals(1, FlowableMigrationPipeline.compare(p, new TargetState(true, false, List.of("review"), List.of(),
                List.of(), List.of(), List.of(new VariableValue("a", "integer", "1")), 0, 0)).size(), "the type is part of the value");
        assertEquals(List.of("target instance missing"), FlowableMigrationPipeline.compare(p, TargetState.absent()));
    }

    // ------------------------------------------------------------------ rollback, cut-over, release

    @Test
    void rollbackDeletesUntouchedTargetsAndFreesTheSourceForANewRun() {
        toApplied("run-1");
        pipeline.reconcile("run-1", "op");
        Report r = pipeline.rollback("run-1", "op", "rehearsal");
        assertEquals(RunState.ROLLED_BACK, r.state(), r.problems().toString());
        assertTrue(writer.instances.isEmpty());
        assertEquals(8, count("SELECT COUNT(*) FROM OPS_FMIG_ITEM WHERE run_id='run-1' AND status='ROLLED_BACK'"));
        assertTrue(events("run-1").contains(FlowableMigrationPipeline.EVENT_ROLLED_BACK));
        pipeline.release("run-1", "op");
        assertTrue(freeze.frozen.isEmpty());
        Report again = toApplied("run-2");
        assertEquals(8, again.applied(), "a rolled-back source instance may be migrated again");
    }

    @Test
    void rollbackIsRefusedAsAWholeWhenAnyTargetMoved() {
        toApplied("run-1");
        writer.finishedTasks.put(writer.targetOf("s1"), 1L);
        Report r = pipeline.rollback("run-1", "op", "try");
        assertEquals(RunState.APPLIED, r.state());
        assertTrue(r.problems().get(0).startsWith("rollback refused as a whole"), r.problems().toString());
        assertEquals(8, writer.instances.size(), "nothing deleted");

        writer.finishedTasks.clear();
        writer.executedJobs.put(writer.targetOf("s5"), 1L);
        assertEquals(RunState.APPLIED, pipeline.rollback("run-1", "op", "try").state());
        writer.executedJobs.clear();
        writer.moveActivity(writer.targetOf("s2"), "cooling");
        assertEquals(RunState.APPLIED, pipeline.rollback("run-1", "op", "try").state());
        assertEquals(8, writer.instances.size());
    }

    @Test
    void rollbackIsRefusedOnceFlowableIsNoLongerFrozen() {
        toApplied("run-1");
        freeze.frozen.clear();
        Report r = pipeline.rollback("run-1", "op", "try");
        assertEquals(RunState.APPLIED, r.state());
        assertTrue(r.problems().get(0).startsWith("rollback refused"), r.problems().toString());
        assertEquals(8, writer.instances.size());
    }

    @Test
    void cutOverEndsRollbackAndReleaseWaitsForFlowableToBeEmptied() {
        toApplied("run-1");
        pipeline.reconcile("run-1", "op");
        assertEquals(RunState.CUT_OVER, pipeline.cutOver("run-1", "op").state());
        assertThrows(IllegalStateException.class, () -> pipeline.rollback("run-1", "op", "late"));
        Report refused = pipeline.release("run-1", "op");
        assertTrue(refused.problems().get(0).startsWith("release refused: 8 migrated"), refused.problems().toString());
        assertTrue(freeze.frozen.contains("run-1"));
        for (String id : FlowableCorpus.SUPPORTED_IDS) {
            source.removeInstance(id);
        }
        Report released = pipeline.release("run-1", "op");
        assertTrue(released.problems().isEmpty(), released.problems().toString());
        assertFalse(freeze.frozen.contains("run-1"));
        assertEquals(List.of(FlowableMigrationPipeline.EVENT_PLANNED, FlowableMigrationPipeline.EVENT_FROZEN),
                events("run-1").subList(0, 2));
        List<String> tail = events("run-1").subList(10, events("run-1").size());
        assertEquals(List.of(FlowableMigrationPipeline.EVENT_RECONCILED, FlowableMigrationPipeline.EVENT_CUT_OVER,
                FlowableMigrationPipeline.EVENT_RELEASED), tail);
    }

    @Test
    void releaseFromFrozenAbortsToDryRun() {
        pipeline.plan(request("run-1"), "op");
        pipeline.freeze("run-1", "op");
        Report r = pipeline.release("run-1", "op");
        assertEquals(RunState.DRY_RUN, r.state());
        assertFalse(freeze.frozen.contains("run-1"));
    }

    @Test
    void runTransitionsAreCompareAndSet() {
        pipeline.plan(request("run-1"), "op");
        FlowableMigrationStore store = new FlowableMigrationStore(D);
        boolean first = tx.inTransaction(c -> store.transition(c, "run-1", RunState.DRY_RUN, 1, RunState.FROZEN));
        boolean stale = tx.inTransaction(c -> store.transition(c, "run-1", RunState.DRY_RUN, 1, RunState.FROZEN));
        assertTrue(first);
        assertFalse(stale, "stale version");
        assertEquals(RunState.FROZEN, tx.inTransaction(c -> store.run(c, "run-1")).orElseThrow().state());
        assertEquals(List.of(), tx.inTransaction(c -> store.items(c, "run-1", List.of(ItemStatus.APPLIED))));
    }

    // ------------------------------------------------------------------ fakes

    /** Copy of a corpus whose rows a test can change (a source that moved). */
    static final class MutableRows implements FlowableRows {
        final Map<String, List<Map<String, Object>>> tables = new HashMap<>();
        private final FlowableRows from;

        MutableRows(FlowableRows from) {
            this.from = from;
            for (String t : FlowableTables.ALL) {
                List<Map<String, Object>> rows = new ArrayList<>();
                from.all(t).forEach(r -> rows.add(new LinkedHashMap<>(r)));
                tables.put(t, rows);
            }
        }

        void set(String table, String id, String column, Object value) {
            tables.get(table).stream().filter(r -> id.equals(r.get(FlowableTables.ID))).forEach(r -> r.put(column, value));
        }

        void removeInstance(String id) {
            for (String t : FlowableTables.ALL) {
                tables.get(t).removeIf(r -> id.equals(r.get(FlowableTables.PROC_INST_ID)) || id.equals(r.get(FlowableTables.JOB_PROCESS_INSTANCE_ID)));
            }
        }

        @Override
        public List<Map<String, Object>> where(String table, String column, Collection<String> values) {
            Set<String> wanted = new HashSet<>(values);
            return all(table).stream().filter(r -> r.get(column) != null && wanted.contains(String.valueOf(r.get(column)))).toList();
        }

        @Override
        public List<Map<String, Object>> all(String table) {
            return List.copyOf(tables.getOrDefault(table, List.of()));
        }

        @Override
        public long count(String table) {
            return all(table).size();
        }

        /** Resources a test replaced (a source deployed with other listeners), by {@code <deployment id>/<name>}. */
        final Map<String, byte[]> resources = new HashMap<>();

        /** Deployed resources come from the original corpus unless a test replaced them. */
        @Override
        public byte[] resource(String deploymentId, String name) {
            byte[] b = resources.get(deploymentId + "/" + name);
            return b != null ? b.clone() : from.resource(deploymentId, name);
        }
    }

    static final class FakeFreeze implements WorkerFreeze {
        final Set<String> frozen = new HashSet<>();
        boolean drained = true;

        @Override
        public void freeze(String runId) {
            frozen.add(runId);
        }

        @Override
        public boolean frozen(String runId) {
            return frozen.contains(runId);
        }

        @Override
        public boolean drained(String runId) {
            return drained;
        }

        @Override
        public void release(String runId) {
            frozen.remove(runId);
        }
    }

    /** An in-memory target that reports exactly what it was given, unless a test moves or tampers with it. */
    static final class FakeWriter implements OperatonStateWriter {
        final Map<String, InstancePlan> instances = new LinkedHashMap<>();
        final Map<String, String> provenanceOf = new HashMap<>();
        final Map<String, List<String>> active = new HashMap<>();
        final Map<String, List<VariableValue>> variables = new HashMap<>();
        final Map<String, Long> finishedTasks = new HashMap<>();
        final Map<String, Long> executedJobs = new HashMap<>();
        final List<InstancePlan> created = new ArrayList<>();
        final Set<String> loseAnswerFor = new HashSet<>();
        final Set<String> failFor = new HashSet<>();
        int applyCalls;
        int seq;

        String targetOf(String sourceId) {
            return provenanceOf.entrySet().stream().filter(e -> e.getValue().endsWith(":" + sourceId)).map(Map.Entry::getKey)
                    .filter(instances::containsKey).findFirst().orElse(null);
        }

        void tamper(String target) {
            variables.put(target, List.of(new VariableValue("amount", "long", "999")));
        }

        void moveActivity(String target, String activity) {
            active.put(target, List.of(activity));
        }

        @Override
        public Optional<String> findMigrated(String provenance) {
            return provenanceOf.entrySet().stream().filter(e -> e.getValue().equals(provenance) && instances.containsKey(e.getKey()))
                    .map(Map.Entry::getKey).findFirst();
        }

        @Override
        public String apply(InstancePlan plan, String provenance) {
            applyCalls++;
            if (failFor.contains(plan.sourceInstanceId())) {
                throw new IllegalStateException("engine refused " + plan.sourceInstanceId());
            }
            String id = "op-" + (++seq);
            instances.put(id, plan);
            provenanceOf.put(id, provenance);
            created.add(plan);
            if (loseAnswerFor.remove(plan.sourceInstanceId())) {
                throw new IllegalStateException("connection lost after commit");
            }
            return id;
        }

        @Override
        public TargetState state(String id) {
            InstancePlan p = instances.get(id);
            if (p == null) {
                return TargetState.absent();
            }
            return new TargetState(true, p.suspended(), active.getOrDefault(id, p.startActivities()), p.tasks(), p.timers(),
                    p.messageNames(), variables.getOrDefault(id, p.variables()), finishedTasks.getOrDefault(id, 0L),
                    executedJobs.getOrDefault(id, 0L));
        }

        @Override
        public void delete(String id, String reason) {
            instances.remove(id);
        }
    }
}
