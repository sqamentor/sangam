// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ai;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** REST exposure of sangam-ai; active when the module is on the class path and sangam-server bound {@link AiApi}. */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.ai.agent.AgentRuntime")
public class AiRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.rest.modules.ai.AiApi")
    @Import({AiController.class, AiUiController.class, AiProblemAdvice.class})
    static class Controllers {
    }
}
