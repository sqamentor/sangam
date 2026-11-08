// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.sql.Connection;
import java.time.Instant;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.persistence.deploy.Deployer;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.operaton.bpm.engine.repository.CaseDefinition;
import org.operaton.bpm.engine.repository.DecisionDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.sangam.core.engine.SangamClock;
import org.sangam.engine.tx.CommandState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R10 deploy-time half: every decision, process and case definition of a <b>new</b> deployment is bound to the FEEL
 * engine running now ({@link FeelEngineIdentity}), in the deploy transaction. A deployment re-parsed into the cache
 * (engine restart) is not re-bound; if the FEEL engine changed since it was deployed, the run-time check
 * ({@link FeelPinningListener}) refuses to evaluate its decisions and this deployer logs the mismatch once.
 */
public final class FeelBindingDeployer implements Deployer {

    private static final Logger LOG = LoggerFactory.getLogger(FeelBindingDeployer.class);

    private final FeelBindingStore store;
    private final SangamClock clock;

    public FeelBindingDeployer(FeelBindingStore store, SangamClock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void deploy(DeploymentEntity deployment) {
        String current = FeelEngineIdentity.of(Context.getProcessEngineConfiguration().getDmnEngineConfiguration());
        Connection c = CommandState.currentConnection();
        Instant now = clock.now();
        for (DecisionDefinition d : nz(deployment.getDeployedDecisionDefinitions())) {
            bind(c, deployment, d.getId(), d.getKey(), "DECISION", current, now);
        }
        for (ProcessDefinition p : nz(deployment.getDeployedProcessDefinitions())) {
            bind(c, deployment, p.getId(), p.getKey(), "PROCESS", current, now);
        }
        for (CaseDefinition k : nz(deployment.getDeployedCaseDefinitions())) {
            bind(c, deployment, k.getId(), k.getKey(), "CASE", current, now);
        }
    }

    private static <T> java.util.List<T> nz(java.util.List<T> l) {
        return l == null ? java.util.List.of() : l;
    }

    private void bind(Connection c, DeploymentEntity deployment, String id, String key, String kind, String current,
                      Instant now) {
        if (deployment.isNew()) {
            store.record(c, id, key, kind, deployment.getId(), current, now);
            return;
        }
        store.find(c, id).filter(bound -> !bound.equals(current)).ifPresent(bound -> LOG.warn(
                "R10: {} {} ({}) was deployed with FEEL {} but the engine runs {}; its decisions will be refused "
                        + "until it is redeployed", kind, key, id, bound, current));
    }
}
