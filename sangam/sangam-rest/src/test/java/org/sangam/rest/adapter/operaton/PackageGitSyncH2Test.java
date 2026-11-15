// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackageGitRemote;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PackageToolsPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * M-100 (R69): {@code PackageToolsPort.gitSync} runs through the host's {@link PackageGitRemote}. Before the binding it
 * answered {@code 501} for every configured package. Fast-forward only: a pull never overwrites a package that changed
 * since the last sync, a push carries the last synced commit as its expected parent, a conflict changes nothing and is
 * shown by the status; without a transport the call stays {@code 501}, without a remote {@code 409}.
 */
class PackageGitSyncH2Test {

    static final RequestContext AUTHOR = new RequestContext("t1", "author-1", Set.of(), null, null, null, null, null, "token");

    DocumentStore docs;
    final Packages packages = new Packages();
    final List<String> audit = new ArrayList<>();
    final FakeRemote remote = new FakeRemote();

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:gitsync" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            // REST_DOCUMENT as in sangam-persistence (changeset 0016-01-rest_document)
            s.execute("CREATE TABLE REST_DOCUMENT (tenant_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL,"
                    + " doc_id VARCHAR(64) NOT NULL, doc_key VARCHAR(255), revision BIGINT NOT NULL, state VARCHAR(32),"
                    + " owner_id VARCHAR(255), ref_id VARCHAR(255), body CHARACTER LARGE OBJECT NOT NULL, created_by VARCHAR(255),"
                    + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " CONSTRAINT PK_REST_DOCUMENT PRIMARY KEY (tenant_id, kind, doc_id))");
        }
        docs = new DocumentStore(new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL));
    }

    /** One DRAFT package "pkg-1" with the given resources; update checks the revision like the JDBC adapter. */
    static final class Packages {
        Map<String, String> resources = new LinkedHashMap<>();
        long revision = 1;
        int updates;

        PackagePort port() {
            return (PackagePort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PackagePort.class}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "get" -> {
                        return pkg();
                    }
                    case "update" -> {
                        if ((long) args[2] != revision) {
                            throw ApiProblem.conflict("REVISION_CONFLICT", "stale");
                        }
                        resources = new LinkedHashMap<>(((PackagePort.UpdatePackageRequest) args[3]).resources());
                        revision++;
                        updates++;
                        return pkg();
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                }
            });
        }

        PackagePort.Package pkg() {
            return new PackagePort.Package("pkg-1", "orders", "Orders", revision, "DRAFT", null, Map.of(), "author-1", null, null,
                    null, null, null, null, Instant.EPOCH, Instant.EPOCH, List.of(), resources);
        }
    }

    /** The remote branch: its files and tip; push replaces the files unless the expected parent is stale. */
    static final class FakeRemote implements PackageGitRemote {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String tip = "c0";
        int commits;
        final List<String> expectedParents = new ArrayList<>();
        Remote seen;

        @Override
        public Snapshot pull(String tenantId, Remote remote) {
            seen = remote;
            return new Snapshot(tip, files);
        }

        @Override
        public Pushed push(String tenantId, Remote remote, Map<String, byte[]> pushed, String message, String authorName,
                           String expectedParent) {
            seen = remote;
            expectedParents.add(String.valueOf(expectedParent));
            if (expectedParent != null && !expectedParent.equals(tip)) {
                return new Pushed("CONFLICT", null, tip, "the remote branch moved since " + expectedParent);
            }
            String parent = tip;
            files = new LinkedHashMap<>(pushed);
            tip = "c" + (++commits);
            return new Pushed("PUSHED", tip, parent, "pushed");
        }
    }

    AuditPort auditPort() {
        return (AuditPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AuditPort.class}, (proxy, m, args) -> {
            if (m.getName().equals("record")) {
                audit.add(args[2] + "@" + args[1]);
                return "evt";
            }
            throw new UnsupportedOperationException(m.getName());
        });
    }

    OperatonPackageToolsAdapter tools(PackageGitRemote transport) {
        return new OperatonPackageToolsAdapter(packages.port(), auditPort(), null, docs, () -> transport);
    }

    OperatonPackageToolsAdapter configured(PackageGitRemote transport) {
        OperatonPackageToolsAdapter t = tools(transport);
        t.configureGit(AUTHOR, "pkg-1", new PackageToolsPort.GitConfig("https://git.example.org/orders.git", "main",
                "secret://git/orders", "gitlab"));
        audit.clear();
        return t;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aPullFillsAnEmptyPackageFromTheRemoteAndRecordsTheSync() {
        remote.files.put("orders.bpmn", utf8("<definitions/>"));
        remote.files.put("logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        OperatonPackageToolsAdapter t = configured(remote);

        PackageToolsPort.GitSyncResult r = t.gitSync(AUTHOR, "pkg-1", "pull", null);

        assertEquals(List.of(), r.conflicts());
        assertEquals("<definitions/>", packages.resources.get("orders.bpmn"), "text resources as UTF-8 text");
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Base64.getDecoder().decode(packages.resources.get("logo.png")),
                "other resources as base64");
        assertEquals(2, packages.revision);
        assertEquals(List.of("PACKAGE_GIT_PULLED@pkg:orders"), audit);
        assertEquals("https://git.example.org/orders.git", remote.seen.uri());
        assertEquals("secret://git/orders", remote.seen.credentialRef(), "the credential travels by reference only");
        PackageToolsPort.GitStatus st = t.gitStatus(AUTHOR, "pkg-1");
        assertNotNull(st.lastSync());
        assertEquals(List.of(), st.conflicts());
    }

    @Test
    void aPushCarriesTheLastSyncedCommitAndAMovedRemoteIsAConflictThatChangesNothing() {
        remote.files.put("orders.bpmn", utf8("<v1/>"));
        OperatonPackageToolsAdapter t = configured(remote);
        t.gitSync(AUTHOR, "pkg-1", "pull", null);
        packages.resources.put("orders.bpmn", "<v2/>");
        packages.revision++;

        PackageToolsPort.GitSyncResult pushed = t.gitSync(AUTHOR, "pkg-1", "push", new PackageToolsPort.GitSyncRequest("v2"));
        assertEquals(List.of(), pushed.conflicts());
        assertEquals(List.of("c0"), remote.expectedParents, "the commit of the pull is the expected parent");
        assertArrayEquals(utf8("<v2/>"), remote.files.get("orders.bpmn"));

        // someone else pushes; the next push from here is refused, the remote keeps their commit
        remote.tip = "c-other";
        remote.files.put("orders.bpmn", utf8("<theirs/>"));
        packages.resources.put("orders.bpmn", "<v3/>");
        packages.revision++;
        audit.clear();
        PackageToolsPort.GitSyncResult refused = t.gitSync(AUTHOR, "pkg-1", "push", null);
        assertEquals(1, refused.conflicts().size());
        assertArrayEquals(utf8("<theirs/>"), remote.files.get("orders.bpmn"));
        assertEquals(List.of("PACKAGE_GIT_CONFLICT@pkg:orders"), audit);
        assertEquals(refused.conflicts(), t.gitStatus(AUTHOR, "pkg-1").conflicts(), "the status shows the open conflict");
    }

    @Test
    void aPullNeverOverwritesAPackageThatChangedSinceTheLastSync() {
        remote.files.put("orders.bpmn", utf8("<v1/>"));
        OperatonPackageToolsAdapter t = configured(remote);
        t.gitSync(AUTHOR, "pkg-1", "pull", null);
        packages.resources.put("orders.bpmn", "<local/>");
        packages.revision++;
        remote.files.put("orders.bpmn", utf8("<remote/>"));
        remote.tip = "c9";
        int updates = packages.updates;

        PackageToolsPort.GitSyncResult r = t.gitSync(AUTHOR, "pkg-1", "pull", null);

        assertEquals(1, r.conflicts().size());
        assertEquals("<local/>", packages.resources.get("orders.bpmn"));
        assertEquals(updates, packages.updates, "no package update");
    }

    @Test
    void aFirstPullIntoANonEmptyPackageThatDiffersIsAConflict() {
        packages.resources.put("orders.bpmn", "<mine/>");
        remote.files.put("orders.bpmn", utf8("<theirs/>"));
        PackageToolsPort.GitSyncResult r = configured(remote).gitSync(AUTHOR, "pkg-1", "pull", null);
        assertEquals(1, r.conflicts().size());
        assertEquals("<mine/>", packages.resources.get("orders.bpmn"));
        assertNull(configured(remote).gitStatus(AUTHOR, "pkg-1").lastSync(), "never synced");
    }

    @Test
    void aTextResourceThatIsNotUtf8IsRefusedByName() {
        remote.files.put("orders.bpmn", new byte[] {(byte) 0xC3, (byte) 0x28});
        ApiProblem p = assertThrows(ApiProblem.class, () -> configured(remote).gitSync(AUTHOR, "pkg-1", "pull", null));
        assertEquals(422, p.status());
        assertEquals("GIT_INVALID", p.code());
        assertTrue(packages.resources.isEmpty());
    }

    @Test
    void anEmptyPackageIsNeverPushed() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> configured(remote).gitSync(AUTHOR, "pkg-1", "push", null));
        assertEquals(400, p.status());
        assertTrue(remote.expectedParents.isEmpty(), "the remote was not called");
    }

    @Test
    void withoutATransportItIsNotInstalledAndWithoutARemoteNotConfigured() {
        // first without a stored remote (configured(...) below stores one for pkg-1)
        ApiProblem unconfigured = assertThrows(ApiProblem.class, () -> tools(remote).gitSync(AUTHOR, "pkg-1", "push", null));
        assertEquals(409, unconfigured.status());
        assertEquals("GIT_NOT_CONFIGURED", unconfigured.code());
        ApiProblem none = assertThrows(ApiProblem.class, () -> configured(null).gitSync(AUTHOR, "pkg-1", "pull", null));
        assertEquals(501, none.status());
        assertNull(remote.seen);
    }
}
