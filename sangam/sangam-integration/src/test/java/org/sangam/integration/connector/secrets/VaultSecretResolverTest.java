// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.sim.SimHttpServer;

/** M-075: the Vault KV v2 resolver against a loopback stand-in of the public KV v2 read API. */
class VaultSecretResolverTest {

    /** KV v2 read: {@code GET /v1/<mount>/data/<path>}, token in X-Vault-Token. */
    static final class KvStub extends SimHttpServer {
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final List<String> namespaces = new CopyOnWriteArrayList<>();
        volatile int status = 200;

        KvStub() {
            super("vault-kv-stub");
            prefixRoute("GET", "/v1/secret/data/", r -> {
                tokens.add(String.valueOf(r.header("X-Vault-Token")));
                namespaces.add(String.valueOf(r.header("X-Vault-Namespace")));
                if (status != 200) {
                    return Response.json(status, "{\"errors\":[]}");
                }
                String path = r.pathAfter("/v1/secret/data/");
                return switch (path) {
                    case "sangam/app/db" -> Response.json(200, "{\"data\":{\"data\":{\"password\":\"p@ss\"}}}");
                    case "sangam/single" -> Response.json(200, "{\"data\":{\"data\":{\"value\":\"one\"}}}");
                    default -> Response.json(404, "{\"errors\":[]}");
                };
            });
        }
    }

    private KvStub vault;
    private final Map<String, String> boot = new HashMap<>(Map.of("secret://boot/vault-token", "hvs.first"));

    @BeforeEach
    void start() {
        vault = new KvStub().start();
    }

    @AfterEach
    void stop() {
        vault.close();
    }

    private VaultSecretResolver resolver(String namespace) {
        return new VaultSecretResolver(new VaultSecretResolver.Settings(vault.baseUri(), null, "/sangam/", namespace,
                "secret://boot/vault-token", true), SecretResolver.of(boot), false);
    }

    @Test
    void aFieldIsReadFromThePathAndThePrefix() {
        assertEquals(Optional.of("p@ss"), resolver(null).resolve("secret://app/db/password"));
        assertEquals(Optional.of("one"), resolver(null).resolve("secret://single"));
        assertEquals(List.of("hvs.first", "hvs.first"), vault.tokens);
    }

    @Test
    void notFoundAndAMissingFieldAreEmpty() {
        assertTrue(resolver(null).resolve("secret://app/other/password").isEmpty());
        assertTrue(resolver(null).resolve("secret://app/db/user").isEmpty());
        assertTrue(resolver(null).resolve("not-a-reference").isEmpty());
    }

    @Test
    void aRenewedTokenIsUsedAtOnceAndTheNamespaceIsSent() {
        VaultSecretResolver r = resolver("team-a");
        r.resolve("secret://app/db/password");
        boot.put("secret://boot/vault-token", "hvs.second");
        r.resolve("secret://app/db/password");
        assertEquals(List.of("hvs.first", "hvs.second"), vault.tokens);
        assertEquals("team-a", vault.namespaces.get(0));
    }

    @Test
    void aVaultErrorIsAnErrorNotAMissingSecret() {
        vault.status = 503;
        assertThrows(RuntimeException.class, () -> resolver(null).resolve("secret://app/db/password"));
    }

    @Test
    void aMissingBootstrapTokenFailsConstruction() {
        boot.clear();
        assertThrows(RuntimeException.class, () -> resolver(null));
    }

    @Test
    void aPrivateVaultAddressNeedsAllowPrivate() {
        VaultSecretResolver strict = new VaultSecretResolver(new VaultSecretResolver.Settings(vault.baseUri(), null, null,
                null, "secret://boot/vault-token", false), SecretResolver.of(boot), false);
        assertThrows(RuntimeException.class, () -> strict.resolve("secret://app/db/password"));
        assertFalse(vault.tokens.contains("hvs.first"), "nothing was sent to a loopback address that was not allowed");
    }
}
