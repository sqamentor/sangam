// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The verified payee of a payment ([CONTRACT] SANGAM-PAYEE-VERIFIED v2, JOINT-LEDGER-SANGAM-IFMS; E-31 §6, SD-107):
 * bank details are never typed by the DDO. The payment step asks this port for the current verified payee version in
 * force on the payment date and records its {@code payeeVersionId} on the case. IFMS implements it against its MDM
 * payee API (mdm.payee, E-02 P02-FR-004/005); {@link VerifiedPayees} holds the installed adapter.
 */
public interface VerifiedPayeeLookup {

    /**
     * Exactly one of {@code employeeId} / {@code payeeId} is non-blank. {@code asOf}: the payment date of the case
     * (never null).
     */
    Result resolve(String tenantId, String employeeId, String payeeId, LocalDate asOf);

    /** The answer: {@link Verified} or {@link NotVerified}. */
    sealed interface Result permits Verified, NotVerified {
    }

    /** The current verified payee version in force on {@code asOf}. */
    record Verified(String payeeVersionId, String name, String accountNo, String ifsc) implements Result {
        public Verified {
            Objects.requireNonNull(payeeVersionId, "payeeVersionId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(accountNo, "accountNo");
            Objects.requireNonNull(ifsc, "ifsc");
        }
    }

    /** No verified version: the case stops at a person with {@link #reason()} as its reason code. */
    record NotVerified(Reason reason, String detail) implements Result {
        public NotVerified {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** ONBOARDING_INCOMPLETE: no payee record; PAYEE_NOT_VERIFIED: a record without a verified version in force. */
    enum Reason { ONBOARDING_INCOMPLETE, PAYEE_NOT_VERIFIED }
}
