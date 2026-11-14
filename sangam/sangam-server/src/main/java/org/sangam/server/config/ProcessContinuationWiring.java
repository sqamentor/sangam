// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.security.SecurityPorts;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.continuation.ProcessContinuations;
import org.sangam.rest.port.ProcessContinuationPort;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.ProcessContinuationBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Decision 149 (M-060): the continue-as-new port over the engine's {@link ProcessContinuations}. A core route, always
 * present. The object checks use sangam-security's VI.4 authorization; without sangam-security the engine refuses every
 * continuation (fail closed, {@link #authorization(SangamSecurity)} gives null), it never falls back to a tenant-only
 * check.
 */
@Configuration(proxyBeanMethods = false)
public class ProcessContinuationWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessContinuationWiring.class);

    @Bean
    @ConditionalOnMissingBean(ProcessContinuationPort.class)
    public ProcessContinuationPort sangamProcessContinuationPort(OperatonEngineAdapter sangamEngineAdapter,
                                                                 ObjectProvider<SangamSecurity> security) {
        SecurityPorts.Authorization authz = authorization(security.getIfAvailable());
        if (authz == null) {
            LOG.warn("sangam-security is off: continue-as-new refuses every request (decision 149 fails closed)");
        }
        return new ProcessContinuationBridge(ProcessContinuationBridge.Continuations.of(
                new ProcessContinuations(sangamEngineAdapter, authz)));
    }

    /** The VI.4 authorization of sangam-security, or null (the engine then refuses: fail closed). */
    public static SecurityPorts.Authorization authorization(SangamSecurity security) {
        return security == null ? null : security.authorization();
    }
}
