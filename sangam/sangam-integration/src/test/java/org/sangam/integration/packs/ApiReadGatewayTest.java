// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.packs.port.OperationStatus;

/**
 * Owner decisions 320 and 321: the API-read entry of the connector gateway ({@link ApiReadGateway#queryReadOnly}) calls
 * a connector only for a request the connector itself declares read-only; anything else is refused with
 * CONNECTOR_OPERATION_NOT_READ_ONLY and the connector is not invoked. The pack step's query mode
 * ({@code ConnectorGateway.query}) is not an API read and is unchanged.
 *
 * <p>The case is the one found in the code-scanning triage: a pack connector id bound to a state-changing operation
 * of a money connector (close a period), listed through a data object's GET.
 */
class ApiReadGatewayTest {

    /** Records every invoke; answers readOnly from the given rule (the request, as the gateway resolved it). */
    static final class Recording implements SangamConnector {
        final List<ConnectorRequest> invoked = new ArrayList<>();
        final Predicate<ConnectorRequest> readOnly;

        Recording(Predicate<ConnectorRequest> readOnly) {
            this.readOnly = readOnly;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("books", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    List.of("balance", "closePeriod"), ConnectorProfile.LIVE);
        }

        @Override
        public boolean readOnly(ConnectorRequest request) {
            return readOnly.test(request);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            invoked.add(request);
            return ConnectorResult.Success.of(Map.of("records", List.of(Map.of("id", "1")), "total", 1), null);
        }
    }

    /** A connector written before decision 320: it does not override readOnly. */
    static final class Undeclared implements SangamConnector {
        final List<ConnectorRequest> invoked = new ArrayList<>();

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("legacy", "in-process", CapabilityClass.C_NEITHER, List.of(), ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            invoked.add(request);
            return ConnectorResult.Success.of(Map.of("records", List.of()), null);
        }
    }

    final Recording books = new Recording(r -> "balance".equals(r.operation()));
    final Undeclared legacy = new Undeclared();
    final ConnectorRegistry registry = new ConnectorRegistry().register(books).register(legacy);
    final PackConnectorBindings bindings = new PackConnectorBindings()
            .bind("books.closePeriod", "books", "closePeriod")
            .bind("books.balance", "books", "balance")
            .bind("books.any", "books", null)
            .bind("legacy.list", "legacy", null);
    /** No engine, store or journal: a query touches only the bindings and the registry. */
    final IntegrationConnectorGateway gateway = new IntegrationConnectorGateway(null, null, null, bindings, registry, null,
            null, null, null, null);

    static org.sangam.packs.port.ConnectorRequest list(String packConnector) {
        Map<String, Object> payload = Map.of("offset", 0, "limit", 20);
        return new org.sangam.packs.port.ConnectorRequest("t1", packConnector, "list", null, null, null, payload, "h");
    }

    @Test
    void anApiReadOfABindingToAStateChangingOperationIsRefusedAndTheConnectorIsNotInvoked() {
        org.sangam.packs.port.ConnectorResult r = gateway.queryReadOnly(list("books.closePeriod"));
        assertEquals(OperationStatus.FAILED, r.status());
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", r.reasonCode());
        assertEquals(SangamConnector.OPERATION_NOT_READ_ONLY, r.reasonCode());
        assertTrue(r.message().contains("books") && r.message().contains("closePeriod"), r.message());
        assertTrue(books.invoked.isEmpty(), "the write was not invoked");
        assertTrue(gateway.readOnlyRefusal(list("books.closePeriod")).isPresent(), "the definition-time probe says so too");
        assertTrue(books.invoked.isEmpty(), "the probe invokes nothing");
    }

    @Test
    void theOperationJudgedIsTheOneTheBindingResolvesToNotTheCallersName() {
        // the caller always says "list"; the binding replaces it, and the connector judges what will really run
        assertEquals(OperationStatus.COMPLETED, gateway.queryReadOnly(list("books.balance")).status());
        assertEquals("balance", books.invoked.get(0).operation());
        // without a rename the caller's own operation reaches the connector, which does not declare "list" read-only
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", gateway.queryReadOnly(list("books.any")).reasonCode());
        assertEquals(1, books.invoked.size());
    }

    @Test
    void aReadOnlyRequestStillLists() {
        org.sangam.packs.port.ConnectorResult r = gateway.queryReadOnly(list("books.balance"));
        assertEquals(OperationStatus.COMPLETED, r.status());
        assertEquals(List.of(Map.of("id", "1")), r.data().get("records"));
        assertEquals(1, books.invoked.size());
        assertEquals(Map.of("offset", 0, "limit", 20), books.invoked.get(0).input());
        assertTrue(gateway.readOnlyRefusal(list("books.balance")).isEmpty());
    }

    @Test
    void aConnectorThatDeclaresNothingIsRefusedOnTheApiReadPath() {
        org.sangam.packs.port.ConnectorResult r = gateway.queryReadOnly(list("legacy.list"));
        assertEquals(OperationStatus.FAILED, r.status());
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", r.reasonCode());
        assertTrue(legacy.invoked.isEmpty());
        assertTrue(gateway.readOnlyRefusal(list("legacy.list")).isPresent());
    }

    @Test
    void aConnectorThatCannotAnswerIsRefused() {
        Recording broken = new Recording(r -> {
            throw new IllegalStateException("cannot tell");
        });
        IntegrationConnectorGateway g = new IntegrationConnectorGateway(null, null, null,
                new PackConnectorBindings().bind("x.list", "books", null), new ConnectorRegistry().register(broken), null,
                null, null, null, null);
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", g.queryReadOnly(list("x.list")).reasonCode());
        assertTrue(broken.invoked.isEmpty());
    }

    @Test
    void thePackQueryPathIsUnchanged() {
        // decision 321: a pack step in query mode is an engine job inside a process, not an API read
        assertEquals(OperationStatus.COMPLETED, gateway.query(list("books.closePeriod")).status());
        assertEquals("closePeriod", books.invoked.get(0).operation());
        assertEquals(OperationStatus.COMPLETED, gateway.query(list("legacy.list")).status());
        assertEquals(1, legacy.invoked.size());
    }

    @Test
    void anUnresolvedBindingIsNotConfiguredAndTheProbeSaysNothing() {
        assertEquals("CONNECTOR_NOT_CONFIGURED", gateway.queryReadOnly(list("nobody.bound")).reasonCode());
        assertFalse(gateway.readOnlyRefusal(list("nobody.bound")).isPresent(), "nothing can be said yet");
        bindings.bind("gone.list", "not-registered", null);
        assertEquals("CONNECTOR_NOT_CONFIGURED", gateway.queryReadOnly(list("gone.list")).reasonCode());
        assertFalse(gateway.readOnlyRefusal(list("gone.list")).isPresent());
    }

    @Test
    void aTenantsOwnBindingIsTheOneJudged() {
        bindings.bindForTenant("t2", "books.balance", "books", "closePeriod");
        Map<String, Object> payload = Map.of("offset", 0, "limit", 20);
        org.sangam.packs.port.ConnectorRequest t2 = new org.sangam.packs.port.ConnectorRequest("t2", "books.balance", "list",
                null, null, null, payload, "h");
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", gateway.queryReadOnly(t2).reasonCode());
        assertTrue(books.invoked.isEmpty());
    }
}
