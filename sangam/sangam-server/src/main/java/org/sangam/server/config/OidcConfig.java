// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * OIDC JWT decoder with issuer, timestamp and <b>audience</b> validation ({@code SANGAM_SECURITY_OIDC_AUDIENCE},
 * default {@code sangam-server}). Created when {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} or
 * {@code jwk-set-uri} is set; Spring Boot's own decoder then backs off. The IdP metadata is resolved lazily, so the
 * server starts even while the IdP is still coming up.
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

    static final class JwtConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return StringUtils.hasText(env.getProperty(ISSUER)) || StringUtils.hasText(env.getProperty(JWKS));
        }
    }
}
