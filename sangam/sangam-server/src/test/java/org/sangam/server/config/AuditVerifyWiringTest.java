// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * M-073: with trusted checkpoint public keys configured (as secret references only), the audit adapter's verify
 * includes the signed checkpoints; keys as values are refused.
 */
class AuditVerifyWiringTest {

    static String publicKey() throws Exception {
        return Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()
                .getEncoded());
    }

    @Test
    void trustedKeysAreReferencesOnly() throws Exception {
        String k1 = publicKey();
        String k2 = publicKey();
        SecretResolver secrets = SecretResolver.of(Map.of("secret://audit/cp-current", k1, "secret://audit/cp-retired", k2));
        assertEquals(List.of(k1, k2), AuditVerifyWiring.trustedKeys(secrets, "secret://audit/cp-current, secret://audit/cp-retired"));
        IllegalStateException literal = assertThrows(IllegalStateException.class, () -> AuditVerifyWiring.trustedKeys(secrets, k1));
        assertTrue(literal.getMessage().contains("secret references"), literal.getMessage());
        assertThrows(IllegalStateException.class, () -> AuditVerifyWiring.trustedKeys(secrets, " , "));
        assertThrows(IllegalStateException.class, () -> AuditVerifyWiring.trustedKeys(secrets, "secret://audit/missing"));
    }

    @Test
    void theJdbcAuditAdapterVerifiesTheCheckpoints() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:auditverify" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        TxSupport tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        JdbcAuditAdapter adapter = new JdbcAuditAdapter(tx, null, null, new RestProperties());
        AuditCheckpoints.KeyLookup keys = AuditCheckpoints.ed25519Keys(List.of(publicKey()));
        assertTrue(AuditVerifyWiring.bind(adapter, tx, keys));
        Field cps = JdbcAuditAdapter.class.getDeclaredField("checkpoints");
        Field kf = JdbcAuditAdapter.class.getDeclaredField("checkpointKeys");
        cps.setAccessible(true);
        kf.setAccessible(true);
        assertNotNull(cps.get(adapter), "verify-only checkpoints bound");
        assertSame(keys, kf.get(adapter));

        AuditPort other = (AuditPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AuditPort.class},
                (p, m, a) -> null);
        assertFalse(AuditVerifyWiring.bind(other, tx, keys), "another audit port is left as it is");
    }

    @Test
    void offWithoutTrustedKeys() {
        assertEquals(AuditVerifyWiring.KEYS_PROPERTY, String.join(",", AuditVerifyWiring.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class).name()));
    }
}
