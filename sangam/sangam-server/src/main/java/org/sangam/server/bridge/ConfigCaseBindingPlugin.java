// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * sangam-config "Case binding" (README integration notes): at the start of every root process instance, the tenant's
 * resolved profile binding is stored as the variable {@value #VARIABLE} and in {@code CFG_CASE_BINDING}, so a case keeps
 * the configuration version it started under (R43, K10). The row is written on the engine command's connection, so it
 * commits or rolls back with the instance. The pack's start listener runs first and sets the same binding as the
 * variable (it asks the same profile through the config connector); the row is then stored for that binding. A tenant
 * without a profile is left unchanged, and so is an instance that carries any other binding: its variable is kept and
 * nothing is stored.
 */
public final class ConfigCaseBindingPlugin extends AbstractProcessEnginePlugin {

    public static final String VARIABLE = "profileVersion";
    private static final Logger LOG = LoggerFactory.getLogger(ConfigCaseBindingPlugin.class);

    private final ObjectProvider<ProfileResolver> profiles;
    private final Dialect dialect;

    private final org.sangam.core.time.BusinessClock businessClock;

    public ConfigCaseBindingPlugin(ObjectProvider<ProfileResolver> profiles, Dialect dialect,
                                   org.sangam.core.time.BusinessClock businessClock) {
        this.profiles = profiles;
        this.dialect = dialect;
        this.businessClock = java.util.Objects.requireNonNull(businessClock, "businessClock");
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        List<BpmnParseListener> post = cfg.getCustomPostBPMNParseListeners();
        if (post == null) {
            post = new ArrayList<>();
            cfg.setCustomPostBPMNParseListeners(post);
        }
        post.add(new AbstractBpmnParseListener() {
            @Override
            public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
                processDefinition.addBuiltInListener(ExecutionListener.EVENTNAME_START,
                        (ExecutionListener) ConfigCaseBindingPlugin.this::onStart);
            }
        });
    }

    private void onStart(DelegateExecution e) {
        String tenant = e.getTenantId();
        ProfileResolver resolver = profiles.getIfAvailable();
        if (tenant == null || resolver == null) {
            return;
        }
        if (e instanceof ExecutionEntity x && x.getSuperExecution() != null) {
            return;   // sub-processes inherit the root's binding
        }
        ResolvedProfile p;
        try {
            p = resolver.resolve(tenant, businessClock.today(tenant));
        } catch (RuntimeException ex) {
            LOG.debug("no configuration profile for tenant {}: {}", tenant, ex.getMessage());
            return;
        }
        if (p.binding() == null) {
            return;
        }
        Object carried = e.getVariable(VARIABLE);
        if (carried == null) {
            e.setVariable(VARIABLE, p.binding());
        } else if (!p.binding().equals(carried.toString())) {
            // not the binding this server resolves for the tenant today (a start variable of the caller, an older
            // case moved here): the variable stays as it is and is never stored as the case's binding
            LOG.debug("instance {} of tenant {} carries another profile binding; not stored", e.getProcessInstanceId(), tenant);
            return;
        }
        // also when the pack's start listener set the variable first (it runs before this listener and asks the same
        // profile): without the row the case has no stored binding and its configuration reads follow later versions
        Connection c = Context.getCommandContext().getDbSqlSession().getSqlSession().getConnection();
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CFG_CASE_BINDING (tenant_id, root_id, binding, bound_at) VALUES (?,?,?,?)",
                tenant, e.getProcessInstanceId(), p.binding(), Instant.now());
    }
}
