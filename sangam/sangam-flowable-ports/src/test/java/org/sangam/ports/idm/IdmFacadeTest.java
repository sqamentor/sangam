// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.idm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.IdentityExtrasPort;
import org.sangam.core.security.SecurityIdentity;

/**
 * FC-42 IDM facade: Flowable user/group vocabulary onto WS-SECURITY principal ids, every call in one transaction, no
 * state of its own (an in-memory port stands in for WS-SECURITY's store, with deny-wins).
 */
class IdmFacadeTest {

    private static final SecurityIdentity ADMIN = SecurityIdentity.system("acc", "admin");
    private static final SecurityIdentity ALICE = new SecurityIdentity("acc", "alice", "alice", Set.of("ops"), Set.of(), Set.of(), Set.of(),
            "PASSWORD", null, null, false, Map.of());

    /** Counts transactions and hands out a sentinel connection; a failure inside the work propagates (rollback). */
    static final class CountingTx implements Transactions {
        int opened;
        int inside;

        @Override
        public <T> T inTransaction(Function<Connection, T> work) {
            opened++;
            inside++;
            try {
                return work.apply(SENTINEL);
            } finally {
                inside--;
            }
        }
    }

    static final Connection SENTINEL = (Connection) java.lang.reflect.Proxy.newProxyInstance(IdmFacadeTest.class.getClassLoader(),
            new Class<?>[]{Connection.class}, (p, m, a) -> {
                throw new UnsupportedOperationException(m.getName());
            });

    /** Minimal WS-SECURITY stand-in: grants and denies by principal id, deny wins; tokens keyed by plaintext. */
    static final class InMemorySecurity implements IdentityExtrasPort {
        final CountingTx tx;
        final Map<String, String> privileges = new HashMap<>();
        final Set<String> grants = new HashSet<>();
        final Set<String> denies = new HashSet<>();
        final Map<String, TokenInfo> tokens = new HashMap<>();
        final Map<String, String> plaintextToId = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        InMemorySecurity(CountingTx tx) {
            this.tx = tx;
        }

        private void check(Connection c, String call) {
            assertTrue(c == SENTINEL && tx.inside == 1, call + " runs inside exactly one facade transaction");
            calls.add(call);
        }

        @Override
        public void createPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String name) {
            check(c, "createPrivilege");
            if (privileges.putIfAbsent(privilegeId, name) != null) {
                throw new IllegalStateException("privilege " + privilegeId + " exists");
            }
        }

        @Override
        public void mapPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String principalId, boolean deny) {
            check(c, "map " + privilegeId + " " + principalId + (deny ? " deny" : ""));
            (deny ? denies : grants).add(privilegeId + "|" + principalId);
        }

        @Override
        public void unmapPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String principalId) {
            check(c, "unmap " + privilegeId + " " + principalId);
            grants.remove(privilegeId + "|" + principalId);
        }

        @Override
        public boolean hasPrivilege(Connection c, SecurityIdentity who, String privilegeId) {
            check(c, "has");
            List<String> ids = principals(who);
            if (ids.stream().anyMatch(id -> denies.contains(privilegeId + "|" + id))) {
                return false;
            }
            return ids.stream().anyMatch(id -> grants.contains(privilegeId + "|" + id));
        }

        @Override
        public List<String> privilegesOf(Connection c, SecurityIdentity who) {
            check(c, "privilegesOf");
            return privileges.keySet().stream().sorted().filter(p -> {
                List<String> ids = principals(who);
                return ids.stream().noneMatch(id -> denies.contains(p + "|" + id)) && ids.stream().anyMatch(id -> grants.contains(p + "|" + id));
            }).toList();
        }

        @Override
        public IssuedToken issueToken(Connection c, SecurityIdentity actor, String ownerPersonId, String name, Set<String> scopes, Instant expiresAt) {
            check(c, "issue");
            String id = "t" + (tokens.size() + 1);
            tokens.put(id, new TokenInfo(id, ownerPersonId, name, List.copyOf(scopes), expiresAt, false));
            plaintextToId.put("secret-" + id, id);
            return new IssuedToken(id, "secret-" + id, expiresAt);
        }

        @Override
        public Optional<SecurityIdentity> authenticateToken(Connection c, String presented, String clientIp) {
            check(c, "authenticate");
            TokenInfo t = tokens.get(plaintextToId.get(presented));
            if (t == null || t.revoked() || !t.expiresAt().isAfter(SangamClockHolder.now())) {
                return Optional.empty();
            }
            return Optional.of(new SecurityIdentity("acc", t.ownerPersonId(), t.ownerPersonId(), Set.of(), Set.of(), Set.of(), Set.of(),
                    "TOKEN", clientIp, null, false, Map.of()));
        }

        @Override
        public void revokeToken(Connection c, SecurityIdentity actor, String tokenId) {
            check(c, "revoke");
            TokenInfo t = tokens.get(tokenId);
            tokens.put(tokenId, new TokenInfo(t.tokenId(), t.ownerPersonId(), t.name(), t.scopes(), t.expiresAt(), true));
        }

        @Override
        public int revokeAllTokensOf(Connection c, SecurityIdentity actor, String personId) {
            check(c, "revokeAll");
            int n = 0;
            for (TokenInfo t : List.copyOf(tokens.values())) {
                if (t.ownerPersonId().equals(personId) && !t.revoked()) {
                    tokens.put(t.tokenId(), new TokenInfo(t.tokenId(), t.ownerPersonId(), t.name(), t.scopes(), t.expiresAt(), true));
                    n++;
                }
            }
            return n;
        }

        @Override
        public List<TokenInfo> tokensOf(Connection c, SecurityIdentity actor, String ownerPersonId) {
            check(c, "tokensOf");
            return tokens.values().stream().filter(t -> t.ownerPersonId().equals(ownerPersonId)).toList();
        }

        private static List<String> principals(SecurityIdentity who) {
            List<String> ids = new ArrayList<>();
            ids.add(SecurityIdentity.USER + who.personId());
            who.groups().forEach(g -> ids.add(SecurityIdentity.GROUP + g));
            return ids;
        }
    }

    private final CountingTx tx = new CountingTx();
    private final InMemorySecurity sec = new InMemorySecurity(tx);
    private final IdmFacade idm = new IdmFacade(sec, tx);

    @AfterEach
    void resetClock() {
        SangamClockHolder.reset();
    }

    @Test
    void userAndGroupMappingsUseThePrefixedPrincipalIds() {
        idm.createPrivilege(ADMIN, "access-admin", "Admin console");
        idm.addUserPrivilegeMapping(ADMIN, "access-admin", "alice");
        idm.addGroupPrivilegeMapping(ADMIN, "access-admin", "ops");
        assertEquals(List.of("createPrivilege", "map access-admin user:alice", "map access-admin group:ops"), sec.calls);
        assertTrue(idm.hasPrivilege(ALICE, "access-admin"));
        assertEquals(List.of("access-admin"), idm.privilegesOf(ALICE));

        idm.deleteUserPrivilegeMapping(ADMIN, "access-admin", "alice");
        assertTrue(idm.hasPrivilege(ALICE, "access-admin"), "still granted through group ops");
        idm.deleteGroupPrivilegeMapping(ADMIN, "access-admin", "ops");
        assertFalse(idm.hasPrivilege(ALICE, "access-admin"));
        assertTrue(sec.calls.contains("unmap access-admin user:alice"));
        assertTrue(sec.calls.contains("unmap access-admin group:ops"));
        assertEquals(sec.calls.size(), tx.opened, "one transaction per facade call");
    }

    @Test
    void anExplicitDenyWinsOverAGroupGrant() {
        idm.createPrivilege(ADMIN, "export", "Export");
        idm.addGroupPrivilegeMapping(ADMIN, "export", "ops");
        idm.denyPrivilege(ADMIN, "export", SecurityIdentity.USER + "alice");
        assertTrue(sec.calls.contains("map export user:alice deny"), "deny takes a full principal id as given");
        assertFalse(idm.hasPrivilege(ALICE, "export"));
        assertEquals(List.of(), idm.privilegesOf(ALICE));
    }

    @Test
    void aPortFailurePropagatesOutOfTheTransaction() {
        idm.createPrivilege(ADMIN, "p", "P");
        assertThrows(IllegalStateException.class, () -> idm.createPrivilege(ADMIN, "p", "P again"));
        assertEquals(0, tx.inside);
    }

    @Test
    void tokensExpireRelativeToTheSangamClockAndCanBeRevoked() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        SangamClockHolder.set(() -> now);
        IdentityExtrasPort.IssuedToken t = idm.createToken(ADMIN, "alice", "ci", Set.of("work:read"), Duration.ofHours(2));
        assertEquals(now.plus(Duration.ofHours(2)), t.expiresAt());
        assertEquals("alice", idm.authenticateToken(t.plaintext(), "10.0.0.1").orElseThrow().personId());
        assertEquals(List.of("work:read"), idm.tokensOf(ADMIN, "alice").get(0).scopes());

        SangamClockHolder.set(() -> now.plus(Duration.ofHours(3)));
        assertTrue(idm.authenticateToken(t.plaintext(), null).isEmpty(), "expired");
        SangamClockHolder.set(() -> now);

        idm.deleteToken(ADMIN, t.tokenId());
        assertTrue(idm.authenticateToken(t.plaintext(), null).isEmpty(), "revoked");
        IdentityExtrasPort.IssuedToken t2 = idm.createToken(ADMIN, "alice", "a", Set.of(), Duration.ofHours(1));
        idm.createToken(ADMIN, "alice", "b", Set.of(), Duration.ofHours(1));
        assertEquals(2, idm.deleteAllTokensOf(ADMIN, "alice"));
        assertTrue(idm.authenticateToken(t2.plaintext(), null).isEmpty());
    }
}
