// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.security.oidc.SecurityProperties;
import org.sangam.security.saml.SamlAttributeMapper;
import org.sangam.security.saml.SamlProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * M-076 host side of the optional SAML 2.0 SP adapter (owner decisions 52 and 73). Off unless
 * {@code sangam.security.saml.enabled=true}; the documented default is Keycloak brokering
 * ({@code deploy/compose/keycloak/README.md}). The plain logic (settings, assertion-to-identity mapping through the
 * claims profile) is in {@code sangam-security}; the Spring Security SAML filter chain belongs here.
 *
 * <p>Fail closed: an enabled adapter refuses to start when the registration is incomplete, when
 * {@code spring-security-saml2-service-provider} (with OpenSAML) is not on the class path, and, until the relying-party
 * filter chain is built (BLOCKED on the approved download of those libraries), always: an enabled adapter that cannot
 * authenticate must never start as if it could.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.security.saml.enabled", havingValue = "true")
public class SamlWiring {

    static final String PREFIX = "sangam.security.saml";
    /** Present when spring-security-saml2-service-provider is on the class path. */
    static final String SP_LIBRARY_CLASS =
            "org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal";

    static SamlProperties bind(Environment env) {
        SamlProperties p = Binder.get(env).bind(PREFIX, SamlProperties.class).orElseGet(SamlProperties::new);
        p.setEnabled(true);
        return p;
    }

    /** Refuses the start when the adapter cannot run; returns the validated settings otherwise. */
    static SamlProperties checked(SamlProperties p, ClassLoader loader) {
        p.validate();
        if (!ClassUtils.isPresent(SP_LIBRARY_CLASS, loader)) {
            throw new IllegalStateException(PREFIX + ".enabled=true needs spring-security-saml2-service-provider and"
                    + " OpenSAML on the class path; use Keycloak brokering (the default) or add them");
        }
        throw new IllegalStateException(PREFIX + ".enabled=true: the SAML relying-party filter chain is not part of this"
                + " build yet; use Keycloak brokering (the default)");
    }

    /** The tenant claim and MFA acr values the mapper reads; the server's token settings bind the same keys. */
    static SecurityProperties oidc(Environment env) {
        return Binder.get(env).bind("sangam.security", SecurityProperties.class).orElseGet(SecurityProperties::new);
    }

    @Bean
    public SamlAttributeMapper sangamSamlAttributeMapper(Environment env) {
        return new SamlAttributeMapper(checked(bind(env), SamlWiring.class.getClassLoader()), oidc(env));
    }
}
