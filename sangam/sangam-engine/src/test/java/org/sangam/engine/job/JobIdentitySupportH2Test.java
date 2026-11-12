// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.engine.JobIdentityBinder;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * C3-05 (M-097 debt): the job identity is passed explicitly. A job runs as its tenant's system identity, which the
 * installed binder receives and which code inside the job's engine command reads through
 * {@link JobIdentitySupport#current()}; outside a job command there is none, and the command's previous identity is
 * restored afterwards. sangam-core keeps no thread-bound copy (ArchitectureGuardTest ratchet).
 */
class JobIdentitySupportH2Test {

    static final List<SecurityIdentity> SEEN_IN_JOB = new CopyOnWriteArrayList<>();

    /** Records the command-scoped job identity while the job runs. */
    public static final class Capture implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            SEEN_IN_JOB.add(JobIdentitySupport.current().orElse(null));
        }
    }

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="c305" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <serviceTask id="work" operaton:asyncBefore="true"
                             operaton:class="org.sangam.engine.job.JobIdentitySupportH2Test$Capture"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    @Test
    void aJobRunsAsItsTenantSystemIdentityPassedExplicitly() throws Exception {
        SEEN_IN_JOB.clear();
        AtomicReference<SecurityIdentity> boundByBinder = new AtomicReference<>();
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setDeployAllowedClasses(Map.of("*", Set.of(Capture.class.getName())));
        s.setJobIdentityBinder(new JobIdentityBinder() {
            @Override
            public <T> T runAs(SecurityIdentity identity, java.util.function.Supplier<T> work) {
                boundByBinder.set(identity);
                return work.get();
            }
        });
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:c305" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy("t1", "c305-" + UUID.randomUUID(), Map.of("c305.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "c305", "C305-" + UUID.randomUUID(), Map.of());
            assertTrue(JobIdentitySupport.current().isEmpty(), "no job identity outside a job command");
            Job j = a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).singleResult();
            a.processEngine().getManagementService().executeJob(j.getId());

            assertEquals(1, SEEN_IN_JOB.size());
            SecurityIdentity inJob = SEEN_IN_JOB.get(0);
            assertNotNull(inJob, "the job's command carries its identity");
            assertEquals("t1", inJob.tenantId(), "VI.4 rule 7: the tenant of the job");
            assertSame(inJob, boundByBinder.get(), "the binder received the same identity, explicitly");
            assertTrue(JobIdentitySupport.current().isEmpty(), "nothing is left behind after the job");
        }
    }

    @Test
    void identityResolutionOrderIsUnchanged() {
        JobIdentitySupport support = new JobIdentitySupport(null, "dflt");
        assertEquals("tx", support.identityFor(null, null, "tx").tenantId(), "the tenant id passed by the engine first");
        assertEquals("dflt", support.identityFor(null, null, null).tenantId(), "the engine default tenant last");
    }
}
