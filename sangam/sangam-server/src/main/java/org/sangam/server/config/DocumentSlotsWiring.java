// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.support.DocumentSlots;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.OperatonDocumentSlots;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Owner decisions 267 to 269: the document route fills DSC document slots from the pack manifests. Only with the packs
 * module (the slot pairs are pack data); with {@code sangam.modules.packs.enabled=false} the route stores a
 * documentType as metadata and fills no slot.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.packs.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentSlotsWiring {

    @Bean
    @ConditionalOnMissingBean(DocumentSlots.class)
    public DocumentSlots sangamDocumentSlots(ProcessEngine engine, PackRegistry sangamPackRegistry, TxSupport tx,
                                             PermissionPort permissions) {
        return new DocumentSlots(new OperatonDocumentSlots(engine.getRuntimeService(), engine.getTaskService(),
                engine.getRepositoryService(), sangamPackRegistry, tx), permissions);
    }
}
