// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.server.security.DevTokenFilter;
import org.sangam.server.security.JwtPrincipalConverter;
import org.sangam.server.security.PlatformIssuers;
import org.sangam.server.security.PrincipalAttributeFilter;
import org.sangam.server.security.ProblemResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
 *   <li>fine-grained checks happen in sangam-rest (PermissionPort) and in the adapters (VI.4 object checks);</li>
 *   <li>SL-70: platform identities only from {@code sangam.security.platform-issuer} ({@link PlatformIssuers}); the
 *       actuator (every tenant's metrics) only for platform identities.</li>
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
                                                         ObjectProvider<org.sangam.server.bridge.SecurityChainContributions> module,
                                                         ObjectProvider<org.operaton.bpm.engine.ProcessEngine> engine)
            throws Exception {
        ProblemResponses problems = new ProblemResponses();
        // decision 126 part 1: production requires signed audit checkpoints for every tenant (this chain is always on)
        AuditCheckpointWiring.requireInProduction(env);
        requireNoDevelopmentIssuer(props, env);
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
                        // SL-170 Event Grid deliveries (decisions 189, 219): public only because PortsEventBridge checks the
                        // channel's Entra token or shared secret first (401 when it fails); 404 unless enabled
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/hooks/eventgrid/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/api/v1/hooks/eventgrid/**").permitAll()
                        // Decision 304, DLT SMS delivery reports: public only because the port verifies the provider's
                        // X-Sangam-Signature over the raw body first (401 WEBHOOK_SIGNATURE_INVALID, nothing read or
                        // written); a connector without a configured signing secret refuses every report
                        .requestMatchers(org.springframework.http.HttpMethod.POST, DLT_DELIVERY_REPORTS).permitAll()
                        // CDS Hooks discovery and services (HL7 CDS Hooks paths, outside /api/v1). M-006 / SL-138: the
                        // caller is a CDS client (an EHR) with a JWT signed by its own key, which no Sangam decoder
                        // knows; HealthHooksController verifies it against the client's registered JWKS (iss, aud,
                        // exp, jti) and refuses every call when no client is registered. The bearer resolver hands
                        // no token from these paths to the Sangam JWT decoder (bearerResolver).
                        .requestMatchers(CDS_SERVICES_PATHS).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/compliance/rights-requests",
                                "/api/v1/compliance/consent-managers/*/artefacts").permitAll()
                        .requestMatchers("/engine-rest/**")
                        .hasAnyAuthority(org.sangam.rest.support.Permissions.ENGINE_REST_GROUPS.stream()
                                .map(g -> "GROUP_" + g).sorted().toArray(String[]::new))
                        // SL-70: the actuator shows every tenant's data, so only a platform identity (operator/admin)
                        .requestMatchers("/actuator/**")
                        .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                                org.springframework.security.authorization.AuthorityAuthorizationManager
                                        .hasAuthority("GROUP_" + org.sangam.rest.support.Permissions.GROUP_PLATFORM),
                                org.springframework.security.authorization.AuthorityAuthorizationManager
                                        .hasAnyAuthority("GROUP_sangam-operator", "GROUP_sangam-admin")))
                        // SCALE IR-4: the target cell pulls a tenant export with its one-time export token (checked
                        // by the controller); every other cell tenant-admin route needs the service role CELL_ADMIN.
                        // Cross-cell messages (IR-3) are checked for role XCELL by CellCrossCellInboxFilter.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/admin/cell/exports/*").permitAll()
                        .requestMatchers("/api/v1/admin/cell/**")
                        .hasAnyAuthority("GROUP_CELL_ADMIN", "GROUP_sangam-cell-admin")
                        .requestMatchers("/api/**").authenticated()
                        // SCIM 2.0 provisioning (RFC 7644 paths, outside /api/v1): a signed-in caller; the
                        // scim.provision permission is still checked inside ScimController
                        .requestMatchers("/scim/v2/**").authenticated()
                        // FWT-D11: the servlet error dispatch of a request that already passed this chain keeps its
                        // status for a signed-in caller (an uncaught 409/422 must not become 403 ACTION_NOT_PERMITTED);
                        // without authentication it stays 401. A direct request to /error is still denied below.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).authenticated()
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
                            .jwtAuthenticationConverter(new JwtPrincipalConverter(props.getClaims(), props.platformIssuers(),
                                    props.claimsProfileOrNull(), props.getMfaAcrValues()))));
            LOG.info("OIDC JWT authentication enabled (tenant claim '{}', claims profile {})", props.getClaims().getTenant(),
                    props.claimsProfileOrNull() == null ? "none" : props.claimsProfileOrNull().name());
        }
        if (props.platformIssuers().configured()) {
            LOG.info("SL-70 platform issuers: {} (only their tokens are platform identities)", props.platformIssuers().issuers());
        } else {
            LOG.warn("SL-70: no sangam.security.platform-issuer configured: nobody is a platform administrator");
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
        // SL-45: /engine-rest runs with the caller's engine authentication, so Operaton's tenant check applies
        http.addFilterAfter(new org.sangam.server.security.EngineRestTenantFilter(
                () -> engine.getIfAvailable() == null ? null : engine.getIfAvailable().getIdentityService(),
                () -> operatonAdminGroups(engine.getIfAvailable())), PrincipalAttributeFilter.class);
        return http.build();
    }

    private static java.util.Collection<String> operatonAdminGroups(org.operaton.bpm.engine.ProcessEngine engine) {
        if (engine != null && engine.getProcessEngineConfiguration()
                instanceof org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl c && c.getAdminGroups() != null) {
            return c.getAdminGroups();
        }
        return List.of();
    }

    /**
     * Decision 152: outside a development environment the development token issuer can never be trusted, also not
     * as a platform issuer (it would let a development-issued identity in through the JWT path).
     */
    static void requireNoDevelopmentIssuer(SangamSecurityProperties props, Environment env) {
        if (!ProductionEnvironment.developmentDefaultsAllowed(env)
                && props.platformIssuers().issuers().contains(org.sangam.server.security.PlatformIssuers.DEV_TOKEN_ISSUER)) {
            throw new IllegalStateException("sangam.security.platform-issuer lists the development token issuer "
                    + org.sangam.server.security.PlatformIssuers.DEV_TOKEN_ISSUER
                    + ", which is allowed only in a development environment (decision 152)");
        }
    }

    /** Returns the dev-token filter when enabled, after the safety checks; {@code null} otherwise. */
    static DevTokenFilter devTokenFilter(SangamSecurityProperties props, Environment env) {
        SangamSecurityProperties.DevToken cfg = props.getDevToken();
        if (!cfg.isEnabled()) {
            return null;
        }
        if (ProductionEnvironment.isProduction(env)) { // SL-109
            throw new IllegalStateException("sangam.security.dev-token.enabled=true is not allowed in production "
                    + "(sangam.security.production=true or a 'prod' profile)");
        }
        if (!ProductionEnvironment.developmentDefaultsAllowed(env)) {
            // decision 152: a static development identity only in a validated development or test environment
            throw new IllegalStateException("sangam.security.dev-token.enabled=true is allowed only in a development"
                    + " environment (sangam.security.production=false with a dev, h2, test or local profile;"
                    + " decisions 143 and 152)");
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
        return new DevTokenFilter(cfg, props.platformIssuers());
    }

    /** The HL7 CDS Hooks routes (server root): authenticated by the CDS client's own JWT in the controller (M-006). */
    static final String[] CDS_SERVICES_PATHS = {"/cds-services", "/cds-services/**"};

    private static final RequestMatcher CDS_SERVICES = new OrRequestMatcher(
            java.util.Arrays.stream(CDS_SERVICES_PATHS)
                    .map(p -> (RequestMatcher) PathPatternRequestMatcher.pathPattern(p)).toList());

    /** Decision 304: the signed delivery-report webhook of a tenant's DLT SMS connector (exactly this shape). */
    static final String DLT_DELIVERY_REPORTS = "/api/v1/hooks/sms-dlt/*/*/delivery-reports";

    /** SL-170: Event Grid hooks; their Authorization header is a Microsoft Entra delivery token, checked by the channel. */
    static final RequestMatcher EVENT_GRID_HOOKS = PathPatternRequestMatcher.pathPattern("/api/v1/hooks/eventgrid/**");

    /**
     * Keeps the dev token away from the JWT decoder when both modes are on. SL-138: on the CDS Hooks paths no token
     * goes to the decoder: the {@code Authorization} header there carries a CDS client's JWT, checked by the
     * controller's {@code CdsClientVerifier}; decoding it as a Sangam token would answer 401 before the controller.
     * The same holds for the SL-170 Event Grid hooks (an Entra delivery token, checked by the channel, decision 219).
     */
    static BearerTokenResolver bearerResolver(DevTokenFilter devToken) {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> {
            if (CDS_SERVICES.matches(request) || EVENT_GRID_HOOKS.matches(request)) {
                return null;
            }
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
