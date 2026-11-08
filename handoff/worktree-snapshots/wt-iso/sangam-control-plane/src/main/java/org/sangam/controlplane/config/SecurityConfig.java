// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Role model: ADMIN (tenant/cell administration, moves), CELL (register, heartbeat, tenant lookup), GATEWAY and ROLLUP
 * (route reads). Bearer tokens are compared in constant time. Actuator health is open for probes; everything else
 * needs a token. Integration with the national IdP (OIDC) for ADMIN is a listed gap (README).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain controlPlaneSecurity(HttpSecurity http, ControlPlaneProperties props) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new StaticTokenFilter(parse(props.tokens())), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/livez", "/readyz").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/cells/*").hasAnyRole("CELL", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/cells/*/heartbeat").hasAnyRole("CELL", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/cells", "/api/v1/cells/**")
                        .hasAnyRole("ADMIN", "GATEWAY", "CELL", "ROLLUP")
                        .requestMatchers(HttpMethod.GET, "/api/v1/routes/**").hasAnyRole("GATEWAY", "CELL", "ROLLUP", "ADMIN")
                        .anyRequest().denyAll());
        return http.build();
    }

    record TokenGrant(byte[] token, List<SimpleGrantedAuthority> roles, String name) {
    }

    static List<TokenGrant> parse(List<String> specs) {
        List<TokenGrant> out = new ArrayList<>();
        int n = 0;
        for (String spec : specs) {
            int i = spec.lastIndexOf(':');
            if (i <= 0) {
                throw new IllegalArgumentException("sangam.controlplane.tokens entry must be <token>:<ROLE>[,<ROLE>]");
            }
            List<SimpleGrantedAuthority> roles = Arrays.stream(spec.substring(i + 1).split(","))
                    .map(String::trim).filter(r -> !r.isEmpty()).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            out.add(new TokenGrant(spec.substring(0, i).getBytes(StandardCharsets.UTF_8), roles,
                    "token-" + (n++) + ":" + spec.substring(i + 1)));
        }
        return out;
    }

    static final class StaticTokenFilter extends OncePerRequestFilter {
        private final List<TokenGrant> grants;

        StaticTokenFilter(List<TokenGrant> grants) {
            this.grants = grants;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String h = req.getHeader("Authorization");
            if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
                byte[] presented = h.substring(7).trim().getBytes(StandardCharsets.UTF_8);
                for (TokenGrant g : grants) {
                    if (MessageDigest.isEqual(presented, g.token())) {
                        SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated(g.name(), null, g.roles()));
                        break;
                    }
                }
            }
            chain.doFilter(req, res);
        }
    }
}
