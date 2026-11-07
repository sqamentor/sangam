// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.server.security.DevTokenFilter;
import org.sangam.server.security.JwtPrincipalConverter;
import org.sangam.server.security.PrincipalAttributeFilter;
import org.sangam.server.security.ProblemResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Sangam authentication and coarse authorization (ARCHITECTURE section 4 "Auth"):
 * <ul>
 *   <li>OIDC JWT resource server when {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} (or
 *       {@code jwk-set-uri}) is set; tenant from the {@code tenant} claim;</li>
 *   <li>a development static token when {@code sangam.security.dev-token.enabled=true} (never on {@code prod});</li>
 *   <li>Operaton REST ({@code /engine-rest/**}) only for engine administrators; Operaton's own authorization stays off
 *       (VI.4: product authorization, deny wins);</li>
 *   <li>fine-grained checks happen in sangam-rest (PermissionPort) and in the adapters (VI.4 object checks).</li>
 * </ul>
 * Stateless, no sessions, no CSRF (bearer tokens only). Errors are RFC 9457 problem+json.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SangamSecurityProperties.class)
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);
    private static final int MIN_DEV_TOKEN_LENGTH = 16;

    @Bean
    public SecurityFilterChain sangamSecurityFilterChain(HttpSecurity http, SangamSecurityProperties props,
                                                         ObjectProvider<JwtDecoder> jwtDecoder, Environment env,
                                                         ObjectProvider<org.sangam.server.bridge.SecurityChainContributions> module)
            throws Exception {
        ProblemResponses problems = new ProblemResponses();
        DevTokenFilter devToken = devTokenFilter(props, env);
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (devToken == null && decoder == null) {
            LOG.warn("No authentication is configured (neither OIDC issuer nor dev token): every protected request gets 401");
        }

        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/livez", "/readyz", "/api/v1/openapi.yaml").permitAll()
                        // verified UI plugin assets: same-origin, loaded by module import (no secrets, plugin guide)
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/plugins/ui/*/assets/**").permitAll()
                        // Public compliance endpoints (DPDP: notices and privacy policy are published; rights requests
                        // authenticate by the published identifier; consent-manager artefacts need mTLS at the ingress).
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/compliance/notices/**",
                                "/api/v1/compliance/privacy-policy", "/api/v1/compliance/jurisdiction").permitAll()
                        // FHIR subscription notifications: authenticated by the channel secret in the receiver
                        // (sangam-connectors-health), not by a Sangam token
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/hooks/fhir/**").permitAll()
                        // Channel webhooks: public only because the controller verifies the provider signature first
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/hooks/channels/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/hooks/channels/**").permitAll()
                        // CDS Hooks discovery and services (HL7 CDS Hooks paths, outside /api/v1): signed-in callers
                        .requestMatchers("/cds-services", "/cds-services/**").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/compliance/rights-requests",
                                "/api/v1/compliance/consent-managers/*/artefacts").permitAll()
                        .requestMatchers("/engine-rest/**")
                        .hasAnyAuthority("GROUP_sangam-admin", "GROUP_sangam-engine-admin")
                        .requestMatchers("/actuator/**")
                        .hasAnyAuthority("GROUP_sangam-operator", "GROUP_sangam-admin")
                        // SCALE IR-4: the target cell pulls a tenant export with its one-time export token (checked
                        // by the controller); every other cell tenant-admin route needs the service role CELL_ADMIN.
                        // Cross-cell messages (IR-3) are checked for role XCELL by CellCrossCellInboxFilter.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/admin/cell/exports/*").permitAll()
                        .requestMatchers("/api/v1/admin/cell/**")
                        .hasAnyAuthority("GROUP_CELL_ADMIN", "GROUP_sangam-cell-admin")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll());

        List<String> origins = props.getCors().getAllowedOrigins();
        if (origins != null && !origins.isEmpty()) {
            http.cors(c -> c.configurationSource(corsSource(origins)));
        } else {
            http.cors(c -> c.disable());
        }

        if (devToken != null) {
            http.addFilterBefore(devToken, BearerTokenAuthenticationFilter.class);
        }
        if (decoder != null) {
            BearerTokenResolver resolver = bearerResolver(devToken);
            http.oauth2ResourceServer(o -> o
                    .bearerTokenResolver(resolver)
                    .authenticationEntryPoint(problems)
                    .accessDeniedHandler(problems)
                    .jwt(j -> j.decoder(decoder)
                            .jwtAuthenticationConverter(new JwtPrincipalConverter(props.getClaims()))));
            LOG.info("OIDC JWT authentication enabled (tenant claim '{}')", props.getClaims().getTenant());
        }
        http.addFilterAfter(new PrincipalAttributeFilter(), AuthorizationFilter.class);
        org.sangam.server.bridge.SecurityChainContributions contributions = module.getIfAvailable();
        if (contributions != null) {
            // sangam-security in the same (single) chain: API tokens (sgm_..., R26) before bearer/JWT authentication;
            // identity resolution against the organisation model + TenantContext after the principal attribute is set.
            http.addFilterBefore(contributions.apiTokenFilter(), BearerTokenAuthenticationFilter.class);
            http.addFilterAfter(contributions.identityFilter(), PrincipalAttributeFilter.class);
            LOG.info("sangam-security filters added to the Sangam filter chain (API tokens, identity resolution)");
        }
        return http.build();
    }

    /** Returns the dev-token filter when enabled, after the safety checks; {@code null} otherwise. */
    static DevTokenFilter devTokenFilter(SangamSecurityProperties props, Environment env) {
        SangamSecurityProperties.DevToken cfg = props.getDevToken();
        if (!cfg.isEnabled()) {
            return null;
        }
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("sangam.security.dev-token.enabled=true is not allowed with the 'prod' profile");
        }
        if (cfg.getToken() == null || cfg.getToken().length() < MIN_DEV_TOKEN_LENGTH) {
            throw new IllegalStateException("sangam.security.dev-token.token must be at least " + MIN_DEV_TOKEN_LENGTH
                    + " characters");
        }
        if (cfg.getTenant() == null || cfg.getTenant().isBlank()) {
            // SANGAM_TENANT_DEFAULT is a fallback for the dev token ONLY; a JWT without a tenant claim stays 401.
            cfg.setTenant(env.getProperty("sangam.tenant.default"));
        }
        if (cfg.getTenant() == null || cfg.getTenant().isBlank()) {
            throw new IllegalStateException("sangam.security.dev-token.tenant (or SANGAM_TENANT_DEFAULT) is required");
        }
        LOG.warn("DEVELOPMENT static token authentication is ENABLED (tenant '{}', principal '{}'); never use in production",
                cfg.getTenant(), cfg.getPrincipal());
        return new DevTokenFilter(cfg);
    }

    /** Keeps the dev token away from the JWT decoder when both modes are on. */
    private static BearerTokenResolver bearerResolver(DevTokenFilter devToken) {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> {
            String token = delegate.resolve(request);
            if (token == null || token.startsWith(API_TOKEN_PREFIX)) {
                return null; // sangam-security API tokens are not JWTs (handled by its ApiTokenFilter)
            }
            return devToken != null && devToken.matches(token) ? null : token;
        };
    }

    /** sangam-security {@code ApiTokenService.PREFIX}. */
    static final String API_TOKEN_PREFIX = "sgm_";

    private static UrlBasedCorsConfigurationSource corsSource(List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match",
                "X-Sangam-Seat", "X-Sangam-Acting-For-Seat", "traceparent", "Prefer"));
        cors.setExposedHeaders(List.of("X-Total-Count", "X-Total-Count-Capped", "Idempotent-Replayed",
                "Idempotency-Key-Expires", "ETag", "Location", "Retry-After"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
