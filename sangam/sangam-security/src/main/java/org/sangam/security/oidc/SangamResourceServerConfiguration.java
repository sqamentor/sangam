// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import org.sangam.core.layer.Transactions;
import org.sangam.security.SangamSecurity;
import org.sangam.security.audit.LoginEventBatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security OAuth 2.0 resource-server configuration (R50 OIDC, R67; ARCHITECTURE.md §4 auth).
 *
 * <ul>
 *   <li>JWTs are validated against the issuer's JWKS (issuer, expiry/not-before with clock skew, audience, and a tenant
 *       claim). Keycloak realm/client roles and groups are mapped by {@link KeycloakClaimsMapper}.</li>
 *   <li>Sangam API tokens ({@code sgm_}) and dev static tokens (dev profile only) are handled by their own filters.</li>
 *   <li>{@link IdentityResolutionFilter} then resolves person, groups, roles and seats and binds the tenant.</li>
 *   <li>Stateless, no CSRF (bearer-only API). URL rules only require authentication; authorization is the VI.4
 *       policy in the services, never URL roles.</li>
 * </ul>
 * Required beans from {@code sangam-server}: {@link SangamSecurity}, {@link Transactions} on the product data source,
 * and {@link SecurityProperties} bound from {@code sangam.security.*}.
 */
@Configuration
@EnableWebSecurity
public class SangamResourceServerConfiguration {

    @Bean
    public KeycloakClaimsMapper keycloakClaimsMapper(SecurityProperties props) {
        return new KeycloakClaimsMapper(props);
    }

    @Bean(destroyMethod = "close")
    public LoginEventBatcher loginEventBatcher(Transactions tx, SangamSecurity security, SecurityProperties props) {
        return new LoginEventBatcher(tx, security.audit(), props.getLoginBatchMillis());
    }

    @Bean
    public JwtDecoder sangamJwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder;
        if (props.getJwkSetUri() != null) {
            decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwkSetUri()).build();
        } else if (props.getIssuerUri() != null) {
            decoder = NimbusJwtDecoder.withIssuerLocation(props.getIssuerUri()).build();
        } else {
            // no IdP configured (dev/H2): every JWT is rejected; dev static tokens and API tokens still work
            return token -> {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("no OIDC issuer configured");
            };
        }
        decoder.setJwtValidator(validator(props));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(SecurityProperties props) {
        List<OAuth2TokenValidator<Jwt>> v = new ArrayList<>();
        if (props.getIssuerUri() != null) {
            v.add(JwtValidators.createDefaultWithIssuer(props.getIssuerUri()));
        }
        v.add(new JwtTimestampValidator(Duration.ofSeconds(props.getClockSkewSeconds())));
        if (!props.getAudiences().isEmpty()) {
            v.add(new JwtClaimValidator<Object>(JwtClaimNames.AUD, aud -> audienceMatches(aud, props.getAudiences())));
        }
        String tenantClaim = props.getTenantClaim();
        v.add(new JwtClaimValidator<Object>(tenantClaim, t -> t != null && !t.toString().isBlank()));
        return new DelegatingOAuth2TokenValidator<>(v);
    }

    private static boolean audienceMatches(Object aud, List<String> allowed) {
        if (aud instanceof Collection<?> c) {
            return c.stream().anyMatch(a -> allowed.contains(String.valueOf(a)));
        }
        return aud != null && allowed.contains(aud.toString());
    }

    @Bean
    public SecurityFilterChain sangamSecurityFilterChain(HttpSecurity http, SecurityProperties props, JwtDecoder decoder,
                                                         KeycloakClaimsMapper mapper, Transactions tx, SangamSecurity security,
                                                         LoginEventBatcher logins) throws Exception {
        String[] open = props.getPublicPaths().toArray(String[]::new);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(open).permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(SecurityWebSupport.jwtOnlyResolver(props))
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(SecurityWebSupport.jwtConverter(mapper))))
                .addFilterBefore(new TokenAuthenticationFilters.ApiTokenFilter(tx, security.tokens()),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new IdentityResolutionFilter(tx, security.principals(), mapper, props, logins),
                        BearerTokenAuthenticationFilter.class);
        if (props.devTokensActive()) {
            http.addFilterBefore(new TokenAuthenticationFilters.DevStaticTokenFilter(props), BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }
}
