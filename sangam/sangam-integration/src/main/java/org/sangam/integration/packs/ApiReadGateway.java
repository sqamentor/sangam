// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import java.util.Optional;

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;

/**
 * The connector entry of an API read (owner decisions 320 and 321): a request handler that only reads (the low-code
 * data-object records {@code GET}) reaches a connector through this interface and never through
 * {@code ConnectorGateway.query}. A pack step in query mode is not an API read (it runs as an engine job inside a
 * process, with its audit) and keeps {@code ConnectorGateway.query}.
 *
 * <p>The call is made outside the operation ledger, idempotency and maker-checker, so the bound connector must itself
 * answer {@link org.sangam.core.integration.SangamConnector#readOnly} for the exact request that would be sent; any
 * other request is refused with {@link org.sangam.core.integration.SangamConnector#OPERATION_NOT_READ_ONLY} and the
 * connector is not invoked.
 */
public interface ApiReadGateway {

    /**
     * Invokes the bound connector only when it declares this request read-only; otherwise a FAILED result with reason
     * {@code CONNECTOR_OPERATION_NOT_READ_ONLY}, nothing invoked.
     */
    ConnectorResult queryReadOnly(ConnectorRequest request);

    /**
     * Why {@link #queryReadOnly} would refuse this request, without invoking anything; empty when it would not be
     * refused for this reason. A pack connector with no binding, or one bound to a connector that is not registered,
     * gives empty (nothing can be said yet; the run-time check is the guard).
     */
    Optional<String> readOnlyRefusal(ConnectorRequest request);
}
