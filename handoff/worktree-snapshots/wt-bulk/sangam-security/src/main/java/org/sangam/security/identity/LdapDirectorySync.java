// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.identity;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.admin.GroupAdminService;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LDAP / Active Directory read-and-sync (R67 at Stage C, A-17 parity with Operaton {@code identity-ldap} and Flowable
 * {@code flowable-ldap}). Uses the JDK's JNDI LDAP provider only (no new dependency). It reads users and groups and
 * synchronises them into {@code SEC_PERSON} and {@code SEC_GROUP}/{@code SEC_GROUP_MEMBER} with source {@code LDAP}:
 * new users are created, users missing from the directory are disabled, group memberships are added and removed to
 * match. Seats and postings are never derived from the directory (AJ-05 step 3). The integrator schedules
 * {@link #sync}; the bind password is passed by reference from the server's secret store.
 */
public final class LdapDirectorySync {

    /** Directory settings. {@code userIdAttribute} is matched to the IdP subject ({@code external_subject}). */
    public record Config(String url, String bindDn, char[] bindPassword, String userBase, String userFilter,
                         String userIdAttribute, String displayNameAttribute, String mailAttribute, String groupBase,
                         String groupFilter, String groupIdAttribute, String memberAttribute, boolean startTls) {
    }

    public record SyncResult(int usersCreated, int usersDisabled, int groupsCreated, int membershipsAdded,
                             int membershipsRemoved) {
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final GroupAdminService groups;
    private final SecurityAuditService audit;

    public LdapDirectorySync(SecurityDialect dialect, AuthorizationService authz, GroupAdminService groups,
                             SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.groups = groups;
        this.audit = audit;
    }

    public SyncResult sync(Connection c, SecurityIdentity admin, Config cfg) {
        authz.require(c, admin, ResourceRegistry.USER, Permission.ADMIN, ResourceRegistry.ANY_ID);
        String t = admin.tenantId();
        Map<String, String[]> users;      // dn -> {id, name, mail}
        Map<String, List<String>> ldapGroups;  // group id -> member DNs
        try {
            LdapContext ctx = connect(cfg);
            try {
                users = searchUsers(ctx, cfg);
                ldapGroups = searchGroups(ctx, cfg);
            } finally {
                ctx.close();
            }
        } catch (NamingException e) {
            throw new IllegalStateException("LDAP sync failed: " + e.getMessage(), e);
        }
        int created = 0;
        int disabled = 0;
        Set<String> ids = new LinkedHashSet<>();
        for (String[] u : users.values()) {
            ids.add(u[0]);
            boolean inserted = Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name,"
                    + " external_subject, email, status, created_at) VALUES (?,?,?,?,?,'ACTIVE',?)",
                    t, u[0], u[1], u[0], u[2], Sql.dbNow(c, dialect));
            if (inserted) {
                created++;
                Jdbc.update(c, "UPDATE SEC_PERSON SET source='LDAP' WHERE tenant_id=? AND person_id=?", t, u[0]);
            } else {
                Jdbc.update(c, "UPDATE SEC_PERSON SET display_name=?, email=? WHERE tenant_id=? AND person_id=? AND source='LDAP'",
                        u[1], u[2], t, u[0]);
            }
        }
        for (String existing : Jdbc.query(c, "SELECT person_id FROM SEC_PERSON WHERE tenant_id=? AND source='LDAP' AND status='ACTIVE'",
                rs -> rs.getString(1), t)) {
            if (!ids.contains(existing)) {
                Jdbc.update(c, "UPDATE SEC_PERSON SET status='DISABLED' WHERE tenant_id=? AND person_id=?", t, existing);
                disabled++;
            }
        }
        int groupsCreated = 0;
        int added = 0;
        int removed = 0;
        Map<String, String> dnToId = new HashMap<>();
        users.forEach((dn, u) -> dnToId.put(dn.toLowerCase(), u[0]));
        for (Map.Entry<String, List<String>> g : ldapGroups.entrySet()) {
            String gid = g.getKey();
            long exists = Jdbc.one(c, "SELECT COUNT(*) FROM SEC_GROUP WHERE tenant_id=? AND group_id=?", rs -> rs.getLong(1), t, gid).orElse(0L);
            if (exists == 0) {
                groups.createGroup(c, admin, gid, gid, "ldap", "LDAP");
                groupsCreated++;
            }
            Set<String> wanted = new LinkedHashSet<>();
            for (String dn : g.getValue()) {
                String pid = dnToId.get(dn.toLowerCase());
                if (pid != null) {
                    wanted.add(pid);
                }
            }
            List<String> current = Jdbc.query(c, "SELECT person_id FROM SEC_GROUP_MEMBER WHERE tenant_id=? AND group_id=? AND source='LDAP'",
                    rs -> rs.getString(1), t, gid);
            for (String pid : wanted) {
                if (!current.contains(pid)) {
                    groups.addMember(c, admin, gid, pid, "LDAP");
                    added++;
                }
            }
            for (String pid : current) {
                if (!wanted.contains(pid)) {
                    groups.removeMember(c, admin, gid, pid);
                    removed++;
                }
            }
        }
        SyncResult r = new SyncResult(created, disabled, groupsCreated, added, removed);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("users_created", created);
        p.put("users_disabled", disabled);
        p.put("groups_created", groupsCreated);
        p.put("memberships_added", added);
        p.put("memberships_removed", removed);
        audit.security(c, admin, SecurityEventType.DIRECTORY_SYNCED, p);
        return r;
    }

    private static LdapContext connect(Config cfg) throws NamingException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, cfg.url());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, cfg.bindDn());
        env.put(Context.SECURITY_CREDENTIALS, new String(cfg.bindPassword()));
        env.put("com.sun.jndi.ldap.connect.timeout", "10000");
        env.put("com.sun.jndi.ldap.read.timeout", "30000");
        env.put(Context.REFERRAL, "ignore");
        if (!cfg.url().startsWith("ldaps:") && !cfg.startTls()) {
            throw new NamingException("refusing a plain-text LDAP bind: use ldaps:// or StartTLS");
        }
        LdapContext ctx = new InitialLdapContext(env, null);
        if (cfg.startTls()) {
            javax.naming.ldap.StartTlsResponse tls = (javax.naming.ldap.StartTlsResponse)
                    ctx.extendedOperation(new javax.naming.ldap.StartTlsRequest());
            try {
                tls.negotiate();
            } catch (java.io.IOException e) {
                throw new NamingException("StartTLS failed: " + e.getMessage());
            }
        }
        return ctx;
    }

    private static Map<String, String[]> searchUsers(LdapContext ctx, Config cfg) throws NamingException {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setReturningAttributes(new String[]{cfg.userIdAttribute(), cfg.displayNameAttribute(), cfg.mailAttribute()});
        Map<String, String[]> out = new LinkedHashMap<>();
        NamingEnumeration<SearchResult> e = ctx.search(cfg.userBase(), cfg.userFilter(), sc);
        while (e.hasMore()) {
            SearchResult r = e.next();
            Attributes a = r.getAttributes();
            String id = first(a.get(cfg.userIdAttribute()));
            if (id != null) {
                out.put(r.getNameInNamespace(), new String[]{id, first(a.get(cfg.displayNameAttribute())), first(a.get(cfg.mailAttribute()))});
            }
        }
        return out;
    }

    private static Map<String, List<String>> searchGroups(LdapContext ctx, Config cfg) throws NamingException {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setReturningAttributes(new String[]{cfg.groupIdAttribute(), cfg.memberAttribute()});
        Map<String, List<String>> out = new LinkedHashMap<>();
        NamingEnumeration<SearchResult> e = ctx.search(cfg.groupBase(), cfg.groupFilter(), sc);
        while (e.hasMore()) {
            SearchResult r = e.next();
            Attributes a = r.getAttributes();
            String id = first(a.get(cfg.groupIdAttribute()));
            if (id == null) {
                continue;
            }
            List<String> members = new ArrayList<>();
            Attribute m = a.get(cfg.memberAttribute());
            if (m != null) {
                NamingEnumeration<?> vals = m.getAll();
                while (vals.hasMore()) {
                    members.add(String.valueOf(vals.next()));
                }
            }
            out.put(id, members);
        }
        return out;
    }

    private static String first(Attribute a) throws NamingException {
        return a == null || a.size() == 0 ? null : String.valueOf(a.get());
    }
}
