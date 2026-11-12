// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nimbusds.jwt.JWTParser;
import org.sangam.server.security.PlatformIssuers;
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

    static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    static final String JWKS = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";

    @Bean
    @Conditional(JwtConfigured.class)
    public JwtDecoder sangamJwtDecoder(Environment env, SangamSecurityProperties props) {
        String issuer = env.getProperty(ISSUER);
        String jwks = env.getProperty(JWKS);
        String audience = props.getClaims().getAudience();
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
