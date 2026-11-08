// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.config;

import javax.sql.DataSource;

import liquibase.integration.spring.SpringLiquibase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the control plane's own, self-contained changelog ({@code db/changelog/controlplane/cp-master.xml}, five
 * dialects). Operaton creates its ACT_* tables itself ({@code operaton.bpm.database.schema-update}); the control plane
 * has its own database, separate from every cell.
 */
@Configuration(proxyBeanMethods = false)
public class SchemaConfig {

    @Bean
    public SpringLiquibase controlPlaneLiquibase(DataSource dataSource,
                                                 @Value("${sangam.controlplane.schema.apply:true}") boolean apply) {
        org.sangam.core.licence.LiquibaseLicenceGuard.check(); // SG-26 / LIC-7
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(dataSource);
        lb.setChangeLog("classpath:db/changelog/controlplane/cp-master.xml");
        lb.setShouldRun(apply);
        return lb;
    }
}
