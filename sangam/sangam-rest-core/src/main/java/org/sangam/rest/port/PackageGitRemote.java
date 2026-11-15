// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.Map;
import java.util.Objects;

/**
 * M-100 (R69): the host's Git transport for package pull and push. sangam-rest stores the configuration, the package
 * files, the sync state and the audit; the transport only talks to the remote. The server binds it with
 * sangam-integration's {@code PackageGitSync} when {@code sangam.packages.git.enabled=true} and JGit is on the class
 * path; without a binding pull and push answer {@code 501 CAPABILITY_NOT_INSTALLED} (fail closed).
 *
 * <p>A failed call throws an {@link ApiProblem} with a stable code: {@code GIT_INVALID} or {@code GIT_AUTH} (422: the
 * remote, branch, path, egress rule or credential is wrong), {@code GIT_UNREACHABLE} or {@code GIT_FAILED} (503).
 */
public interface PackageGitRemote {

    /** The stored remote of a package: the repository root is the package (the configuration has no directory). */
    record Remote(String uri, String branch, String credentialRef, String provider) {
        public Remote {
            Objects.requireNonNull(uri, "uri");
        }
    }

    /** The files at {@code commitId} (paths relative to the repository root). */
    record Snapshot(String commitId, Map<String, byte[]> files) {
        public Snapshot {
            files = Map.copyOf(files);
        }
    }

    /** {@code status}: {@code PUSHED}, {@code UNCHANGED} or {@code CONFLICT} (the remote moved; nothing was pushed). */
    record Pushed(String status, String commitId, String parentId, String message) {
    }

    /** Fetches the branch and returns its files. */
    Snapshot pull(String tenantId, Remote remote);

    /**
     * Replaces the files on the branch with {@code files} in one commit, pushed without force. With
     * {@code expectedParent} (the commit last synced) a remote tip that differs is {@code CONFLICT}.
     */
    Pushed push(String tenantId, Remote remote, Map<String, byte[]> files, String message, String authorName,
                String expectedParent);
}
