// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.support.PackageSigning;
import org.sangam.server.web.PromotionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-074 (R43, X.2.4.5, owner Q-04): the executable package signing key of this environment, the keys it trusts and
 * the unsigned-package rule, for {@code JdbcPackageAdapter} ({@link PackageSigning}).
 *
 * <ul>
 *   <li>{@code sangam.packages.signing.private-key-ref} and {@code .public-key-ref}: secret references
 *       ({@code secret://...}, resolved through the {@link SecretResolver}) to this environment's package signing key,
 *       base64 PKCS#8 and X.509 Ed25519. A key of its own per purpose and environment (Q-04: not the audit checkpoint
 *       key, not the onboarding bundle key); the custodian is named later, so the shipped configuration sets none.
 *       Unset: nothing is signed here.</li>
 *   <li>{@code sangam.packages.signing.trusted-public-key-refs}: a comma list of secret references to the public keys
 *       of the environments this one imports from (and retired keys still trusted). The environment's own public key
 *       is always trusted.</li>
 *   <li>Unsigned packages are allowed only on the {@code h2}/{@code dev} profiles outside production (the rule of
 *       {@code OnboardingWiring} for unsigned bundles; SL-109 production predicate).</li>
 *   <li>The environment label is {@code sangam.onboarding.environment}, the one label the server already signs into
 *       promotion bundles (no second label).</li>
 * </ul>
 * A literal key instead of a reference, or a reference that does not resolve, fails the start-up.
 */
@Configuration(proxyBeanMethods = false)
public class PackageSigningWiring {

    private static final Logger LOG = LoggerFactory.getLogger(PackageSigningWiring.class);
    static final String PREFIX = "sangam.packages.signing.";

    @Bean
    public PackageSigning sangamPackageSigning(ObjectProvider<SecretResolver> secrets, Environment env) {
        return signing(env, secrets.getIfAvailable(SecretResolver::fromEnvironment));
    }

    static PackageSigning signing(Environment env, SecretResolver secrets) {
        boolean production = ProductionEnvironment.isProduction(env);
        boolean dev = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("h2") || p.equals("dev"));
        String privateRef = env.getProperty(PREFIX + "private-key-ref");
        List<String> trusted = new ArrayList<>();
        AuditCheckpoints.Signer signer = null;
        if (privateRef != null && !privateRef.isBlank()) {
            String publicKey = resolve(secrets, PREFIX + "public-key-ref", env.getProperty(PREFIX + "public-key-ref"));
            signer = AuditCheckpoints.ed25519(resolve(secrets, PREFIX + "private-key-ref", privateRef), publicKey);
            trusted.add(publicKey);
        }
        for (String ref : env.getProperty(PREFIX + "trusted-public-key-refs", "").split(",")) {
            if (!ref.isBlank()) {
                trusted.add(resolve(secrets, PREFIX + "trusted-public-key-refs", ref.trim()));
            }
        }
        PackageSigning s = new PackageSigning(signer, AuditCheckpoints.ed25519Keys(trusted), dev && !production, production,
                env.getProperty(PromotionController.ENVIRONMENT_PROPERTY));
        LOG.info("M-074 package signing: key {}, {} trusted key(s), unsigned packages {}",
                signer == null ? "none" : signer.keyId(), trusted.size(), s.allowUnsigned() ? "allowed (development)" : "refused");
        return s;
    }

    /** The value behind a key reference; refuses a missing reference or a literal (keys are never configuration). */
    static String resolve(SecretResolver secrets, String property, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException(property + " is required with " + PREFIX + "private-key-ref");
        }
        if (!ref.startsWith("secret://")) {
            throw new IllegalStateException(property + " must be a secret reference (secret://...), never the key");
        }
        return secrets.require(ref);
    }
}
