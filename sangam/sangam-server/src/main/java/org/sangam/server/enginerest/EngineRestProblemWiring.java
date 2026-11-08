// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.enginerest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jersey.autoconfigure.ResourceConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts {@link EngineRestProblemMappers} on the engine-rest Jersey application (SL-43, SL-45). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.glassfish.jersey.server.ResourceConfig")
public class EngineRestProblemWiring {

    @Bean
    public ResourceConfigCustomizer sangamEngineRestProblemMappers() {
        return EngineRestProblemMappers::register;
    }
}
