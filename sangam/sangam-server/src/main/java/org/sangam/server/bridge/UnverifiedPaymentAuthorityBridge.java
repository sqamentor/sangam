// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Optional;

import org.sangam.billing.UnverifiedPaymentAuthority;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.billing.UnverifiedPaymentPermissions;
import org.sangam.security.policy.Permission;
import org.sangam.security.tenant.TenantContext;

/**
 * Decision 141: sangam-billing's {@link UnverifiedPaymentAuthority} on sangam-security
 * ({@link UnverifiedPaymentPermissions}). The identity evaluated is the one the server resolved for this request
 * ({@link TenantContext}, bound by {@link SecurityIdentityFilter}); the {@link UnverifiedPaymentAuthority.Caller} only
 * has to match it (tenant, and principal as person id or subject; a named seat must be one the person holds now).
 * No resolved identity (a pack, connector, job or other thread), a mismatch, the system identity or an API key: refused.
 * Each call re-reads the grants without cache (sensitive actions), so the re-check inside the transition is current.
 */
public final class UnverifiedPaymentAuthorityBridge implements UnverifiedPaymentAuthority {

    private final UnverifiedPaymentPermissions permissions;
    private final TxSupport tx;

    public UnverifiedPaymentAuthorityBridge(SangamSecurity security, TxSupport tx) {
        this(new UnverifiedPaymentPermissions(security.authorization()), tx);
    }

    UnverifiedPaymentAuthorityBridge(UnverifiedPaymentPermissions permissions, TxSupport tx) {
        this.permissions = permissions;
        this.tx = tx;
    }

    @Override
    public boolean permits(Caller caller, String action, String tenant, String office, String channel) {
        Optional<Permission> p = UnverifiedPaymentPermissions.action(action);
        if (caller == null || p.isEmpty()) {
            return false;
        }
        SecurityIdentity who = requestIdentity(caller).orElse(null);
        if (who == null) {
            return false;
        }
        return tx.withConnection(c -> permissions.permits(c, who, p.get(), tenant, office, channel, null));
    }

    /** The request's resolved identity when it is the caller's; empty otherwise. */
    static Optional<SecurityIdentity> requestIdentity(Caller caller) {
        SecurityIdentity current = TenantContext.current().orElse(null);
        if (current == null || current.system() || !caller.tenant().equals(current.tenantId())) {
            return Optional.empty();
        }
        if (!caller.principalId().equals(current.personId()) && !caller.principalId().equals(current.subject())) {
            return Optional.empty();
        }
        if (caller.seatId() != null && !caller.seatId().isBlank() && !current.seats().contains(caller.seatId())) {
            return Optional.empty();
        }
        return Optional.of(current);
    }
}
