// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.cases;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * REST exposure of sangam-case (N04 case files, N16 committees, meetings, action items and references). Active when
 * the module is on the class path and sangam-server bound its REST ports (the module's {@code CaseRestAdapter}).
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.casefile.CaseModule")
public class CaseRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = {"org.sangam.casefile.rest.CaseFileRestPort", "org.sangam.casefile.rest.MeetingRestPort",
            "org.sangam.casefile.rest.ReferenceRestPort"})
    @Import({CaseFileController.class, MeetingController.class, ReferenceController.class, CaseProblemAdvice.class})
    static class Controllers {
    }
}
