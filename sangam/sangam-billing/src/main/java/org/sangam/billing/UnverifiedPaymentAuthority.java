// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

/**
 * The authorisation port of unverified payments (RM-03, owner decision 141), implemented on the existing
 * authorisation model (sangam-security) and bound by the host. Billing asks it for every acceptance and every
 * resolution, and again inside the transition under the record's row lock.
 *
 * <p>Actions are explicit and scoped to (tenant, office or counter, payment channel): {@link #ACCEPT_UNVERIFIED} and
 * {@link #RESOLVE_UNVERIFIED}. A generic billing or channel permission never implies them. An implementation answers
 * {@code false} for "no" and throws on a failed evaluation; billing treats both as a refusal. Billing refuses a caller
 * that is not signed-in staff before it asks ({@link Caller#actorType}, decision 201).
 */
public interface UnverifiedPaymentAuthority {

    /** Accept a payment its channel has not confirmed. */
    String ACCEPT_UNVERIFIED = "ACCEPT_UNVERIFIED";

    /** Resolve a pending one to VERIFIED, REJECTED or REVERSED. */
    String RESOLVE_UNVERIFIED = "RESOLVE_UNVERIFIED";

    /**
     * What kind of identity the server authenticated (owner decision 201). Only {@link #STAFF}, a person's interactive
     * sign-in, may accept or resolve an unverified payment; API keys, service identities and the system identity are
     * refused (rail reconciliation runs as the system job and resolves through the rail, not as a caller).
     */
    enum ActorType {
        /** A member of staff signed in interactively (OIDC or SAML). */
        STAFF,
        /** An API key. */
        API_KEY,
        /** A service or machine identity, or any sign-in the host cannot show to be a person's. */
        SERVICE,
        /** The system identity (packs, jobs, connectors). */
        SYSTEM
    }

    /**
     * The caller as the server authenticated it. Only a host adapter that holds a verified identity builds it (the REST
     * adapter from its request context); no request field, pack variable or connector input names it, and pack steps,
     * connectors and jobs have none, so they can never accept or resolve an unverified payment.
     *
     * @param actorType how the server authenticated the caller; anything but {@link ActorType#STAFF} is refused
     */
    record Caller(String principalId, String tenant, String seatId, ActorType actorType) {
        public Caller {
            if (principalId == null || principalId.isBlank() || tenant == null || tenant.isBlank()) {
                throw new IllegalArgumentException("an authenticated caller has a principal and a tenant");
            }
            if (actorType == null) {
                actorType = ActorType.SERVICE;
            }
        }

        /** A caller whose kind of sign-in is not stated: treated as {@link ActorType#SERVICE}, so it is refused. */
        public Caller(String principalId, String tenant, String seatId) {
            this(principalId, tenant, seatId, ActorType.SERVICE);
        }

        /** A member of staff signed in interactively. */
        public static Caller staff(String principalId, String tenant, String seatId) {
            return new Caller(principalId, tenant, seatId, ActorType.STAFF);
        }
    }

    /** Whether {@code caller} holds {@code action} on (tenant, office, channel). */
    boolean permits(Caller caller, String action, String tenant, String office, String channel);

    /** Nothing bound: every unverified acceptance and resolution is refused. */
    UnverifiedPaymentAuthority NONE = (caller, action, tenant, office, channel) -> false;
}
