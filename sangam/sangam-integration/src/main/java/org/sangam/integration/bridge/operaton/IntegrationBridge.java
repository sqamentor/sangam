// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.relay.OutboxWriter;

import java.util.Objects;

/**
 * Process-wide access point for Operaton delegates and listeners, which the engine instantiates by class name and so
 * cannot receive constructor injection. sangam-server sets it once at start-up ({@link #install}); Spring users may
 * instead expose {@link SangamConnectorDelegate} / {@link SendEventDelegate} as beans built with explicit arguments.
 *
 * <p>{@code delegateInvoker} must use its own transactions on the product data source (not the engine transaction),
 * so the call intent commits <b>before</b> the remote call (R37) even though a JavaDelegate runs inside the job's
 * engine transaction.
 */
public final class IntegrationBridge {

    private static volatile IntegrationBridge current;

    private final ConnectorInvoker delegateInvoker;
    private final OutboxWriter outboxWriter;
    private final String defaultTenant;

    public IntegrationBridge(ConnectorInvoker delegateInvoker, OutboxWriter outboxWriter, String defaultTenant) {
        this.delegateInvoker = delegateInvoker;
        this.outboxWriter = outboxWriter;
        this.defaultTenant = defaultTenant == null ? "default" : defaultTenant;
    }

    public static void install(IntegrationBridge bridge) {
        current = Objects.requireNonNull(bridge);
    }

    public static IntegrationBridge get() {
        IntegrationBridge b = current;
        if (b == null) {
            throw new IllegalStateException("Sangam IntegrationBridge is not installed (sangam-server wiring)");
        }
        return b;
    }

    public ConnectorInvoker delegateInvoker() {
        return delegateInvoker;
    }

    public OutboxWriter outboxWriter() {
        return outboxWriter;
    }

    public String defaultTenant() {
        return defaultTenant;
    }
}
