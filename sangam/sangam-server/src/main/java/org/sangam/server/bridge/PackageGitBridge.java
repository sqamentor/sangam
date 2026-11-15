// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

import org.sangam.integration.git.PackageGitSync;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackageGitRemote;

/**
 * M-100 (R69): sangam-rest's {@link PackageGitRemote} bound to sangam-integration's {@link PackageGitSync} (JGit). The
 * repository root is the package (the stored configuration has no directory). Each {@link PackageGitSync.GitSyncException}
 * becomes a problem with the same stable code: {@code GIT_INVALID} and {@code GIT_AUTH} are 422 (the remote, branch,
 * path, egress rule or credential must change), {@code GIT_UNREACHABLE} and {@code GIT_FAILED} 503 with Retry-After.
 * An unparsable remote URI is {@code GIT_INVALID} before any call.
 */
public final class PackageGitBridge implements PackageGitRemote {

    static final int RETRY_AFTER_SECONDS = 60;

    private final PackageGitSync git;

    public PackageGitBridge(PackageGitSync git) {
        this.git = Objects.requireNonNull(git, "git");
    }

    @Override
    public Snapshot pull(String tenantId, Remote remote) {
        try {
            PackageGitSync.Snapshot s = git.pull(tenantId, remote(remote));
            return new Snapshot(s.commitId(), s.files());
        } catch (PackageGitSync.GitSyncException e) {
            throw problem(e);
        }
    }

    @Override
    public Pushed push(String tenantId, Remote remote, Map<String, byte[]> files, String message, String authorName,
                       String expectedParent) {
        try {
            PackageGitSync.PushOutcome o = git.push(tenantId, remote(remote), files, message, authorName, "", expectedParent);
            return new Pushed(o.status(), o.commitId(), o.parentId(), o.message());
        } catch (PackageGitSync.GitSyncException e) {
            throw problem(e);
        }
    }

    static PackageGitSync.Remote remote(Remote r) {
        URI uri;
        try {
            uri = new URI(r.uri().trim());
        } catch (java.net.URISyntaxException e) {
            throw ApiProblem.unprocessable("GIT_INVALID", "the Git remote is not a valid URI: " + e.getReason());
        }
        return new PackageGitSync.Remote(uri, r.branch(), "", r.credentialRef(), r.provider());
    }

    static ApiProblem problem(PackageGitSync.GitSyncException e) {
        return switch (e.code()) {
            case "GIT_INVALID", "GIT_AUTH" -> ApiProblem.unprocessable(e.code(), e.getMessage());
            case "GIT_UNREACHABLE" -> ApiProblem.unavailable(e.code(), e.getMessage(), RETRY_AFTER_SECONDS);
            default -> ApiProblem.unavailable("GIT_FAILED", e.getMessage(), RETRY_AFTER_SECONDS);
        };
    }
}
