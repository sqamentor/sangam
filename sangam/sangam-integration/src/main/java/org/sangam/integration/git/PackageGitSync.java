// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.git;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.sangam.engine.tx.LockScope;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M-100 (R69): Git pull and push of a package's files through JGit (EDL-1.0, allowed by {@code licence-policy.json}).
 * This is the Git engine; binding it to {@code PackageToolsPort.gitSync} (the package store, approval state and audit)
 * belongs to the REST adapter.
 *
 * <ul>
 *   <li><b>Egress.</b> Only {@code https} remotes, checked against the tenant's {@link EgressPolicy} before any
 *       connection; credentials in the URL are refused. The repository is configured with
 *       {@code http.followRedirects=false} and {@code http.sslVerify=true}, so JGit never follows a redirect the policy
 *       did not check and never turns certificate checks off from a user or system Git configuration. JGit connects by
 *       host name itself, so the addresses vetted by the check are not pinned for the connection (DNS rebinding between
 *       check and connect is not detected here, unlike the HTTP transport).</li>
 *   <li><b>Credentials by reference.</b> {@code credentialRef} is read through the {@link SecretResolver} on every
 *       call and given to JGit as HTTP Basic credentials only, never written to the repository configuration. The
 *       secret is {@code user:token}, or a bare token used with the provider's token user name ({@code x-access-token}
 *       for GitHub, {@code oauth2} for GitLab; Gitea and generic remotes need {@code user:token}).</li>
 *   <li><b>No working tree.</b> Each call uses a fresh bare repository in a temporary directory that is deleted
 *       afterwards. Pull reads blobs from the fetched commit's tree; push builds the new tree in memory (the files
 *       under the package directory replaced, everything else kept), commits it on top of the fetched tip and pushes
 *       without force, so a concurrent change on the remote is a conflict, never overwritten. Fetched objects are
 *       checked ({@code transfer.fsckObjects}); symbolic links, submodules and unsafe paths are refused.</li>
 *   <li><b>Bounds.</b> At most {@code maxFiles} files and {@code maxBytes} bytes per pull; a network timeout per call.
 *       VI.1 rule 5 (owner decision 86): no call while the thread's engine command holds an L2 root lock.</li>
 * </ul>
 */
public final class PackageGitSync {

    private static final Logger LOG = LoggerFactory.getLogger(PackageGitSync.class);

    /** One remote branch and the package directory inside it ({@code ""} for the repository root). */
    public record Remote(URI uri, String branch, String directory, String credentialRef, String provider) {
        public Remote {
            Objects.requireNonNull(uri, "uri");
            branch = branch == null || branch.isBlank() ? "main" : branch.trim();
            directory = directory == null ? "" : directory.replaceAll("^/+|/+$", "");
            provider = provider == null || provider.isBlank() ? "generic" : provider.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** The files of the package directory at {@code commitId} (paths relative to the directory). */
    public record Snapshot(String commitId, Map<String, byte[]> files) {
        public Snapshot {
            files = Map.copyOf(files);
        }
    }

    /** A push: {@code status} is {@code PUSHED}, {@code UNCHANGED} or {@code CONFLICT} (the remote moved on). */
    public record PushOutcome(String status, String commitId, String parentId, String message) {
    }

    /** A Git call that failed: {@code code} is {@code GIT_AUTH}, {@code GIT_UNREACHABLE}, {@code GIT_INVALID} or {@code GIT_FAILED}. */
    public static final class GitSyncException extends RuntimeException {
        private final String code;

        public GitSyncException(String code, String message, Throwable cause) {
            super(code + ": " + message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,200}");
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._ ()+,@=-]+(/[A-Za-z0-9._ ()+,@=-]+)*");

    private final EgressPolicy egress;
    private final SecretResolver secrets;
    private final Path workRoot;
    private final Duration timeout;
    private final Clock clock;
    private final int maxFiles;
    private final long maxBytes;

    public PackageGitSync(EgressPolicy egress, SecretResolver secrets, Path workRoot, Duration timeout, Clock clock,
                          int maxFiles, long maxBytes) {
        this.egress = Objects.requireNonNull(egress, "egress");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.workRoot = workRoot;
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.maxFiles = maxFiles <= 0 ? 2000 : maxFiles;
        this.maxBytes = maxBytes <= 0 ? 64L * 1024 * 1024 : maxBytes;
    }

    /** Fetches the branch and returns the files under the package directory. */
    public Snapshot pull(String tenantId, Remote remote) {
        return withRepository(tenantId, remote, (git, creds) -> {
            ObjectId tip = fetch(git, remote, creds);
            if (tip == null) {
                throw new GitSyncException("GIT_INVALID", "branch " + remote.branch() + " does not exist on the remote", null);
            }
            return new Snapshot(tip.name(), read(git.getRepository(), tip, remote.directory()));
        });
    }

    /**
     * Replaces the package directory on the branch with {@code files} and pushes one commit. {@code expectedParent}
     * (optional) is the commit the caller last pulled: when the remote tip differs, nothing is pushed
     * ({@code CONFLICT}).
     */
    public PushOutcome push(String tenantId, Remote remote, Map<String, byte[]> files, String message, String authorName,
                            String authorEmail, String expectedParent) {
        Objects.requireNonNull(files, "files");
        files.keySet().forEach(PackageGitSync::checkPath);
        return withRepository(tenantId, remote, (git, creds) -> {
            Repository repo = git.getRepository();
            ObjectId tip = fetch(git, remote, creds);
            if (expectedParent != null && !expectedParent.isBlank()
                    && (tip == null || !tip.name().equalsIgnoreCase(expectedParent.trim()))) {
                return new PushOutcome("CONFLICT", null, tip == null ? null : tip.name(),
                        "the remote branch moved since " + expectedParent);
            }
            ObjectId commit;
            try (ObjectInserter ins = repo.newObjectInserter(); RevWalk rw = new RevWalk(repo)) {
                RevCommit parent = tip == null ? null : rw.parseCommit(tip);
                ObjectId tree = tree(repo, ins, parent, remote.directory(), files);
                if (parent != null && tree.equals(parent.getTree())) {
                    return new PushOutcome("UNCHANGED", parent.name(), parent.name(), "no change");
                }
                PersonIdent who = new PersonIdent(authorName == null || authorName.isBlank() ? "Sangam" : authorName,
                        authorEmail == null ? "" : authorEmail, clock.instant(), ZoneOffset.UTC);
                CommitBuilder cb = new CommitBuilder();
                cb.setTreeId(tree);
                if (parent != null) {
                    cb.setParentId(parent);
                }
                cb.setAuthor(who);
                cb.setCommitter(who);
                cb.setMessage(message == null || message.isBlank() ? "Sangam package update" : message);
                commit = ins.insert(cb);
                ins.flush();
                RefUpdate ru = repo.updateRef(Constants.R_HEADS + remote.branch());
                ru.setNewObjectId(commit);
                ru.setExpectedOldObjectId(parent == null ? ObjectId.zeroId() : parent);
                RefUpdate.Result r = ru.update(rw);
                if (r != RefUpdate.Result.NEW && r != RefUpdate.Result.FAST_FORWARD) {
                    throw new GitSyncException("GIT_FAILED", "local ref update " + r, null);
                }
            }
            String ref = Constants.R_HEADS + remote.branch();
            Iterable<org.eclipse.jgit.transport.PushResult> results = git.push().setRemote("origin")
                    .setRefSpecs(new RefSpec(ref + ":" + ref)).setCredentialsProvider(creds)
                    .setTimeout((int) Math.max(1, timeout.toSeconds())).setForce(false).call();
            for (org.eclipse.jgit.transport.PushResult pr : results) {
                RemoteRefUpdate u = pr.getRemoteUpdate(ref);
                if (u == null) {
                    continue;
                }
                return switch (u.getStatus()) {
                    case OK, UP_TO_DATE -> new PushOutcome("PUSHED", commit.name(), tip == null ? null : tip.name(), "pushed");
                    case REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED -> new PushOutcome("CONFLICT", null,
                            tip == null ? null : tip.name(), "the remote rejected the push as not a fast-forward");
                    default -> throw new GitSyncException("GIT_FAILED", "push " + u.getStatus()
                            + (u.getMessage() == null ? "" : ": " + u.getMessage()), null);
                };
            }
            throw new GitSyncException("GIT_FAILED", "the remote reported no result for " + ref, null);
        });
    }

    @FunctionalInterface
    private interface RepoWork<T> {
        T run(Git git, UsernamePasswordCredentialsProvider creds) throws IOException, GitAPIException;
    }

    private <T> T withRepository(String tenantId, Remote remote, RepoWork<T> work) {
        LockScope.assertNoLockHeld("Git " + remote.uri().getHost());
        checkRemote(tenantId, remote);
        UsernamePasswordCredentialsProvider creds = credentials(remote);
        Path dir;
        try {
            dir = workRoot == null ? Files.createTempDirectory("sangam-git-") : Files.createTempDirectory(
                    Files.createDirectories(workRoot), "sangam-git-");
        } catch (IOException e) {
            throw new GitSyncException("GIT_FAILED", "no temporary directory: " + e.getMessage(), e);
        }
        try (Git git = Git.init().setBare(true).setDirectory(dir.toFile()).setInitialBranch(remote.branch()).call()) {
            StoredConfig cfg = git.getRepository().getConfig();
            cfg.setString("http", null, "followRedirects", "false");
            cfg.setBoolean("http", null, "sslVerify", true);
            cfg.setBoolean("transfer", null, "fsckObjects", true);
            cfg.setBoolean("fetch", null, "fsckObjects", true);
            cfg.save();
            git.remoteAdd().setName("origin").setUri(new URIish(remote.uri().toURL())).call();
            return work.run(git, creds);
        } catch (TransportException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            boolean auth = m.contains("not authorized") || m.contains("authentication") || m.contains("401")
                    || m.contains("403");
            throw new GitSyncException(auth ? "GIT_AUTH" : "GIT_UNREACHABLE", e.getMessage(), e);
        } catch (GitAPIException | IOException e) {
            throw new GitSyncException("GIT_FAILED", e.getMessage(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    private void checkRemote(String tenantId, Remote remote) {
        URI u = remote.uri();
        if (!"https".equalsIgnoreCase(u.getScheme())) {
            throw new GitSyncException("GIT_INVALID", "only https Git remotes are allowed: " + u.getScheme(), null);
        }
        if (u.getUserInfo() != null) {
            throw new GitSyncException("GIT_INVALID", "credentials must not be in the remote URL; use credentialRef", null);
        }
        if (!BRANCH.matcher(remote.branch()).matches() || remote.branch().contains("..")) {
            throw new GitSyncException("GIT_INVALID", "not a valid branch name: " + remote.branch(), null);
        }
        if (!remote.directory().isEmpty()) {
            checkPath(remote.directory());
        }
        try {
            egress.check(tenantId, u);
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new GitSyncException("GIT_INVALID", e.getMessage(), e);
        }
    }

    private UsernamePasswordCredentialsProvider credentials(Remote remote) {
        if (remote.credentialRef() == null || remote.credentialRef().isBlank()) {
            return null;   // a public remote: pull works, push is refused by the remote
        }
        String v;
        try {
            v = secrets.require(remote.credentialRef());
        } catch (RuntimeException e) {
            throw new GitSyncException("GIT_AUTH", remote.credentialRef() + " cannot be resolved", e);
        }
        if (v == null || v.isBlank()) {
            throw new GitSyncException("GIT_AUTH", remote.credentialRef() + " resolves to an empty value", null);
        }
        int colon = v.indexOf(':');
        if (colon > 0) {
            return new UsernamePasswordCredentialsProvider(v.substring(0, colon), v.substring(colon + 1));
        }
        String user = switch (remote.provider()) {
            case "github" -> "x-access-token";
            case "gitlab" -> "oauth2";
            default -> throw new GitSyncException("GIT_AUTH", "provider " + remote.provider()
                    + " needs the credential as user:token", null);
        };
        return new UsernamePasswordCredentialsProvider(user, v);
    }

    /** Fetches the branch into {@code refs/heads/<branch>}; null when the remote has no such branch. */
    private ObjectId fetch(Git git, Remote remote, UsernamePasswordCredentialsProvider creds)
            throws IOException, GitAPIException {
        String ref = Constants.R_HEADS + remote.branch();
        boolean exists = git.lsRemote().setRemote("origin").setHeads(true).setCredentialsProvider(creds)
                .setTimeout((int) Math.max(1, timeout.toSeconds())).call().stream()
                .anyMatch(r -> r.getName().equals(ref));
        if (!exists) {
            return null;
        }
        git.fetch().setRemote("origin").setRefSpecs(new RefSpec("+" + ref + ":" + ref)).setCredentialsProvider(creds)
                .setTimeout((int) Math.max(1, timeout.toSeconds())).call();
        return git.getRepository().resolve(ref);
    }

    private Map<String, byte[]> read(Repository repo, ObjectId commitId, String directory) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        long total = 0;
        try (RevWalk rw = new RevWalk(repo); TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(rw.parseCommit(commitId).getTree());
            tw.setRecursive(true);
            if (!directory.isEmpty()) {
                tw.setFilter(PathFilter.create(directory));
            }
            String prefix = directory.isEmpty() ? "" : directory + "/";
            while (tw.next()) {
                String path = tw.getPathString();
                FileMode mode = tw.getFileMode(0);
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                    throw new GitSyncException("GIT_INVALID", "only regular files are allowed in a package: " + path
                            + " is " + (mode == FileMode.SYMLINK ? "a symbolic link" : mode == FileMode.GITLINK
                            ? "a submodule" : "not a file"), null);
                }
                String name = path.substring(prefix.length());
                checkPath(name);
                if (out.size() >= maxFiles) {
                    throw new GitSyncException("GIT_INVALID", "more than " + maxFiles + " files under " + directory, null);
                }
                byte[] b = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB).getCachedBytes((int) Math.min(Integer.MAX_VALUE,
                        maxBytes));
                total += b.length;
                if (total > maxBytes) {
                    throw new GitSyncException("GIT_INVALID", "the package directory is larger than " + maxBytes + " bytes",
                            null);
                }
                out.put(name, b);
            }
        }
        return out;
    }

    /** The parent's tree with everything under {@code directory} replaced by {@code files}. */
    private static ObjectId tree(Repository repo, ObjectInserter ins, RevCommit parent, String directory,
                                 Map<String, byte[]> files) throws IOException {
        String prefix = directory.isEmpty() ? "" : directory + "/";
        DirCache dc = DirCache.newInCore();
        DirCacheBuilder b = dc.builder();
        if (parent != null) {
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(parent.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    String p = tw.getPathString();
                    if (prefix.isEmpty() || p.startsWith(prefix)) {
                        continue;   // the package directory (the whole tree at the root) is replaced by its files
                    }
                    DirCacheEntry e = new DirCacheEntry(p);
                    e.setFileMode(tw.getFileMode(0));
                    e.setObjectId(tw.getObjectId(0));
                    b.add(e);
                }
            }
        }
        for (Map.Entry<String, byte[]> f : new TreeMap<>(files).entrySet()) {
            DirCacheEntry e = new DirCacheEntry(prefix + f.getKey());
            e.setFileMode(FileMode.REGULAR_FILE);
            e.setObjectId(ins.insert(Constants.OBJ_BLOB, f.getValue()));
            b.add(e);
        }
        b.finish();
        return dc.writeTree(ins);
    }

    /** A relative path of plain segments: no empty, {@code .}, {@code ..} or {@code .git} segment, no backslash. */
    static void checkPath(String path) {
        if (path == null || !SAFE_PATH.matcher(path).matches()) {
            throw new GitSyncException("GIT_INVALID", "unsafe path in a package: " + path, null);
        }
        for (String seg : path.split("/")) {
            if (seg.equals(".") || seg.equals("..") || seg.equalsIgnoreCase(".git") || seg.isBlank()
                    || seg.endsWith(" ") || seg.endsWith(".")) {
                throw new GitSyncException("GIT_INVALID", "unsafe path segment in a package: " + path, null);
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // never masks the call's own result; the directory holds fetched objects only, no credential
            LOG.warn("temporary Git directory {} not fully deleted: {}", dir, e.toString());
        }
    }
}
