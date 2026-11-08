// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** REST exposure of sangam-ops; active when the module is on the class path and sangam-server bound {@link OpsApi}. */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.ops.search.GlobalSearch")
public class OpsRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.rest.modules.ops.OpsApi")
    @Import({OpsController.class, OpsUiController.class})
    static class Controllers {
    }

    /** SL-44 bulk ingest; active when sangam-server bound {@link org.sangam.ops.bulk.BulkIngest}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.ops.bulk.BulkIngest")
    @Import({BulkController.class, BulkProblemAdvice.class})
    static class Bulk {
    }
}
