// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.field;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * REST exposure of sangam-field (N13 field work, N17 dispatch) under {@code /api/v1/field}. Active when the module is
 * on the class path and sangam-server bound the module's {@code FieldRestPort}.
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.field.FieldModule")
public class FieldRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.field.rest.FieldRestPort")
    @Import({FieldWorkController.class, FieldProblemAdvice.class})
    static class Controllers {
    }
}
