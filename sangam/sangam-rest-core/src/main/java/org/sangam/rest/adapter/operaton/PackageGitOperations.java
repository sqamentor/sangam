// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackageGitRemote;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PackageToolsPort.GitConfig;
import org.sangam.rest.port.PackageToolsPort.GitStatus;
import org.sangam.rest.port.PackageToolsPort.GitSyncRequest;
import org.sangam.rest.port.PackageToolsPort.GitSyncResult;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;

/**
 * M-100 (R69): package pull and push over the host's {@link PackageGitRemote}. Both directions are fast-forward only,
 * so neither side's work is ever overwritten:
 * <ul>
 *   <li><b>Sync state</b> ({@code package:git-sync}, doc id = package id): the commit and the package revision of the
 *       last successful sync, its time and the open conflicts ({@link GitStatus#lastSync()},
 *       {@link GitStatus#conflicts()}).</li>
 *   <li><b>pull</b> replaces the package's resources with the remote files, through {@link PackagePort#update} (DRAFT
 *       only, package author, revision checked, edit fence). When the package changed since the last sync (or was
 *       never synced and is not empty) and differs from the remote, nothing changes and the result names the
 *       conflict.</li>
 *   <li><b>push</b> commits the package's resources on top of the commit last synced, without force; a remote that
 *       moved since is a conflict and nothing is pushed. An empty package is never pushed (it would delete the
 *       repository's files).</li>
 *   <li>Audit {@code PACKAGE_GIT_PULLED}, {@code PACKAGE_GIT_PUSHED} or {@code PACKAGE_GIT_CONFLICT} on the package
 *       chain, in the transaction that stores the state. {@code ahead} and {@code behind} are not computed (0).</li>
 * </ul>
 * Without a bound transport pull and push are {@code 501 CAPABILITY_NOT_INSTALLED}; without a configured remote
 * {@code 409 GIT_NOT_CONFIGURED}.
 */
final class PackageGitOperations {

    static final String KIND_GIT_SYNC = "package:git-sync";

    /** The last successful sync of a package and the conflicts of the last attempt. */
    record SyncState(String commitId, long revision, Instant at, List<String> conflicts) {
    }

    private final PackagePort packages;
    private final AuditPort audit;
    private final DocumentStore docs;
    private final Supplier<PackageGitRemote> transport;

    PackageGitOperations(PackagePort packages, AuditPort audit, DocumentStore docs, Supplier<PackageGitRemote> transport) {
        this.packages = packages;
        this.audit = audit;
        this.docs = docs;
        this.transport = transport == null ? () -> null : transport;
    }

    GitStatus status(RequestContext ctx, String packageId, GitConfig config) {
        if (config == null) {
            return new GitStatus(null, null, 0, 0, null, List.of());
        }
        SyncState s = state(ctx, packageId);
        return new GitStatus(config.remote(), config.branch(), 0, 0, s == null ? null : s.at(),
                s == null || s.conflicts() == null ? List.of() : s.conflicts());
    }

    GitSyncResult sync(RequestContext ctx, String packageId, String direction, GitSyncRequest request) {
        PackagePort.Package p = packages.get(ctx, packageId);
        if (!"pull".equals(direction) && !"push".equals(direction)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "direction must be pull or push");
        }
        GitConfig config = docs.get(ctx.tenantId(), OperatonPackageToolsAdapter.KIND_GIT, packageId)
                .map(d -> d.as(GitConfig.class))
                .orElseThrow(() -> ApiProblem.conflict("GIT_NOT_CONFIGURED", "Configure the Git remote first"));
        PackageGitRemote git = transport.get();
        if (git == null) {
            throw ApiProblem.notInstalled("Git sync (R69)", "the server's Git binding (sangam.packages.git.enabled)");
        }
        PackageGitRemote.Remote remote = new PackageGitRemote.Remote(config.remote(), config.branch(), config.credentialRef(),
                config.provider());
        SyncState recorded = state(ctx, packageId);
        // a state with conflicts only (no commit) was never synced
        SyncState last = recorded == null || recorded.commitId() == null ? null : recorded;
        return "pull".equals(direction) ? pull(ctx, p, git, remote, last) : push(ctx, p, git, remote, last, request);
    }

    private GitSyncResult pull(RequestContext ctx, PackagePort.Package p, PackageGitRemote git, PackageGitRemote.Remote remote,
                               SyncState last) {
        PackageGitRemote.Snapshot snap = git.pull(ctx.tenantId(), remote);
        Map<String, byte[]> local = OperatonPackageToolsAdapter.resources(p);
        if (same(local, snap.files())) {
            return docs.tx().inTx(() -> settled(ctx, p, snap.commitId(), p.revision(), "PACKAGE_GIT_PULLED", Map.of("changed", false)));
        }
        boolean localChanged = last == null ? !local.isEmpty() : last.revision() != p.revision();
        if (localChanged) {
            String why = last == null
                    ? "the package has resources and was never synced; push it or start from an empty package"
                    : "the package changed since the last sync (revision " + last.revision() + ", now " + p.revision()
                    + "); push it first";
            return conflict(ctx, p, last, "pull", snap.commitId(), why);
        }
        Map<String, String> resources = encode(snap.files());
        return docs.tx().inTx(() -> {
            PackagePort.Package updated = packages.update(ctx, p.id(), p.revision(), new PackagePort.UpdatePackageRequest(null, resources));
            return settled(ctx, p, snap.commitId(), updated.revision(), "PACKAGE_GIT_PULLED",
                    Map.of("changed", true, "files", resources.size(), "revision", updated.revision()));
        });
    }

    private GitSyncResult push(RequestContext ctx, PackagePort.Package p, PackageGitRemote git, PackageGitRemote.Remote remote,
                               SyncState last, GitSyncRequest request) {
        Map<String, byte[]> files = OperatonPackageToolsAdapter.resources(p);
        if (files.isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "The package has no resources to push");
        }
        String message = request == null || request.message() == null || request.message().isBlank()
                ? "Sangam package " + p.key() + " revision " + p.revision() : request.message().trim();
        PackageGitRemote.Pushed out = git.push(ctx.tenantId(), remote, files, message, ctx.principalId(),
                last == null ? null : last.commitId());
        String status = out.status() == null ? "" : out.status().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "PUSHED", "UNCHANGED" -> docs.tx().inTx(() -> settled(ctx, p, out.commitId(), p.revision(), "PACKAGE_GIT_PUSHED",
                    Map.of("status", status, "parentId", out.parentId() == null ? "" : out.parentId(), "files", files.size())));
            case "CONFLICT" -> conflict(ctx, p, last, "push", out.parentId(), out.message() == null ? "the remote branch moved" : out.message());
            default -> throw ApiProblem.unavailable("GIT_FAILED", "the Git transport answered " + out.status(), 60);
        };
    }

    private GitSyncResult settled(RequestContext ctx, PackagePort.Package p, String commitId, long revision, String event,
                                  Map<String, ?> details) {
        docs.upsert(ctx.tenantId(), KIND_GIT_SYNC, p.id(), p.key(), null, null, p.id(),
                new SyncState(commitId, revision, Instant.now(), List.of()), ctx.principalId());
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("packageId", p.id());
        payload.put("commitId", commitId);
        payload.put("actor", ctx.actor());
        audit.record(ctx, "pkg:" + p.key(), event, payload);
        return new GitSyncResult(0, 0, List.of());
    }

    private GitSyncResult conflict(RequestContext ctx, PackagePort.Package p, SyncState last, String direction, String remoteCommit,
                                   String why) {
        docs.tx().runInTx(() -> {
            docs.upsert(ctx.tenantId(), KIND_GIT_SYNC, p.id(), p.key(), null, null, p.id(),
                    new SyncState(last == null ? null : last.commitId(), last == null ? -1 : last.revision(),
                            last == null ? null : last.at(), List.of(why)), ctx.principalId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("packageId", p.id());
            payload.put("direction", direction);
            payload.put("remoteCommit", remoteCommit == null ? "" : remoteCommit);
            payload.put("reason", why);
            payload.put("actor", ctx.actor());
            audit.record(ctx, "pkg:" + p.key(), "PACKAGE_GIT_CONFLICT", payload);
        });
        return new GitSyncResult(0, 0, List.of(why));
    }

    private SyncState state(RequestContext ctx, String packageId) {
        return docs.get(ctx.tenantId(), KIND_GIT_SYNC, packageId).map(d -> d.as(SyncState.class)).orElse(null);
    }

    static boolean same(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        return a.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), b.get(e.getKey())));
    }

    /** The {@link PackagePort} resource form: text resources as UTF-8 text (strictly decoded), any other as base64. */
    static Map<String, String> encode(Map<String, byte[]> files) {
        Map<String, String> out = new TreeMap<>();
        files.forEach((name, bytes) -> {
            String n = name.toLowerCase(Locale.ROOT);
            if (OperatonPackageToolsAdapter.TEXT_EXTENSIONS.stream().anyMatch(n::endsWith)) {
                try {
                    out.put(name, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
                } catch (CharacterCodingException e) {
                    throw ApiProblem.unprocessable("GIT_INVALID", name + " is a text resource but not UTF-8").with("file", name);
                }
            } else {
                out.put(name, Base64.getEncoder().encodeToString(bytes));
            }
        });
        return out;
    }
}
