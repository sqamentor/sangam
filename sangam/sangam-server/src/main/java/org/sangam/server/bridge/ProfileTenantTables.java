// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.sangam.config.common.Finding;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.config.profile.ScopeRef;
import org.sangam.core.time.BusinessClock;
import org.sangam.packs.spi.ProfileTableDeployer.ProfileTableException;

/**
 * DEC-225-4: a tenant's own delegation tables are the {@code delegation.tables} of its configuration profile (the scope
 * is the tenant id, as for the case binding of {@code ConfigCaseBindingPlugin}), resolved on the tenant's business
 * date. A tenant id that is not a profile scope, or a scope without an active profile, has none. A profile chain with
 * errors is refused ({@code PROFILE_INVALID}): its tables are not deployed, and the tenant is not deployed on the
 * central tables in their place.
 */
public final class ProfileTenantTables implements PacksTenantDeployer.TenantTables {

    public static final String PATH = "delegation.tables";

    private final ProfileResolver resolver;
    private final BusinessClock clock;

    public ProfileTenantTables(ProfileResolver resolver, BusinessClock clock) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<Map<String, Object>> of(String tenantId) {
        try {
            ScopeRef.parse(tenantId);
        } catch (IllegalArgumentException notAScope) {
            return null;
        }
        ResolvedProfile p = resolver.resolve(tenantId, clock.today(tenantId));
        if (p.chain().isEmpty()) {
            return null;
        }
        if (p.hasErrors()) {
            throw new ProfileTableException("PROFILE_INVALID", "profile chain of tenant " + tenantId + " has errors: "
                    + Finding.errors(p.findings()).stream().map(Finding::code).distinct().sorted()
                            .collect(Collectors.joining(",")));
        }
        return p.list(PATH);
    }
}
