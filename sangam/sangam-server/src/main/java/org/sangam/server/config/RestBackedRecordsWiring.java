// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.layer.AuditChain;
import org.sangam.integration.packs.IntegrationConnectorGateway;
import org.sangam.integration.packs.PackOperationStore;
import org.sangam.rest.port.RestBackedRecords;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.DataObjectSettlementAudit;
import org.sangam.server.bridge.RestBackedRecordsGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EC-06, owner decisions 265 and 266: REST-backed data objects over the integration connector gateway. Only with the
 * integration module (same switch as {@link IntegrationWiring}, which defines the gateway); without it there is no
 * {@link RestBackedRecords} bean and REST backing answers 501 CAPABILITY_NOT_INSTALLED. Bound to
 * {@link IntegrationConnectorGateway} itself, never to the packs' simulator gateway.
 *
 * <p>Owner decision 289: {@link DataObjectSettlementAudit} is registered for the kind {@code data-object} on the pack
 * operation store, so each REST-backed create that settles writes {@code DATA_OBJECT_RECORD_SETTLED} exactly once, in
 * the settling transaction.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class RestBackedRecordsWiring {

    @Bean
    @ConditionalOnMissingBean(RestBackedRecords.class)
    public RestBackedRecords sangamRestBackedRecords(IntegrationConnectorGateway sangamIntegrationConnectorGateway) {
        return new RestBackedRecordsGateway(sangamIntegrationConnectorGateway, sangamIntegrationConnectorGateway);
    }

    /** Decision 289: the data-object settlement emitter, registered on the store when it is created. */
    @Bean
    public DataObjectSettlementAudit sangamDataObjectSettlementAudit(PackOperationStore sangamPackOperationStore,
                                                                     TxSupport tx) {
        DataObjectSettlementAudit audit = new DataObjectSettlementAudit(new AuditChain(tx.dialect()));
        sangamPackOperationStore.onSettled(DataObjectSettlementAudit.KIND, audit);
        return audit;
    }
}
