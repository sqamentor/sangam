// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.git;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.engine.IllegalRemoteCallInLockScope;
import org.sangam.engine.tx.LockScope;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.SecretResolver;

/**
 * M-100: everything PackageGitSync refuses before any network call (scheme, credentials in the URL, branch and path
 * names, egress, credential references, the L2 lock rule), and the remote value object. The fetch and push paths
 * need an https Git server and belong to the integration stage.
 */
class PackageGitSyncTest {

    @TempDir
    Path work;

    private PackageGitSync sync(EgressPolicy egress, Map<String, String> secrets) {
        return new PackageGitSync(egress, SecretResolver.of(secrets), work, Duration.ofSeconds(2), null, 0, 0);
    }

    private PackageGitSync loopback() {
        return sync(EgressPolicy.simulatorLoopback(), Map.of());
    }

    private static PackageGitSync.Remote remote(String uri, String branch, String dir, String cred, String provider) {
        return new PackageGitSync.Remote(URI.create(uri), branch, dir, cred, provider);
    }

    private static String code(Runnable r) {
        return assertThrows(PackageGitSync.GitSyncException.class, r::run).code();
    }

    @Test
    void theRemoteHasSafeDefaults() {
        PackageGitSync.Remote r = remote("https://127.0.0.1/x.git", " ", "/packs/orders/", null, null);
        assertEquals("main", r.branch());
        assertEquals("packs/orders", r.directory());
        assertEquals("generic", r.provider());
        assertThrows(NullPointerException.class, () -> new PackageGitSync.Remote(null, null, null, null, null));
    }

    @Test
    void onlyHttpsWithoutCredentialsInTheUrlIsAccepted() {
        assertEquals("GIT_INVALID", code(() -> loopback().pull("t1", remote("http://127.0.0.1/x.git", null, null, null,
                null))));
        assertEquals("GIT_INVALID", code(() -> loopback().pull("t1", remote("ssh://127.0.0.1/x.git", null, null, null,
                null))));
        assertEquals("GIT_INVALID", code(() -> loopback().pull("t1", remote("https://u:p@127.0.0.1/x.git", null, null,
                null, null))));
    }

    @Test
    void unsafeBranchesAndDirectoriesAreRefused() {
        for (String b : List.of("a..b", "feature branch", "x;rm")) {
            assertEquals("GIT_INVALID", code(() -> loopback().pull("t1", remote("https://127.0.0.1/x.git", b, null, null,
                    null))), b);
        }
        for (String d : List.of("../etc", "a/.git/b", "a/./b", "a\\b")) {
            assertEquals("GIT_INVALID", code(() -> loopback().pull("t1", remote("https://127.0.0.1/x.git", "main", d,
                    null, null))), d);
        }
    }

    @Test
    void packagePathsArePlainRelativeSegments() {
        for (String p : List.of("process.bpmn", "forms/a b.json", "dmn/rate(1).dmn")) {
            assertDoesNotThrow(() -> PackageGitSync.checkPath(p), p);
        }
        for (String p : List.of("", "/abs", "a//b", "..", "a/../b", ".git/config", "a/.GIT", "trailing.", "space ",
                "back\\slash", "nul\u0000")) {
            assertEquals("GIT_INVALID", code(() -> PackageGitSync.checkPath(p)), p);
        }
        assertEquals("GIT_INVALID", code(() -> loopback().push("t1", remote("https://127.0.0.1/x.git", null, null, null,
                null), Map.of("../escape", new byte[0]), "m", "a", "a@example.org", null)));
    }

    @Test
    void aDestinationOutsideTheEgressPolicyIsRefusedBeforeAnyConnection() {
        PackageGitSync s = sync(EgressPolicy.denyAll(), Map.of());
        assertEquals("GIT_INVALID", code(() -> s.pull("t1", remote("https://127.0.0.1/x.git", null, null, null, null))));
    }

    @Test
    void credentialsComeOnlyFromAResolvableReference() {
        PackageGitSync s = sync(EgressPolicy.simulatorLoopback(), Map.of("secret://git/empty", " ",
                "secret://git/token", "test-token-value"));
        assertEquals("GIT_AUTH", code(() -> s.pull("t1", remote("https://127.0.0.1:1/x.git", null, null,
                "secret://git/missing", null))));
        assertEquals("GIT_AUTH", code(() -> s.pull("t1", remote("https://127.0.0.1:1/x.git", null, null,
                "secret://git/empty", null))));
        assertEquals("GIT_AUTH", code(() -> s.pull("t1", remote("https://127.0.0.1:1/x.git", null, null,
                "secret://git/token", "gitea"))), "a bare token needs a provider with a known token user");
    }

    @Test
    void noGitCallWhileAnEngineLockIsHeld() {
        LockScope.enter();
        try {
            assertThrows(IllegalRemoteCallInLockScope.class, () -> loopback().pull("t1",
                    remote("https://127.0.0.1/x.git", null, null, null, null)));
        } finally {
            LockScope.exit();
        }
    }

    @Test
    void refusedCallsLeaveNoTemporaryRepositoryBehind() throws Exception {
        assertEquals("GIT_INVALID", code(() -> loopback().pull("t1", remote("http://127.0.0.1/x.git", null, null, null,
                null))));
        try (Stream<Path> s = Files.list(work)) {
            assertFalse(s.findAny().isPresent(), "nothing is created before the checks pass");
        }
    }
}
