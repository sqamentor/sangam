// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import liquibase.integration.spring.SpringLiquibase;

import org.springframework.beans.factory.annotation.Value;
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

/** Schema (own changelog, five dialects) and token security (CELL ingests, DASHBOARD reads). */
@Configuration(proxyBeanMethods = false)
public class RollupConfig {

    @Bean
    public SpringLiquibase rollupLiquibase(DataSource dataSource, @Value("${sangam.rollup.schema.apply:true}") boolean apply) {
        org.sangam.core.licence.LiquibaseLicenceGuard.check(); // SG-26 / LIC-7
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(dataSource);
        lb.setChangeLog("classpath:db/changelog/rollup/ru-master.xml");
        lb.setShouldRun(apply);
        return lb;
    }

    @Bean
    public SecurityFilterChain rollupSecurity(HttpSecurity http, RollupProperties props) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new TokenFilter(parse(props.tokens())), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/livez", "/readyz").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ingest").hasAnyRole("CELL", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/rollup/**").hasAnyRole("DASHBOARD", "ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().denyAll());
        return http.build();
    }

    record Grant(byte[] token, List<SimpleGrantedAuthority> roles, String name) {
    }

    static List<Grant> parse(List<String> specs) {
        List<Grant> out = new ArrayList<>();
        int n = 0;
        for (String spec : specs) {
            int i = spec.lastIndexOf(':');
            if (i <= 0) {
                throw new IllegalArgumentException("sangam.rollup.tokens entry must be <token>:<ROLE>[,<ROLE>]");
            }
            out.add(new Grant(spec.substring(0, i).getBytes(StandardCharsets.UTF_8),
                    Arrays.stream(spec.substring(i + 1).split(",")).map(String::trim).filter(r -> !r.isEmpty())
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList(),
                    "token-" + (n++) + ":" + spec.substring(i + 1)));
        }
        return out;
    }

    static final class TokenFilter extends OncePerRequestFilter {
        private final List<Grant> grants;

        TokenFilter(List<Grant> grants) {
            this.grants = grants;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String h = req.getHeader("Authorization");
            if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
                byte[] p = h.substring(7).trim().getBytes(StandardCharsets.UTF_8);
                for (Grant g : grants) {
                    if (MessageDigest.isEqual(p, g.token())) {
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
