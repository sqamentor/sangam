// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.Set;

import org.sangam.rest.adapter.jdbc.JdbcTenantCustomFieldStore;
import org.sangam.rest.extension.TenantCustomFieldRegistry;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SL-40: the tenant custom field registry of the server, backed by the shared product table
 * {@code TENANT_CUSTOM_FIELD} ({@link JdbcTenantCustomFieldStore}), so a definition made on one node is in force on
 * every node from its effective date. Every define and retire is audited on the tenant's security chain
 * ({@code sec:<tenant>}) by a system context on behalf of the actor, in the transaction of the change.
 *
 * <p>The form ports take this bean: {@code SangamRestAutoConfiguration.sangamFormPort} (its {@code ObjectProvider})
 * and {@code PortsWiring.sangamPortsFormPort}. Extensible object types are the deployed forms ({@code form:<key>});
 * no other object type is registered yet.
 */
@Configuration(proxyBeanMethods = false)
public class TenantCustomFieldsWiring {

    /** Security audit chain of a tenant, as the other tenant configuration changes use it. */
    static String chain(String tenantId) {
        return "sec:" + tenantId;
    }

    /** The store-backed registry, audited through {@code audit}. */
    static TenantCustomFieldRegistry registry(TxSupport tx, AuditPort audit) {
        return new TenantCustomFieldRegistry(Set.of(),
                (tenant, actor, action, payload) -> audit.record(RequestContext.system(tenant, actor), chain(tenant),
                        action, payload),
                new JdbcTenantCustomFieldStore(tx));
    }

    @Bean
    public TenantCustomFieldRegistry sangamTenantCustomFields(TxSupport tx, AuditPort audit) {
        return registry(tx, audit);
    }
}
