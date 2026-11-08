// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Transactions;
import org.sangam.modeller.auth.TokenExchange;

/**
 * On-behalf-of calls for a connector. When the request carries {@code onBehalfOfUser} and {@code onBehalfOfToken}, the
 * token is exchanged for an audience-scoped one, passed on as input {@code bearerToken}, and the acting user goes to the
 * R42 audit event {@code CONNECTOR_TOKEN_EXCHANGED}. The subject token is removed from the input and never audited.
 * Requests without those fields pass through unchanged.
 */
public final class TokenExchangeConnector implements SangamConnector {

    private final SangamConnector delegate;
    private final TokenExchange exchange;
    private final String audience;
    private final Transactions tx;
    private final AuditChain audit;

    public TokenExchangeConnector(SangamConnector delegate, TokenExchange exchange, String audience, Transactions tx, AuditChain audit) {
        this.delegate = delegate;
        this.exchange = exchange;
        this.audience = audience;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        return delegate.invoke(authorised(request));
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        return delegate.lookup(authorised(request));
    }

    @Override
    public boolean healthy() {
        return delegate.healthy();
    }

    private ConnectorRequest authorised(ConnectorRequest r) {
        String user = r.str("onBehalfOfUser");
        String subject = r.str("onBehalfOfToken");
        if (user == null || subject == null) {
            return r;
        }
        TokenExchange.ExchangedToken t = exchange.exchange(r.tenantId(), user, subject, audience);
        tx.inTransaction(c -> audit.append(c, r.tenantId(), "connector:" + delegate.descriptor().id(), "CONNECTOR_TOKEN_EXCHANGED",
                Map.of("actingUser", t.actingUser(), "audience", t.audience(), "operationId", r.operationId().toString())));
        Map<String, Object> input = new LinkedHashMap<>(r.input());
        input.remove("onBehalfOfToken");
        input.put("bearerToken", t.accessToken());
        return r.withInput(input);
    }
}
