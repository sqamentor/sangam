// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.assignment;

import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.security.SangamSecurity;
import org.sangam.security.tenant.SangamTenantIdProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The security {@code ProcessEnginePlugin} (ADR-D-16). The integrator adds it next to {@code SangamProcessEnginePlugin}
 * (sangam-engine). It
 * <ul>
 *   <li>registers {@link SeatAssignmentParseListener} as a post-parse listener (seat assignment, SoD, initiator);</li>
 *   <li>sets {@link SangamTenantIdProvider} so every instance carries the identity's tenant (VI.4 rule 6);</li>
 *   <li>keeps Operaton's built-in authorization <b>off</b>: it has no deny-wins (D-14); reads go through the VI.4
 *       predicate only (ARCHITECTURE.md §1.4).</li>
 * </ul>
 */
public final class SangamSecurityEnginePlugin extends AbstractProcessEnginePlugin {

    private final SangamSecurity security;

    public SangamSecurityEnginePlugin(SangamSecurity security) {
        this.security = security;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        List<BpmnParseListener> post = cfg.getCustomPostBPMNParseListeners() == null
                ? new ArrayList<>() : new ArrayList<>(cfg.getCustomPostBPMNParseListeners());
        post.add(new SeatAssignmentParseListener(security));
        cfg.setCustomPostBPMNParseListeners(post);
        cfg.setTenantIdProvider(new SangamTenantIdProvider());
        cfg.setAuthorizationEnabled(false);
    }
}
