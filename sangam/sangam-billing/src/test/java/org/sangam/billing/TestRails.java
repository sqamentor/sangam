// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.core.money.Money;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Payment rails for billing tests. Since RM-02 a {@link BillingService} without a payment port refuses every collection,
 * so tests that record collections bind a rail that confirms through the one rule,
 * {@link PaymentConfirmationPolicy#confirmedByRail} (success status and the reported amount), exactly as a real rail
 * that settled the payment in full would.
 */
final class TestRails {

    private TestRails() {
    }

    /** A rail that settled every reported payment in full; records the confirmed payment references. */
    static final class Settling implements BillingService.PaymentPort {
        final List<String> confirmed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Money confirm(String tenant, String channel, String paymentRef, Money amount) {
            confirmed.add(paymentRef);
            return PaymentConfirmationPolicy.confirmedByRail(true, amount.toPlainString(), amount);
        }

        @Override
        public String refund(String tenant, String channel, String paymentRef, Money amount, String operationId) {
            return "rail:" + operationId;
        }
    }

    /** A rail that settles every reported payment in full. */
    static BillingService.PaymentPort settling() {
        return new Settling();
    }
}
