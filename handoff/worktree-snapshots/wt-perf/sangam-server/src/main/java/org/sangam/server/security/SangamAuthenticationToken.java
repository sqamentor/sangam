// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.util.Collection;
import java.util.List;

import org.sangam.rest.port.SangamPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring {@code Authentication} carrying the {@link SangamPrincipal}; authorities are {@code GROUP_<group>}. */
public final class SangamAuthenticationToken extends AbstractAuthenticationToken {

    private final SangamPrincipal principal;
    private final Object credentials;

    public SangamAuthenticationToken(SangamPrincipal principal, Object credentials) {
        super(authorities(principal));
        this.principal = principal;
        this.credentials = credentials;
        setAuthenticated(true);
    }

    public static Collection<GrantedAuthority> authorities(SangamPrincipal p) {
        return List.copyOf(p.groups().stream().map(g -> (GrantedAuthority) new SimpleGrantedAuthority("GROUP_" + g))
                .toList());
    }

    public SangamPrincipal sangamPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getPrincipal() {
        return principal.principalId();
    }

    @Override
    public String getName() {
        return principal.principalId();
    }
}
