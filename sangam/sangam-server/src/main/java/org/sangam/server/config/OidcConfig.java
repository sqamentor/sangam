// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nimbusds.jwt.JWTParser;
import org.sangam.server.security.PlatformIssuers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * OIDC JWT decoder with issuer, timestamp and <b>audience</b> validation ({@code SANGAM_SECURITY_OIDC_AUDIENCE},
 * default {@code sangam-server}). Created when {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} or
 * {@code jwk-set-uri} is set; Spring Boot's own decoder then backs off. The IdP metadata is resolved lazily, so the
 * server starts even while the IdP is still coming up.
 *
 * <p>SL-70: the platform issuers ({@code sangam.security.platform-issuer}) are separate realms. A token whose
 * {@code iss} names a platform issuer is verified <b>only</b> against that issuer's own keys (OIDC discovery, or
 * {@code sangam.security.platform-jwk-set-uri} when exactly one platform issuer is listed) and must pass the same
 * issuer, time and audience checks; every other token goes to the tenant decoder as before. So the platform role,
 * which {@code JwtPrincipalConverter} grants by issuer, cannot be obtained with a tenant realm's key. A platform
 * issuer equal to the tenant issuer is refused at startup (it would make every tenant token a platform token).
 */
@Configuration(proxyBeanMethods = false)
public class OidcConfig {

    private static final Logger LOG = LoggerFactory.getLogger(OidcConfig.class);

    static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    static final String JWKS = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";

    @Bean
    @Conditional(JwtConfigured.class)
    public JwtDecoder sangamJwtDecoder(Environment env, SangamSecurityProperties props) {
        String issuer = env.getProperty(ISSUER);
        String jwks = env.getProperty(JWKS);
        String audience = props.getClaims().getAudience();
        requireCompleteValidation(ProductionEnvironment.isProduction(env), issuer, audience);
        JwtDecoder tenant = decoder(issuer, jwks, audience);
        PlatformIssuers platform = props.platformIssuers();
        if (!platform.configured()) {
            return tenant;
        }
        String platformJwks = props.getPlatformJwkSetUri();
        if (StringUtils.hasText(platformJwks) && platform.issuers().size() != 1) {
            throw new IllegalStateException("sangam.security.platform-jwk-set-uri needs exactly one "
                    + "sangam.security.platform-issuer, found " + platform.issuers().size());
        }
        Map<String, JwtDecoder> decoders = new LinkedHashMap<>();
        for (String p : platform.issuers()) {
            if (StringUtils.hasText(issuer) && issuer.trim().equals(p)) {
                throw new IllegalStateException("sangam.security.platform-issuer must be a separate issuer (realm), not "
                        + "the tenant issuer " + issuer + " (SL-70): every tenant token would be a platform token");
            }
            if (p.startsWith("https://") || p.startsWith("http://")) {
                decoders.put(p, decoder(p, StringUtils.hasText(platformJwks) ? platformJwks : null, audience));
            }
        }
        return new IssuerRouting(tenant, platform, Map.copyOf(decoders));
    }

    /**
     * RM-09 (decisions 75 and 92): token validation is complete in production. Without an issuer ({@code jwk-set-uri}
     * only) the {@code iss} claim is not checked, and with a blank audience the {@code aud} claim is not checked; either
     * would accept a token minted for another issuer or another client. In production the start is refused; elsewhere a
     * WARN is logged once at start-up.
     *
     * @return the gaps found (empty when validation is complete), for the WARN and for tests
     */
    static List<String> requireCompleteValidation(boolean production, String issuer, String audience) {
        List<String> gaps = new ArrayList<>();
        if (!StringUtils.hasText(issuer)) {
            gaps.add(ISSUER + " is not set (" + JWKS + " only): the token issuer (iss) is not validated");
        }
        if (!StringUtils.hasText(audience)) {
            gaps.add("sangam.security.claims.audience is blank: the token audience (aud) is not validated");
        }
        if (gaps.isEmpty()) {
            return gaps;
        }
        if (production) {
            throw new IllegalStateException("OIDC token validation is incomplete in production (RM-09): "
                    + String.join("; ", gaps));
        }
        LOG.warn("OIDC token validation is incomplete (refused in production, RM-09): {}", String.join("; ", gaps));
        return gaps;
    }

    /** Issuer location (OIDC discovery) or JWKS, with the issuer, time and audience validators. */
    private static JwtDecoder decoder(String issuer, String jwks, String audience) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = StringUtils.hasText(jwks)
                    ? NimbusJwtDecoder.withJwkSetUri(jwks).build()
                    : NimbusJwtDecoder.withIssuerLocation(issuer).build();
            List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
            validators.add(StringUtils.hasText(issuer) ? JwtValidators.createDefaultWithIssuer(issuer)
                    : JwtValidators.createDefault());
            if (StringUtils.hasText(audience)) {
                validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(audience)));
            }
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        });
    }

    /**
     * Sends a token whose (unverified) {@code iss} is a platform issuer to that issuer's decoder, every other token to
     * the tenant decoder. The routing value is verified afterwards by the chosen decoder (signature with that issuer's
     * keys, then the issuer validator), so a forged {@code iss} only selects a decoder that rejects it. A platform
     * issuer that is not an OIDC URL (for example the dev token's) has no decoder: such a JWT is refused.
     */
    static final class IssuerRouting implements JwtDecoder {

        private final JwtDecoder tenant;
        private final PlatformIssuers platform;
        private final Map<String, JwtDecoder> platformDecoders;

        IssuerRouting(JwtDecoder tenant, PlatformIssuers platform, Map<String, JwtDecoder> platformDecoders) {
            this.tenant = tenant;
            this.platform = platform;
            this.platformDecoders = platformDecoders;
        }

        @Override
        public Jwt decode(String token) throws JwtException {
            String iss;
            try {
                iss = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
            } catch (ParseException e) {
                throw new BadJwtException("Malformed token", e);
            }
            if (!platform.isPlatform(iss)) {
                return tenant.decode(token);
            }
            JwtDecoder d = platformDecoders.get(iss.trim());
            if (d == null) {
                throw new BadJwtException("The token's issuer is not an OIDC platform issuer");
            }
            return d.decode(token);
        }
    }

    static final class JwtConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return StringUtils.hasText(env.getProperty(ISSUER)) || StringUtils.hasText(env.getProperty(JWKS));
        }
    }
}
