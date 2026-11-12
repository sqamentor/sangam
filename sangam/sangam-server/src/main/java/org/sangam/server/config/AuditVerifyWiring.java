// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.List;

import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-073 (SL-152): audit verification includes the signed Merkle checkpoints. With
 * {@code sangam.audit.checkpoint.trusted-public-key-refs} set (a comma list of {@code secret://} references, each a
 * base64 X.509 Ed25519 public key: the current checkpoint key and the retired ones still trusted), the audit adapter's
 * verify ({@code JdbcAuditAdapter.setCheckpoints}) also checks every checkpoint of the tenant (numbering, statement,
 * hash links, signatures, Merkle roots) and that each chain still holds its sealed head. A verify-only
 * {@link AuditCheckpoints} (no signer): verification works on every node, signing only where the private key is
 * configured ({@link AuditCheckpointWiring}). Keys are references only (Q-04); a literal key fails the start-up.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = AuditVerifyWiring.KEYS_PROPERTY)
public class AuditVerifyWiring {

    private static final Logger LOG = LoggerFactory.getLogger(AuditVerifyWiring.class);
    static final String KEYS_PROPERTY = "sangam.audit.checkpoint.trusted-public-key-refs";

    /** The trusted public keys behind the references; refuses a literal key or an unresolved reference. */
    static List<String> trustedKeys(SecretResolver secrets, String refs) {
        List<String> keys = new ArrayList<>();
        for (String ref : refs.split(",")) {
            String r = ref.trim();
            if (r.isEmpty()) {
                continue;
            }
            if (!r.startsWith("secret://")) {
                throw new IllegalStateException(KEYS_PROPERTY + " takes secret references (secret://...), never a key");
            }
            keys.add(secrets.require(r));
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException(KEYS_PROPERTY + " is set but names no key reference");
        }
        return keys;
    }

    @Bean
    public InitializingBean sangamAuditCheckpointVerification(ObjectProvider<AuditPort> audit, TxSupport tx,
            ObjectProvider<SecretResolver> secrets, Environment env) {
        return () -> {
            AuditCheckpoints.KeyLookup keys = AuditCheckpoints.ed25519Keys(trustedKeys(
                    secrets.getIfAvailable(SecretResolver::fromEnvironment), env.getProperty(KEYS_PROPERTY, "")));
            if (!bind(audit.getIfAvailable(), tx, keys)) {
                LOG.warn("M-073: {} is set but the audit port is not the JDBC audit adapter; checkpoints are not verified",
                        KEYS_PROPERTY);
            }
        };
    }

    /** Binds verify-only checkpoints with {@code keys} on the JDBC audit adapter; false for another audit port. */
    static boolean bind(AuditPort audit, TxSupport tx, AuditCheckpoints.KeyLookup keys) {
        if (!(audit instanceof JdbcAuditAdapter jdbc)) {
            return false;
        }
        jdbc.setCheckpoints(new AuditCheckpoints(tx.newTransactions(), tx.dialect(), null, null), keys);
        LOG.info("M-073: audit verification includes the signed checkpoints");
        return true;
    }
}
