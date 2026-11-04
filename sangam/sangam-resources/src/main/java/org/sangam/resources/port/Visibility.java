// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.port;

import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;

import java.sql.Connection;
import java.util.List;

/**
 * Row-level visibility (VI.4 rules 1–3, A-3): one compiled SQL predicate per (identity, resource type, permission),
 * AND-ed into every list, count, search and get-by-id of this module by
 * {@link org.sangam.resources.support.VisibleQuery}. Object checks for mutations go through
 * {@link SecurityPorts.Authorization#require}, which runs the same predicate against the single row, so lists and object
 * checks cannot disagree. Shaped like sangam-security's {@code AuthorizedQueryBuilder}: no post-filtering, capped counts.
 *
 * <p>The predicate refers to the row through {@code alias}; the security {@code ResourceDescriptor} of each resource type
 * names the id and attribute columns (README, integration note 3).
 */
@FunctionalInterface
public interface Visibility {

    SqlPredicate visible(Connection c, SecurityIdentity actor, String resourceType, String permission, String alias);

    /** Stand-alone default: every row of the caller's tenant (the tenant is always bound by the query itself). */
    Visibility TENANT_ONLY = (c, a, t, p, alias) -> new SqlPredicate("1=1", List.of());

    static Visibility of(SecurityPorts.Authorization authz) {
        return authz::visible;
    }
}
