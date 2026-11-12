// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.integration.connector.contract.JdbcApiContractStore;
import org.sangam.rest.adapter.jdbc.ApiContractAdapter;
import org.sangam.rest.port.ApiContractPort;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.support.TxSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M-068: the API contracts of packages. {@code sangamApiContractStore} is THE {@link ApiContractStore} of the server:
 * {@link JdbcApiContractStore} over {@code API_CONTRACT} (changeset 0043-08, in the baseline of every dialect, so there is
 * no switch). It takes only {@link TxSupport} (DataSource and transaction manager), never the engine, so the engine's
 * {@code OperationRefPinning.Pinner} (W3-F) can be built from it before the engine without a bean cycle; its units join
 * the caller's Spring transaction. The routes ({@code ApiContractController}) use {@link ApiContractPort}, which checks
 * the package through {@link PackagePort}, stores only into a DRAFT package and audits each new version (decision 115).
 */
@Configuration(proxyBeanMethods = false)
public class ApiContractWiring {

    /** Joins the caller's Spring transaction, so the insert and its API_CONTRACT_STORED audit commit together. */
    @Bean
    public ApiContractStore sangamApiContractStore(TxSupport sangamTxSupport) {
        return new JdbcApiContractStore(sangamTxSupport.dialect(), ApiContractAdapter.joined(sangamTxSupport));
    }

    @Bean
    public ApiContractPort sangamApiContractPort(ApiContractStore sangamApiContractStore, PackagePort packages,
                                                 TxSupport sangamTxSupport, AuditPort audit) {
        return new ApiContractAdapter(sangamApiContractStore, packages, sangamTxSupport, audit);
    }
}
