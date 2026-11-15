// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.quota.QuotaLimit;
import org.sangam.core.quota.TenantQuotaExceededException;
import org.sangam.core.quota.TenantQuotas;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.tenant.TenantContext;

/**
 * SG-15 round-trip reduction in {@link TenantQuotaEnginePlugin}: the {@code VARIABLE_BYTES} limit (one
 * {@code SEC_TENANT} override read with {@code sangam.quotas.override-ttl=0s}) is read only by commands that wrote a
 * variable value, and the SL-43 invariants hold unchanged:
 * <ul>
 *   <li>commands that write no variable read no limit at all;</li>
 *   <li>a command that writes variables reads the limit exactly once, however many it writes;</li>
 *   <li>an oversized new value, and an existing value updated to an oversized one, are refused (413 path) and the
 *       whole command rolls back;</li>
 *   <li>every start takes one {@code STARTS_PER_SECOND} permit, with or without variables, and a refused start
 *       creates nothing;</li>
 *   <li>an unlimited tenant stores any size; without a bound tenant nothing is counted or read.</li>
 * </ul>
 * Real Operaton engine on in-memory H2 with the production plugin; the quotas are a counting stub, so the assertions
 * are on the calls the plugin makes, not on timing.
 */
class QuotaVariableCheckReadsH2Test {

    private static final String TENANT = "sg15";
    private static final String KEY = "sg15quota";
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="sg15">
              <process id="sg15quota" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="review"/>
                <userTask id="review"/>
                <sequenceFlow id="f2" sourceRef="review" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    /** Counts every call per limit; limits are configurable, sizes refused like {@code JdbcTenantQuotas}. */
    static final class CountingQuotas implements TenantQuotas {
        final Map<QuotaLimit, AtomicInteger> limitCalls = new EnumMap<>(QuotaLimit.class);
        final Map<QuotaLimit, AtomicInteger> acquireCalls = new EnumMap<>(QuotaLimit.class);
        final AtomicInteger checkSizeCalls = new AtomicInteger();
        volatile long variableBytes = 1024;
        volatile long startsPerSecond = QuotaLimit.UNLIMITED;

        CountingQuotas() {
            for (QuotaLimit l : QuotaLimit.values()) {
                limitCalls.put(l, new AtomicInteger());
                acquireCalls.put(l, new AtomicInteger());
            }
        }

        @Override
        public long limit(String tenantId, QuotaLimit limit) {
            limitCalls.get(limit).incrementAndGet();
            return configured(limit);
        }

        /** The configured limit, not counted: the counters are the calls the plugin makes, not this stub's own. */
        private long configured(QuotaLimit limit) {
            return switch (limit) {
                case VARIABLE_BYTES -> variableBytes;
                case STARTS_PER_SECOND -> startsPerSecond;
                default -> QuotaLimit.UNLIMITED;
            };
        }

        @Override
        public void acquire(String tenantId, QuotaLimit limit, long permits) {
            acquireCalls.get(limit).incrementAndGet();
            long max = configured(limit);
            if (max >= 0 && permits > max) {
                throw new TenantQuotaExceededException(tenantId, limit, max, permits, 1);
            }
        }

        @Override
        public void checkSize(String tenantId, QuotaLimit limit, long amount) {
            checkSizeCalls.incrementAndGet();
            long max = configured(limit);
            if (!TenantQuotas.within(max, amount)) {
                throw new TenantQuotaExceededException(tenantId, limit, max, amount, null);
            }
        }

        int variableLimitReads() {
            return limitCalls.get(QuotaLimit.VARIABLE_BYTES).get();
        }

        void reset() {
            limitCalls.values().forEach(a -> a.set(0));
            acquireCalls.values().forEach(a -> a.set(0));
            checkSizeCalls.set(0);
        }
    }

    static final CountingQuotas QUOTAS = new CountingQuotas();
    static ProcessEngine engine;

    @BeforeAll
    static void engine() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName("sg15-quota-variable-reads");
        cfg.setJdbcUrl("jdbc:h2:mem:sg15-quota-variable-reads;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
        cfg.setJobExecutorActivate(false);
        cfg.getProcessEnginePlugins().add(new TenantQuotaEnginePlugin(() -> QUOTAS));
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().addString("sg15quota.bpmn", BPMN).deploy();
    }

    @AfterAll
    static void close() {
        if (engine != null) {
            engine.close();
        }
    }

    @BeforeEach
    void defaults() {
        QUOTAS.variableBytes = 1024;
        QUOTAS.startsPerSecond = QuotaLimit.UNLIMITED;
        QUOTAS.reset();
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    private static <T> T asTenant(Supplier<T> work) {
        return TenantContext.runAs(SecurityIdentity.system(TENANT, null), work);
    }

    private static String startWith(Map<String, Object> vars) {
        return asTenant(() -> engine.getRuntimeService().startProcessInstanceByKey(KEY, vars).getId());
    }

    private static Task taskOf(String pi) {
        return asTenant(() -> engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult());
    }

    @Test
    void commandsThatWriteNoVariableReadNoVariableLimit() {
        String pi = startWith(Map.of("amountMinor", 1));
        Task t = taskOf(pi);
        QUOTAS.reset();

        asTenant(() -> engine.getTaskService().createTaskQuery().processInstanceId(pi).list());
        asTenant(() -> engine.getTaskService().createTaskQuery().processInstanceId(pi).count());
        asTenant(() -> engine.getTaskService().getVariables(t.getId()));
        asTenant(() -> engine.getRuntimeService().getVariable(pi, "amountMinor"));
        asTenant(() -> engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi).list());
        asTenant(() -> engine.getTaskService().getIdentityLinksForTask(t.getId()));
        asTenant(() -> {
            engine.getTaskService().claim(t.getId(), "officer");   // a write, but of no variable
            return null;
        });

        assertThat(QUOTAS.variableLimitReads()).as("read-only and variable-free commands read no limit").isZero();
        assertThat(QUOTAS.checkSizeCalls.get()).isZero();
        assertThat(asTenant(() -> engine.getTaskService().createTaskQuery().taskId(t.getId()).singleResult()
                .getAssignee())).isEqualTo("officer");
    }

    @Test
    void aCommandWritingVariablesReadsTheLimitOnceAndStoresThem() {
        String pi = startWith(Map.of("amountMinor", 1));
        QUOTAS.reset();

        asTenant(() -> {
            engine.getRuntimeService().setVariables(pi, Map.of("a", "x", "b", "y", "c", 3));
            return null;
        });

        assertThat(QUOTAS.variableLimitReads()).as("one read per command, not per value").isEqualTo(1);
        assertThat(QUOTAS.checkSizeCalls.get()).as("values within the limit are not refused").isZero();
        assertThat(asTenant(() -> engine.getRuntimeService().getVariables(pi)))
                .containsEntry("a", "x").containsEntry("b", "y").containsEntry("c", 3);
    }

    @Test
    void anOversizedNewValueIsRefusedAndTheCommandRollsBack() {
        String pi = startWith(Map.of("amountMinor", 1));
        QUOTAS.variableBytes = 16;
        QUOTAS.reset();

        assertThatThrownBy(() -> asTenant(() -> {
            engine.getRuntimeService().setVariables(pi, Map.of("small", "ok", "big", "x".repeat(100)));
            return null;
        })).isInstanceOf(TenantQuotaExceededException.class)
                .satisfies(e -> assertThat(((TenantQuotaExceededException) e).limit()).isEqualTo(QuotaLimit.VARIABLE_BYTES));

        assertThat(QUOTAS.checkSizeCalls.get()).isEqualTo(1);
        Map<String, Object> stored = asTenant(() -> engine.getRuntimeService().getVariables(pi));
        assertThat(stored).as("nothing of the refused command is stored").doesNotContainKeys("small", "big");
    }

    @Test
    void anExistingValueUpdatedToAnOversizedOneIsRefusedAndKeepsItsOldValue() {
        String pi = startWith(Map.of("note", "short"));
        QUOTAS.variableBytes = 16;
        QUOTAS.reset();

        assertThatThrownBy(() -> asTenant(() -> {
            engine.getRuntimeService().setVariable(pi, "note", "y".repeat(100));
            return null;
        })).isInstanceOf(TenantQuotaExceededException.class);

        assertThat(QUOTAS.variableLimitReads()).as("the dirty persistent value is a written value").isEqualTo(1);
        assertThat(asTenant(() -> engine.getRuntimeService().getVariable(pi, "note"))).isEqualTo("short");
    }

    @Test
    void everyStartTakesAStartPermitWithOrWithoutVariables() {
        startWith(Map.of());
        assertThat(QUOTAS.acquireCalls.get(QuotaLimit.STARTS_PER_SECOND).get()).isEqualTo(1);
        assertThat(QUOTAS.variableLimitReads()).as("a start without variables writes none").isZero();

        QUOTAS.reset();
        startWith(Map.of("amountMinor", 1));
        assertThat(QUOTAS.acquireCalls.get(QuotaLimit.STARTS_PER_SECOND).get()).isEqualTo(1);
        assertThat(QUOTAS.variableLimitReads()).as("a start with variables checks them once").isEqualTo(1);
    }

    @Test
    void aRefusedStartCreatesNothing() {
        QUOTAS.startsPerSecond = 0;
        long before = engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(KEY).count();

        assertThatThrownBy(() -> startWith(Map.of("amountMinor", 1))).isInstanceOf(TenantQuotaExceededException.class)
                .satisfies(e -> assertThat(((TenantQuotaExceededException) e).limit())
                        .isEqualTo(QuotaLimit.STARTS_PER_SECOND));

        assertThat(engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(KEY).count())
                .isEqualTo(before);
    }

    @Test
    void anUnlimitedTenantStoresAnySizeWithoutASizeCheck() {
        String pi = startWith(Map.of("amountMinor", 1));
        QUOTAS.variableBytes = QuotaLimit.UNLIMITED;
        QUOTAS.reset();

        asTenant(() -> {
            engine.getRuntimeService().setVariable(pi, "big", "z".repeat(3_000));
            return null;
        });

        assertThat(QUOTAS.variableLimitReads()).isEqualTo(1);
        assertThat(QUOTAS.checkSizeCalls.get()).isZero();
        assertThat(asTenant(() -> engine.getRuntimeService().getVariable(pi, "big"))).isEqualTo("z".repeat(3_000));
    }

    @Test
    void withoutABoundTenantNothingIsCountedOrRead() {
        ProcessInstance p = engine.getRuntimeService().startProcessInstanceByKey(KEY, Map.of("amountMinor", 1));
        engine.getRuntimeService().setVariable(p.getId(), "x", "y");

        assertThat(QUOTAS.acquireCalls.get(QuotaLimit.STARTS_PER_SECOND).get()).isZero();
        assertThat(QUOTAS.variableLimitReads()).isZero();
        assertThat(QUOTAS.checkSizeCalls.get()).isZero();
    }
}
