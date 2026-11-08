// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.PermanentPublishException;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression for the operator outbox repair (VI.2.3 rule 5): {@code POST /api/v1/ops/outbox/{id}/requeue|skip} must
 * repair through the deployment's {@code OutboxRepair} (the production relay service), so the parked delivery's dead
 * letter is closed with the repair (REQUEUED / DISCARDED) instead of staying OPEN. Before the fix the operator API built
 * its own core relay and left every dead letter OPEN. Drives the real {@link OpsPort} bean the controller calls.
 */
@SpringBootTest
@ActiveProfiles("h2")
@org.springframework.test.annotation.DirtiesContext // D-10: one live engine per JVM, so no cached context outlives the class
class OutboxRepairDeadLetterTest {

    @Autowired
    OpsPort ops;

    @Autowired
    PublisherRegistry publishers;

    @Autowired
    TxSupport txSupport;

    @Autowired
    DataSource dataSource;

    private record Parked(String tenant, String destination, long outboxId) {
    }

    /** Appends one event whose destination always refuses it permanently, and waits until it is PARKED. */
    private Parked parked() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String tenant = "regr-" + run;
        String destination = "regr-repair-" + run + ":ordered";
        publishers.register(destination, (d, e) -> {
            throw new PermanentPublishException("downstream refuses " + e.eventId());
        });
        Transactions tx = Transactions.of(dataSource);
        Outbox outbox = new Outbox(txSupport.dialect());
        long id = tx.inTransaction(c -> outbox.append(c, tenant, "regr-root-" + run, "regr.event", "{}",
                List.of(Outbox.Destination.ordered(destination)))).outboxId();
        await(Duration.ofSeconds(60), () -> "PARKED".equals(state(id, destination)) && deadLetters(id, destination, "OPEN") == 1);
        return new Parked(tenant, destination, id);
    }

    private String state(long id, String destination) {
        return Transactions.of(dataSource).inTransaction(c -> Jdbc.one(c,
                "SELECT state FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?", rs -> rs.getString(1), id,
                destination).orElse(null));
    }

    private long deadLetters(long id, String destination, String state) {
        return Transactions.of(dataSource).inTransaction(c -> Jdbc.one(c,
                "SELECT COUNT(*) FROM INTEGRATION_DEAD_LETTER WHERE kind='OUTBOX' AND ref_id=? AND state=?",
                rs -> rs.getLong(1), id + ":" + destination, state).orElse(0L));
    }

    private static void await(Duration max, BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met within " + max);
    }

    @Test
    void operatorSkipClosesTheDeadLetterAsDiscarded() throws Exception {
        Parked p = parked();
        try {
            ops.skipOutbox(RequestContext.system(p.tenant(), null), String.valueOf(p.outboxId()),
                    new OpsPort.OutboxRepairRequest(p.destination(), "regression: payload withdrawn"));
            assertThat(state(p.outboxId(), p.destination())).isEqualTo("SKIPPED");
            assertThat(deadLetters(p.outboxId(), p.destination(), "OPEN")).as("no dead letter left open").isZero();
            assertThat(deadLetters(p.outboxId(), p.destination(), "DISCARDED")).isEqualTo(1);
        } finally {
            publishers.unregister(p.destination());
        }
    }

    @Test
    void operatorRequeueClosesTheDeadLetterAsRequeued() throws Exception {
        Parked p = parked();
        try {
            publishers.register(p.destination(), (d, e) -> { });   // the downstream is fixed before the operator requeues
            ops.requeueOutbox(RequestContext.system(p.tenant(), null), String.valueOf(p.outboxId()),
                    new OpsPort.OutboxRepairRequest(p.destination(), "regression: downstream fixed"));
            assertThat(deadLetters(p.outboxId(), p.destination(), "REQUEUED")).isEqualTo(1);
            await(Duration.ofSeconds(60), () -> "DONE".equals(state(p.outboxId(), p.destination())));
            assertThat(deadLetters(p.outboxId(), p.destination(), "OPEN")).as("no dead letter left open").isZero();
        } finally {
            publishers.unregister(p.destination());
        }
    }
}
