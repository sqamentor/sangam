// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import org.sangam.core.security.SecurityIdentity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security authentication carrying the Sangam identity. Before {@code IdentityResolutionFilter} runs it holds
 * the token identity ({@link #resolved()} false); afterwards the full identity with person, groups, roles and seats.
 * Authorities ({@code ROLE_<role>}) are informational only: Sangam authorization is the VI.4 policy, never URL roles.
 */
public final class SangamAuthenticationToken extends AbstractAuthenticationToken {

    private final SecurityIdentity identity;
    private final Object credentials;
    private final boolean resolved;

    public SangamAuthenticationToken(SecurityIdentity identity, Object credentials, boolean resolved) {
        super(authorities(identity));
        this.identity = identity;
        this.credentials = credentials;
        this.resolved = resolved;
        setAuthenticated(true);
    }

    public SecurityIdentity identity() {
        return identity;
    }

    public boolean resolved() {
        return resolved;
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getPrincipal() {
        return identity;
    }

    @Override
    public String getName() {
        return identity.personId();
    }

    private static Collection<? extends GrantedAuthority> authorities(SecurityIdentity id) {
        List<GrantedAuthority> out = new ArrayList<>();
        id.roles().forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
        return out;
    }
}
