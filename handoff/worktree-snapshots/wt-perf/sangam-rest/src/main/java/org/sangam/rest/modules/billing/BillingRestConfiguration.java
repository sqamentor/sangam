// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.billing;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * REST exposure of sangam-billing ({@code /api/v1/billing}). Active when the module is on the class path and
 * sangam-server's {@code BillingWiring} created the {@code BillingConnector} and {@code BillingService}.
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.billing.BillingService")
public class BillingRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = {"org.sangam.billing.BillingConnector", "org.sangam.billing.BillingService",
            "org.sangam.rest.modules.ledger.LedgerTenantDefinitions"})
    @Import({BillingController.class, BillingProblemAdvice.class})
    static class Controllers {
    }
}
