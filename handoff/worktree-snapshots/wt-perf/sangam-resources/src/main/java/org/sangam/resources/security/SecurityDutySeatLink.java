// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.security;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.security.org.PostingService;

import java.sql.Connection;
import java.time.Instant;

/**
 * Duty => seat link over sangam-security postings: for the duty window the person on duty holds the duty seat by
 * additional charge (IX.5 rule 4, "acted for" in audit), so seat-assigned tasks route to whoever is on shift. The
 * posting is created and ended in the caller's transaction; authorization and audit are done by {@link PostingService}.
 */
public final class SecurityDutySeatLink implements ResourcesPorts.DutySeatLink {

    private final PostingService postings;

    public SecurityDutySeatLink(PostingService postings) {
        this.postings = postings;
    }

    @Override
    public String link(Connection c, SecurityIdentity actor, String dutySeatId, String personId, Instant from, Instant to,
                       String orderRef) {
        return postings.grantAdditionalCharge(c, actor, dutySeatId, personId, from, to, orderRef, null).postingId();
    }

    @Override
    public void unlink(Connection c, SecurityIdentity actor, String postingRef, Instant at) {
        postings.endPosting(c, actor, postingRef, at);
    }
}
