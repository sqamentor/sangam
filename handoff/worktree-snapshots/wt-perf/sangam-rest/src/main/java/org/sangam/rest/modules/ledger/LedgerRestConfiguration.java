// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ledger;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * REST exposure of sangam-ledger ({@code /api/v1/ledger}). Active when the module is on the class path and
 * sangam-server's {@code LedgerWiring} created the {@link LedgerTenantDefinitions} over its {@code LedgerService}.
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.ledger.LedgerService")
public class LedgerRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.rest.modules.ledger.LedgerTenantDefinitions")
    @Import({LedgerController.class, LedgerProblemAdvice.class})
    static class Controllers {
    }
}
