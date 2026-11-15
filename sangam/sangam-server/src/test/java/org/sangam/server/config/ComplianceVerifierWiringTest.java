// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.layer.Dialect;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.ComplianceConfigProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-078 / M-087 wiring: the national-id vault bean needs a FieldEncryptor (fails loudly without one), and the
 * compliance facade takes the verifier and vault ports from beans instead of null.
 */
class ComplianceVerifierWiringTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        when(p.getIfUnique()).thenReturn(value);
        return p;
    }

    @Test
    void theVaultNeedsAFieldEncryptor() {
        TxSupport tx = mock(TxSupport.class);
        when(tx.dialect()).thenReturn(Dialect.POSTGRESQL);
        MockEnvironment env = new MockEnvironment().withProperty("sangam.compliance.national-id-vault.hmac-pepper",
                Base64.getEncoder().encodeToString(new byte[32]));
        ComplianceWiring w = new ComplianceWiring();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> w.sangamNationalIdVault(tx,
                provider((EngineAdapter) null), provider((FieldEncryptor) null), mock(ComplianceConfigProvider.class), env,
                org.sangam.compliance.PrincipalKey.unconfigured()));
        assertTrue(e.getMessage().contains("FieldEncryptor"));
        assertNotNull(w.sangamNationalIdVault(tx, provider((EngineAdapter) null), provider(mock(FieldEncryptor.class)),
                mock(ComplianceConfigProvider.class), env, org.sangam.compliance.PrincipalKey.unconfigured()));
    }

    @Test
    void aShortPepperIsRefused() {
        TxSupport tx = mock(TxSupport.class);
        when(tx.dialect()).thenReturn(Dialect.POSTGRESQL);
        MockEnvironment env = new MockEnvironment().withProperty("sangam.compliance.national-id-vault.hmac-pepper",
                Base64.getEncoder().encodeToString(new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> new ComplianceWiring().sangamNationalIdVault(tx,
                provider((EngineAdapter) null), provider(mock(FieldEncryptor.class)), mock(ComplianceConfigProvider.class), env,
                org.sangam.compliance.PrincipalKey.unconfigured()));
    }

    @Test
    void theFacadeNoLongerPassesNullForTheVerifierAndVaultPorts() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/sangam/server/config/ComplianceWiring.java"));
        assertTrue(src.contains("adultVerifier.getIfUnique(), guardianshipVerifier.getIfUnique()"));
        assertTrue(src.contains("nationalIdVault.getIfUnique()"));
    }
}
