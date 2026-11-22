// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.port.SeatHoldings;
import org.sangam.security.SangamSecurity;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.server.security.PrincipalAttributeFilter;
import org.slf4j.LoggerFactory;

/**
 * {@code AUTH_DENIED} for a refused seat context (SANGAM.md X.2.3 identity events, CX-1, J-P1-E05 "AUTH_DENIED (if old
 * holder tries)"): action (method and path), the named seat and acted-for seat, the person, reason
 * {@code SEAT_NOT_HELD}; the policy version is added by the audit service. It goes to the denial chain of
 * {@code sec:<tenant>} and commits on its own. Deny-only: when the record cannot be written the refusal stands and the
 * failure is logged.
 *
 * <p>One writer for both places a seat is refused: {@link SecurityIdentityFilter} (the person does not hold the seat
 * now, K7) and, register E-32(g), {@link PrincipalAttributeFilter} (the token's seats claim does not name it), which
 * runs before the identity is resolved and therefore records the identity as the token gives it.
 */
public final class SeatDenialAudit implements PrincipalAttributeFilter.SeatRefusal {

    private final Transactions tx;
    private final SangamSecurity security;

    public SeatDenialAudit(Transactions tx, SangamSecurity security) {
        this.tx = tx;
        this.security = security;
    }

    public void record(SecurityIdentity who, HttpServletRequest req, String seat, String actingFor) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", req.getMethod() + " " + req.getRequestURI());
        p.put("seat_id", seat);
        p.put("acted_for_seat_id", actingFor);
        p.put("person_id", who.personId());
        p.put("outcome", "DENIED");
        p.put("reason", SeatHoldings.NOT_HELD);
        try {
            tx.inTransaction(c -> {
                security.audit().denial(c, who, SecurityEventType.AUTH_DENIED, null, p);
                return null;
            });
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(SeatDenialAudit.class).warn(
                    "the AUTH_DENIED record of a refused seat context could not be written: {}", e.toString());
        }
    }

    @Override
    public void refused(HttpServletRequest request, SangamPrincipal principal, String seat, String actingFor) {
        record(SecurityIdentities.fromPrincipal(principal, request.getRemoteAddr()), request, seat, actingFor);
    }
}
