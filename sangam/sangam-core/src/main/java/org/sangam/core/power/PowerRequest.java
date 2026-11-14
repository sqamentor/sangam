// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import java.math.BigDecimal;

/**
 * What is asked of the power-limit evaluator (owner decision 142).
 *
 * <ul>
 *   <li>{@code actor}: the authenticated server-side identity (person or seat holder); never taken from a payload,
 *       a process variable or a pack input. The caller is responsible for resolving it.</li>
 *   <li>{@code action}, {@code tenant}, {@code office}: what is done and its scope (office or unit may be null when the
 *       operation has no office scope).</li>
 *   <li>{@code caseType}, {@code caseTypeVersion}: the operation's case type as stored with the case, the key into the
 *       trusted {@link OperationMetadata}; there is deliberately no "monetary" flag here.</li>
 *   <li>{@code amount}, {@code currency}: the authoritative amount as an exact decimal and its ISO 4217 code, read from
 *       the stored case or transaction, not from the request body. Amount basis of contract version 1: the gross
 *       transaction amount of this single operation; a negative amount (reversal or refund) is compared by its
 *       magnitude, so it never bypasses a limit. Aggregate limits (per period, per payee) are not part of this
 *       contract version and must not be expressed through it.</li>
 *   <li>{@code stateVersion}: the version of the authoritative case or transaction state the amount was read from
 *       (row version, sequence). A change between evaluation and application forces a new evaluation.</li>
 * </ul>
 */
public record PowerRequest(String actor, String action, String tenant, String office, String caseType,
                           int caseTypeVersion, BigDecimal amount, String currency, long stateVersion) {

    /** The same request with the authoritative amount, currency and state version re-read at application time. */
    public PowerRequest withState(BigDecimal newAmount, String newCurrency, long newStateVersion) {
        return new PowerRequest(actor, action, tenant, office, caseType, caseTypeVersion, newAmount, newCurrency,
                newStateVersion);
    }
}
