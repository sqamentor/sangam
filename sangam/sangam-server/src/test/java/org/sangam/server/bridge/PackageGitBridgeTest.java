// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.sangam.integration.git.PackageGitSync;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackageGitRemote;

/** M-100: every Git failure keeps its stable code; the remote is the repository root. */
class PackageGitBridgeTest {

    private static ApiProblem problem(String code) {
        return PackageGitBridge.problem(new PackageGitSync.GitSyncException(code, "detail", null));
    }

    @Test
    void failuresKeepTheirCodes() {
        assertEquals(422, problem("GIT_INVALID").status());
        assertEquals(422, problem("GIT_AUTH").status());
        assertEquals("GIT_AUTH", problem("GIT_AUTH").code());
        assertEquals(503, problem("GIT_UNREACHABLE").status());
        assertEquals("GIT_UNREACHABLE", problem("GIT_UNREACHABLE").code());
        assertEquals(503, problem("GIT_FAILED").status());
        assertEquals("GIT_FAILED", problem("SOMETHING_ELSE").code(), "an unknown code is a failure, never a success");
    }

    @Test
    void theRemoteIsTheRepositoryRoot() {
        PackageGitSync.Remote r = PackageGitBridge.remote(new PackageGitRemote.Remote(" https://git.example.org/o.git ", null,
                "secret://git/o", "GitLab"));
        assertEquals("", r.directory());
        assertEquals("main", r.branch());
        assertEquals("gitlab", r.provider());
        assertEquals("secret://git/o", r.credentialRef());
        assertEquals("git.example.org", r.uri().getHost());
    }
}
