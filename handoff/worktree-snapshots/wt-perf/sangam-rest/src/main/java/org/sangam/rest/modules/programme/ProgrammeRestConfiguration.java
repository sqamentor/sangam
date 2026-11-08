// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.programme;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * REST exposure of sangam-programme (N15: programmes, targets, milestones, periodic returns, roll-up, progress,
 * variance alerts, dashboards). Active when the module is on the class path and sangam-server bound its REST port.
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.programme.ProgrammeModule")
public class ProgrammeRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.programme.rest.ProgrammeRestPort")
    @Import({ProgrammeController.class, ProgrammeProblemAdvice.class})
    static class Controllers {
    }
}
