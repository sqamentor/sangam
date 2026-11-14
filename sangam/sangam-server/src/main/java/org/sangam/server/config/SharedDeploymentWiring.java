// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.tenant.SharedDeployments;
import org.sangam.server.bridge.SharedDeploymentBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Decisions 144 and 197: the shared-deployment registry routes over the engine's {@link SharedDeployments}. A core
 * route, always present; the server's engine always carries the SangamProcessEnginePlugin, so the registry is built at
 * start-up (a server engine without it is refused there, as {@link SharedDeployments} requires).
 */
@Configuration(proxyBeanMethods = false)
public class SharedDeploymentWiring {

    @Bean
    @ConditionalOnMissingBean(SharedDeploymentBridge.class)
    public SharedDeploymentBridge sangamSharedDeploymentBridge(OperatonEngineAdapter sangamEngineAdapter) {
        return new SharedDeploymentBridge(SharedDeploymentBridge.Registry.of(new SharedDeployments(sangamEngineAdapter)));
    }
}
