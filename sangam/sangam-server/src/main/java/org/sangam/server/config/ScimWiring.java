// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.Locale;

import org.sangam.security.SangamSecurity;
import org.sangam.security.scim.ScimService;
import org.sangam.security.scim.ScimStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires SCIM provisioning (M-099, owner decision 154): {@link ScimService} over the security module's
 * person, group, token and posting services, with {@link ScimStore} on {@code SEC_SCIM_RESOURCE}. The web adapter is
 * {@code web.ScimController}. On with the security module ({@code sangam.modules.security.enabled}); the identity
 * provider's service principal needs {@code scim.provision} at the endpoint and, for the object checks, USER and GROUP
 * ADMIN and READ plus API_TOKEN ADMIN (granted to its role by the tenant administrator).
 *
 * <p>{@code sangam.scim.subject-from} ({@code external-id}, the default, or {@code user-name}) says which SCIM attribute
 * is the person's OIDC subject, as {@code LdapDirectorySync} takes its id attribute.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.security.enabled", havingValue = "true", matchIfMissing = true)
public class ScimWiring {

    public static final String SUBJECT_FROM = "sangam.scim.subject-from";

    @Bean
    public ScimService sangamScimService(SangamSecurity security, Environment env) {
        return new ScimService(security.dialect(), security.authorization(), security.persons(), security.groups(),
                security.tokens(), security.postings(), new ScimStore(security.dialect()),
                new ScimService.Config(subjectFrom(env.getProperty(SUBJECT_FROM))));
    }

    /** Refuses an unknown value at start-up instead of guessing which attribute is the subject. */
    public static ScimService.SubjectFrom subjectFrom(String value) {
        if (value == null || value.isBlank()) {
            return ScimService.SubjectFrom.EXTERNAL_ID;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "external-id", "externalid" -> ScimService.SubjectFrom.EXTERNAL_ID;
            case "user-name", "username" -> ScimService.SubjectFrom.USER_NAME;
            default -> throw new IllegalStateException(SUBJECT_FROM + " must be external-id or user-name, not '" + value + "'");
        };
    }
}
