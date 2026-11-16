// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.time.Duration;
import java.util.Objects;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Transactions;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.InboxPort;
import org.sangam.rest.port.RequestContext;

/**
 * RM-30: the {@link InboxPort} of every host, over the core {@link Inbox} table ({@code INBOX}, primary key
 * {@code tenant_id, source, message_id}). Plain Java: a host hands it its {@link Transactions} and dialect. The
 * tenant is always the caller's, so one tenant's message id never dedupes another's.
 */
public final class InboxPortAdapter implements InboxPort {

    /** How long an accepted message waits for processing before it expires (the core inbox default). */
    public static final Duration TTL = Duration.ofHours(24);

    private final Inbox inbox;
    private final Transactions tx;

    public InboxPortAdapter(Dialect dialect, Transactions tx, String node) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.inbox = new Inbox(dialect, tx, node, Duration.ofSeconds(30), Duration.ofSeconds(30), null);
    }

    @Override
    public Receipt receive(RequestContext ctx, String source, String messageId, String correlationKey, String payload) {
        String tenant = tenant(ctx);
        require(source, "source");
        require(messageId, "messageId");
        return tx.inTransaction(c -> {
            Inbox.ReceiveResult r = inbox.receive(c, tenant, source, messageId, correlationKey, payload, TTL);
            Inbox.State s = Inbox.state(c, tenant, source, messageId);
            return new Receipt(r == Inbox.ReceiveResult.ACCEPTED ? Outcome.ACCEPTED : Outcome.DUPLICATE,
                    s == null ? null : s.name());
        });
    }

    @Override
    public String state(RequestContext ctx, String source, String messageId) {
        String tenant = tenant(ctx);
        return tx.inTransaction(c -> {
            Inbox.State s = Inbox.state(c, tenant, source, messageId);
            return s == null ? null : s.name();
        });
    }

    private static String tenant(RequestContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw ApiProblem.badRequest("TENANT_REQUIRED", "an inbox message belongs to a tenant");
        }
        return ctx.tenantId();
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw ApiProblem.badRequest("INBOX_" + name.toUpperCase(java.util.Locale.ROOT) + "_REQUIRED", name + " is required");
        }
    }
}
