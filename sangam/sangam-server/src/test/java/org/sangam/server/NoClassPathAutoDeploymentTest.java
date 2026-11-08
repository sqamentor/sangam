// SPDX-License-Identifier: Apache-2.0
package org.sangam.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.repository.ResourceDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression: the server never deploys the models of packs or jurisdiction profiles by class-path scan. Those models
 * are deployed by their owners, per tenant; a scan puts them into one tenant-less deployment, which (with modules as
 * class folders, as in this module's {@code -am test} run) failed on the decision keys the jurisdiction profiles share
 * and would otherwise share every pack across tenants. Asserted on the started engine: no tenant-less BPMN, DMN or CMMN
 * definition comes from a module's {@code packs/} or {@code profiles/} resources.
 */
@SpringBootTest
@ActiveProfiles("h2")
@org.springframework.test.annotation.DirtiesContext // D-10: one live engine per JVM, so no cached context outlives the class
class NoClassPathAutoDeploymentTest {

    @Autowired
    ProcessEngine engine;

    @Test
    void noPackOrProfileModelIsDeployedWithoutATenant() {
        var repo = engine.getRepositoryService();
        List<ResourceDefinition> tenantless = new java.util.ArrayList<>();
        tenantless.addAll(repo.createProcessDefinitionQuery().withoutTenantId().list());
        tenantless.addAll(repo.createDecisionDefinitionQuery().withoutTenantId().list());
        tenantless.addAll(repo.createCaseDefinitionQuery().withoutTenantId().list());
        assertThat(tenantless).as("tenant-less definitions from pack or profile resources")
                .filteredOn(d -> d.getResourceName() != null && d.getResourceName().matches("(?:.*/)?(packs|profiles)/.*"))
                .extracting(ResourceDefinition::getResourceName).isEmpty();
    }
}
