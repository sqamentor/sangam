// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.connector.SecretResolver;

/** M-075: references, the mounted-file resolver, the cache, the chain and the provider list. */
class SecretResolversTest {

    @TempDir
    Path root;

    @Test
    void aReferenceIsParsedIntoSafeSegments() {
        SecretRef r = SecretRef.parse("secret://payments/gateway/key").orElseThrow();
        assertEquals(List.of("payments", "gateway", "key"), r.segments());
        assertEquals("key", r.last());
        for (String bad : List.of("secret://", "secret://a/../b", "secret://a/./b", "secret://a//b", "secret://a b",
                "plain-value")) {
            assertTrue(SecretRef.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void theMountedFileIsReadExactlyAndNeverTrimmed() throws Exception {
        Files.createDirectories(root.resolve("db"));
        Files.writeString(root.resolve("db/password"), "s3cret\n", StandardCharsets.UTF_8);
        MountedFileSecretResolver r = new MountedFileSecretResolver(root);
        assertEquals(Optional.of("s3cret\n"), r.resolve("secret://db/password"),
                "the value is returned as stored; the consumer refuses an untidy one (SL-65)");
        assertTrue(r.resolve("secret://db/missing").isEmpty());
        assertTrue(r.resolve("not-a-reference").isEmpty());
    }

    @Test
    void aSymlinkOutOfTheRootIsRefused() throws Exception {
        Path outside = Files.createTempFile("outside", ".txt");
        try {
            Files.writeString(outside, "leak");
            try {
                Files.createSymbolicLink(root.resolve("link"), outside);
            } catch (UnsupportedOperationException | java.io.IOException e) {
                return;   // the platform cannot create a symlink (no privilege); nothing to check
            }
            MountedFileSecretResolver r = new MountedFileSecretResolver(root);
            assertTrue(r.resolve("secret://link").isEmpty(), "a link that leaves the root is not followed");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void theCacheKeepsFoundValuesForItsTtlOnly() {
        AtomicInteger calls = new AtomicInteger();
        SecretResolver delegate = ref -> {
            calls.incrementAndGet();
            return ref.endsWith("/a") ? Optional.of("v") : Optional.empty();
        };
        MutableClock clock = new MutableClock();
        CachingSecretResolver c = new CachingSecretResolver(delegate, Duration.ofMinutes(5), clock);
        c.resolve("secret://x/a");
        c.resolve("secret://x/a");
        assertEquals(1, calls.get());
        c.resolve("secret://x/b");
        c.resolve("secret://x/b");
        assertEquals(3, calls.get(), "a missing value is not cached");
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        c.resolve("secret://x/a");
        assertEquals(4, calls.get());
        c.clear();
        c.resolve("secret://x/a");
        assertEquals(5, calls.get());
    }

    @Test
    void theChainAsksInOrderAndAFailureStopsIt() {
        SecretResolver first = SecretResolver.of(Map.of("secret://a", "1"));
        SecretResolver second = SecretResolver.of(Map.of("secret://a", "2", "secret://b", "3"));
        SecretResolver chain = SecretResolver.chain(List.of(first, second));
        assertEquals(Optional.of("1"), chain.resolve("secret://a"));
        assertEquals(Optional.of("3"), chain.resolve("secret://b"));
        SecretResolver failing = ref -> {
            throw new IllegalStateException("vault down");
        };
        assertThrows(IllegalStateException.class,
                () -> SecretResolver.chain(List.of(failing, second)).resolve("secret://b"),
                "an unavailable provider is not skipped silently");
    }

    @Test
    void theProviderListIsParsedStrictly() {
        assertEquals(List.of(SecretProviders.Kind.FILE, SecretProviders.Kind.VAULT, SecretProviders.Kind.ENV),
                SecretProviders.parse(" file, vault ,env"));
        assertThrows(IllegalStateException.class, () -> SecretProviders.parse("file,file"));
        assertThrows(IllegalStateException.class, () -> SecretProviders.parse("file,keychain"));
    }

    @Test
    void fileAndEnvNeedNoNetworkSettings() throws Exception {
        Files.writeString(root.resolve("k"), "v");
        SecretResolver r = SecretProviders.build(new SecretProviders.Settings(
                List.of(SecretProviders.Kind.FILE, SecretProviders.Kind.ENV), Duration.ofMinutes(1), root,
                null, null, null, null), false, Clock.systemUTC());
        assertEquals(Optional.of("v"), r.resolve("secret://k"));
    }

    @Test
    void aNetworkProviderWithoutSettingsFailsTheBuild() {
        assertThrows(IllegalStateException.class, () -> SecretProviders.build(new SecretProviders.Settings(
                List.of(SecretProviders.Kind.VAULT), Duration.ofMinutes(1), null, null, null, null, null), false,
                Clock.systemUTC()));
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
