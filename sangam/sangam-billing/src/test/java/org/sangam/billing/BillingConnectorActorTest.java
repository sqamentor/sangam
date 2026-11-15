// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.time.BusinessClock;
import org.sangam.ledger.LedgerService;

/**
 * RM-37 (owner decisions 141 and 142: the actor is a server-side identity, never a payload value). The actor recorded
 * with a billing posting is the one the host's {@link BillingConnector.ActorSource} states. A step input that names
 * another actor never becomes the recorded actor: with an authenticated actor it is refused and nothing is posted,
 * without one the posting is recorded as the connector's system identity. The receipt reference is a business input:
 * required, and receipted once.
 */
class BillingConnectorActorTest {

    /** One connector over an in-memory ledger whose audit link records the actor of every posting. */
    static final class Fixture {
        final List<String> postedBy = new ArrayList<>();
        final List<String> numberedBy = new ArrayList<>();
        final LedgerService ledger = BillingServiceTest.ledger()
                .withAudit((tenant, reference, operation, auditRef, actor, summary) -> postedBy.add(operation + ":" + actor));
        final TestRails.Settling rail = new TestRails.Settling();
        final BillingConnector connector;

        Fixture(BillingConnector.ActorSource actors, boolean legacyConstructor) {
            DocumentNumbers series = DocumentNumbers.overSeries(new NumberSeries.InMemory());
            DocumentNumbers numbers = (request, plan) -> {
                numberedBy.add(request.kind() + ":" + request.actor());
                return series.issue(request, plan);
            };
            BillingService billing = new BillingService(ledger, new TariffEngine(null), numbers, rail,
                    BusinessClock.utc(BillingRetryTest.AT));
            billing.invoice("T", "DEMAND", "2026", "C-1", "2026-10", BillingRetryTest.FLAT, Map.of(), Map.of(),
                    LocalDate.parse("2026-11-01"), "setup");
            postedBy.clear();
            numberedBy.clear();
            connector = legacyConstructor
                    ? new BillingConnector(billing, ledger, null, (t, c) -> List.of(), (t, d) -> "2026", BillingRetryTest.AT)
                    : new BillingConnector(billing, ledger, null, (t, c) -> List.of(), (t, d) -> "2026", BillingRetryTest.AT,
                    actors);
        }

        ConnectorResult call(String operation, Map<String, Object> input) {
            return connector.invoke(new ConnectorRequest("T", UUID.randomUUID(), "att-1", 1, "root-1", "BK-1", operation,
                    input, null));
        }

        Set<String> actors() {
            Set<String> out = new LinkedHashSet<>();
            postedBy.forEach(p -> out.add(p.substring(p.indexOf(':') + 1)));
            numberedBy.forEach(p -> out.add(p.substring(p.indexOf(':') + 1)));
            return out;
        }

        BigDecimal outstanding() {
            return BillingRetryTest.outstanding(ledger, "C-1");
        }
    }

    static Map<String, Object> collection(String paymentRef, String claimedActor) {
        Map<String, Object> in = new HashMap<>(Map.of("consumer", "C-1", "channel", "upi", "paymentRef", paymentRef,
                "amount", "40.00"));
        if (claimedActor != null) {
            in.put("actor", claimedActor);
        }
        return in;
    }

    static Map<String, Object> waiver(String orderRef, String claimedActor) {
        Map<String, Object> in = new HashMap<>(Map.of("consumer", "C-1", "head", "FIXED", "cycle", "2026-10",
                "period", "2026", "amount", "10.00", "orderRef", orderRef));
        if (claimedActor != null) {
            in.put("actor", claimedActor);
        }
        return in;
    }

    static void refused(String code, ConnectorResult r) {
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r);
        assertEquals(code, f.code(), f.message());
    }

    @Test
    void aLegitimateCollectionAndWaiverRecordTheAuthenticatedActor() {
        Fixture f = new Fixture(request -> "cashier-7", false);
        assertInstanceOf(ConnectorResult.Success.class, f.call("collect", collection("PAY-1", null)));
        assertEquals(0, f.outstanding().compareTo(new BigDecimal("60")));
        assertEquals(Set.of("cashier-7"), f.actors(), "the receipt and its postings: " + f.postedBy + f.numberedBy);
        assertTrue(f.postedBy.contains("CONSUME:cashier-7"), f.postedBy.toString());
        assertTrue(f.numberedBy.contains("RECEIPT:cashier-7"), f.numberedBy.toString());

        // the host may repeat the authenticated actor in the input (the REST adapter does): that is no forgery
        assertInstanceOf(ConnectorResult.Success.class, f.call("waive", waiver("W-1", "cashier-7")));
        assertTrue(f.postedBy.contains("WRITE_OFF:cashier-7"), f.postedBy.toString());
        assertEquals(Set.of("cashier-7"), f.actors());
    }

    @Test
    void anInputNamingAnotherActorIsRefusedAndNothingIsPosted() {
        Fixture f = new Fixture(request -> "cashier-7", false);
        refused(BillingConnector.ACTOR_MISMATCH, f.call("collect", collection("PAY-1", "supervisor-1")));
        refused(BillingConnector.ACTOR_MISMATCH, f.call("waive", waiver("W-1", "supervisor-1")));
        refused(BillingConnector.ACTOR_MISMATCH, f.call("penalty", Map.of("consumer", "C-1", "tariffId", "FLAT",
                "period", "2026", "actor", "supervisor-1")));
        refused(BillingConnector.ACTOR_MISMATCH, f.call("invoice", Map.of("consumer", "C-1", "cycle", "2026-11",
                "tariffId", "FLAT", "period", "2026", "actor", "supervisor-1")));
        assertEquals(0, f.outstanding().compareTo(new BigDecimal("100")), "the demand is untouched");
        assertTrue(f.postedBy.isEmpty(), "nothing posted: " + f.postedBy);
        assertTrue(f.numberedBy.isEmpty(), "no receipt or invoice number drawn: " + f.numberedBy);
        assertTrue(f.rail.confirmed.isEmpty(), "the rail was not even asked");
    }

    @Test
    void withoutAnAuthenticatedActorTheInputNeverBecomesTheActor() {
        for (boolean legacy : new boolean[]{true, false}) {
            Fixture f = new Fixture(request -> legacy ? "unused" : null, legacy);
            assertInstanceOf(ConnectorResult.Success.class, f.call("collect", collection("PAY-1", "supervisor-1")));
            assertInstanceOf(ConnectorResult.Success.class, f.call("waive", waiver("W-1", "minister")));
            assertEquals(Set.of(BillingConnector.SYSTEM_ACTOR), f.actors(),
                    "an engine job or an unwired host posts as the connector, whoever the input names: " + f.postedBy);
            assertFalse(f.postedBy.isEmpty());
        }
        Fixture blank = new Fixture(request -> " ", false);
        assertInstanceOf(ConnectorResult.Success.class, blank.call("collect", collection("PAY-1", "supervisor-1")));
        assertEquals(Set.of(BillingConnector.SYSTEM_ACTOR), blank.actors(), "a blank principal is no principal");
    }

    @Test
    void aFailingActorSourceRefusesThePosting() {
        Fixture f = new Fixture(request -> {
            throw new IllegalStateException("identity provider unavailable");
        }, false);
        refused(BillingConnector.ACTOR_UNRESOLVED, f.call("collect", collection("PAY-1", null)));
        refused(BillingConnector.ACTOR_UNRESOLVED, f.call("waive", waiver("W-1", null)));
        assertTrue(f.postedBy.isEmpty(), f.postedBy.toString());
        assertEquals(0, f.outstanding().compareTo(new BigDecimal("100")));
        // reads record nobody and need no actor
        assertInstanceOf(ConnectorResult.Success.class, f.call("arrears", Map.of("consumer", "C-1")));
        assertThrows(NullPointerException.class, () -> new BillingConnector(null, null, null, null, null, null, null),
                "a host cannot pass no actor source by accident");
    }

    @Test
    void theReferencesAreRequiredBeforeTheRailIsAskedAndAPaymentIsReceiptedOnce() {
        Fixture f = new Fixture(request -> "cashier-7", false);
        Map<String, Object> noRef = collection("PAY-1", null);
        noRef.remove("paymentRef");
        refused("BILLING_ERROR", f.call("collect", noRef));
        Map<String, Object> blankRef = collection(" ", null);
        refused("BILLING_ERROR", f.call("collect", blankRef));
        Map<String, Object> noChannel = collection("PAY-1", null);
        noChannel.remove("channel");
        refused("BILLING_ERROR", f.call("collect", noChannel));
        Map<String, Object> noOrder = waiver("W-1", null);
        noOrder.remove("orderRef");
        refused("BILLING_ERROR", f.call("waive", noOrder));
        assertTrue(f.rail.confirmed.isEmpty(), "no rail call without a reference");
        assertTrue(f.postedBy.isEmpty(), f.postedBy.toString());

        ConnectorResult.Success first = assertInstanceOf(ConnectorResult.Success.class,
                f.call("collect", collection("PAY-1", null)));
        // the same reference again is the same receipt (a retry); for another amount it is refused
        ConnectorResult.Success again = assertInstanceOf(ConnectorResult.Success.class,
                f.call("collect", collection("PAY-1", null)));
        assertEquals(first.output().get("receiptNo"), again.output().get("receiptNo"));
        assertEquals(0, f.outstanding().compareTo(new BigDecimal("60")), "collected once");
        Map<String, Object> otherAmount = collection("PAY-1", null);
        otherAmount.put("amount", "55.00");
        refused("BILLING_ERROR", f.call("collect", otherAmount));
        assertEquals(0, f.outstanding().compareTo(new BigDecimal("60")));
    }
}
