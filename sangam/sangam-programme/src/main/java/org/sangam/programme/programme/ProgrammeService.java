// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.programme;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.ports.support.Paging;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.indicator.IndicatorRegistry;
import org.sangam.programme.support.Db;

import java.math.BigDecimal;
import org.sangam.core.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Programmes, schemes, components and projects as one hierarchy. The kinds and which kind may sit under which are
 * configuration ({@link ProgrammeConfig#programmeKinds()}); the code knows only "a node with a kind". Each node lists
 * the indicators it tracks (keys of the configuration).
 */
public final class ProgrammeService {

    public enum Status { DRAFT, ACTIVE, SUSPENDED, COMPLETED, CLOSED }

    public record Programme(String id, String parentId, String kind, String code, String name, String ownerOrg, LocalDate startsOn,
                            LocalDate endsOn, Status status, Money budget, String currency, List<String> indicators,
                            Map<String, Object> attributes, int rowVersion, String createdBy, Instant createdAt, Instant updatedAt) {
    }

    public record ProgrammeInput(String parentId, String kind, String code, String name, String ownerOrg, LocalDate startsOn,
                                 LocalDate endsOn, BigDecimal budget, String currency, List<String> indicators,
                                 Map<String, Object> attributes) {
    }

    public record Node(Programme programme, List<Node> children) {
    }

    private static final int MAX_TREE = 5_000;

    private final Dialect dialect;
    private final IndicatorRegistry registry;

    public ProgrammeService(Dialect dialect, IndicatorRegistry registry) {
        this.dialect = dialect;
        this.registry = registry;
    }

    public Programme create(Connection c, String tenant, ProgrammeInput in, String actor) {
        String t = Db.tenant(tenant);
        Db.requireText(in.code(), "code");
        Db.requireText(in.name(), "name");
        Db.requireText(in.kind(), "kind");
        ProgrammeConfig cfg = registry.config(c, t);
        String parentKind = null;
        if (in.parentId() != null) {
            parentKind = get(c, t, in.parentId()).kind();
        }
        if (!cfg.kindAllowed(in.kind(), parentKind)) {
            throw ProgrammeException.invalid("kind " + in.kind() + " is not allowed under " + (parentKind == null ? "the top level" : parentKind));
        }
        checkIndicators(cfg, in.indicators());
        checkDates(in.startsOn(), in.endsOn());
        String id = Db.newId();
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "INSERT INTO PG_PROGRAMME(tenant_id, programme_id, parent_id, kind, code, name, owner_org, starts_on, ends_on, status,"
                        + " budget, currency, indicators_json, attributes_json, row_version, created_by, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?)",
                t, id, in.parentId(), in.kind(), in.code(), in.name(), in.ownerOrg(), Db.sqlDate(in.startsOn()), Db.sqlDate(in.endsOn()),
                Status.DRAFT.name(), minor(in.budget(), in.currency()), in.currency(), Db.json(in.indicators() == null ? List.of() : in.indicators()),
                Db.json(in.attributes()), actor, now, now);
        return get(c, t, id);
    }

    /** Updates the descriptive fields (not the parent or kind); optimistic on {@code expectedVersion}. */
    public Programme update(Connection c, String tenant, String id, int expectedVersion, ProgrammeInput in, String actor) {
        String t = Db.tenant(tenant);
        Programme p = get(c, t, id);
        ProgrammeConfig cfg = registry.config(c, t);
        checkIndicators(cfg, in.indicators());
        checkDates(in.startsOn(), in.endsOn());
        Db.requireOne(Jdbc.update(c, "UPDATE PG_PROGRAMME SET name=?, owner_org=?, starts_on=?, ends_on=?, budget=?, currency=?, indicators_json=?,"
                        + " attributes_json=?, row_version=row_version+1, updated_at=" + dialect.now() + " WHERE tenant_id=? AND programme_id=? AND row_version=?",
                in.name() == null ? p.name() : in.name(), in.ownerOrg() == null ? p.ownerOrg() : in.ownerOrg(),
                Db.sqlDate(in.startsOn() == null ? p.startsOn() : in.startsOn()), Db.sqlDate(in.endsOn() == null ? p.endsOn() : in.endsOn()),
                budgetMinor(in, p), in.currency() == null ? p.currency() : in.currency(),
                Db.json(in.indicators() == null ? p.indicators() : in.indicators()), Db.json(in.attributes() == null ? p.attributes() : in.attributes()),
                t, id, expectedVersion), "programme");
        return get(c, t, id);
    }

    public Programme changeStatus(Connection c, String tenant, String id, Status to, String actor) {
        String t = Db.tenant(tenant);
        Programme p = get(c, t, id);
        boolean ok = switch (p.status()) {
            case DRAFT -> to == Status.ACTIVE || to == Status.CLOSED;
            case ACTIVE -> to == Status.SUSPENDED || to == Status.COMPLETED || to == Status.CLOSED;
            case SUSPENDED -> to == Status.ACTIVE || to == Status.CLOSED;
            case COMPLETED -> to == Status.CLOSED || to == Status.ACTIVE;
            case CLOSED -> false;
        };
        if (!ok) {
            throw ProgrammeException.state("programme " + p.code() + ": " + p.status() + " -> " + to + " not allowed");
        }
        Db.requireOne(Jdbc.update(c, "UPDATE PG_PROGRAMME SET status=?, row_version=row_version+1, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND programme_id=? AND status=?", to.name(), t, id, p.status().name()), "programme");
        return get(c, t, id);
    }

    public Programme get(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT * FROM PG_PROGRAMME WHERE tenant_id=? AND programme_id=?", ProgrammeService::map, Db.tenant(tenant), id)
                .orElseThrow(() -> ProgrammeException.notFound("programme", id));
    }

    public List<Programme> children(Connection c, String tenant, String id) {
        return id == null
                ? Jdbc.query(c, "SELECT * FROM PG_PROGRAMME WHERE tenant_id=? AND parent_id IS NULL ORDER BY code", ProgrammeService::map, Db.tenant(tenant))
                : Jdbc.query(c, "SELECT * FROM PG_PROGRAMME WHERE tenant_id=? AND parent_id=? ORDER BY code", ProgrammeService::map, Db.tenant(tenant), id);
    }

    /** The node and every descendant (breadth first, bounded). */
    public List<Programme> subtree(Connection c, String tenant, String id) {
        List<Programme> out = new ArrayList<>();
        Deque<Programme> q = new ArrayDeque<>();
        q.add(get(c, tenant, id));
        Set<String> seen = new LinkedHashSet<>();
        while (!q.isEmpty()) {
            Programme p = q.poll();
            if (!seen.add(p.id())) {
                continue;
            }
            out.add(p);
            if (out.size() > MAX_TREE) {
                throw ProgrammeException.invalid("programme tree larger than " + MAX_TREE + " nodes");
            }
            q.addAll(children(c, tenant, p.id()));
        }
        return out;
    }

    public Node tree(Connection c, String tenant, String id) {
        Programme p = get(c, tenant, id);
        return build(c, tenant, p, 0);
    }

    private Node build(Connection c, String tenant, Programme p, int depth) {
        if (depth > 32) {
            throw ProgrammeException.invalid("programme tree deeper than 32 levels");
        }
        List<Node> kids = new ArrayList<>();
        for (Programme ch : children(c, tenant, p.id())) {
            kids.add(build(c, tenant, ch, depth + 1));
        }
        return new Node(p, kids);
    }

    /** Ancestors from the parent up to the root. */
    public List<Programme> ancestors(Connection c, String tenant, String id) {
        List<Programme> out = new ArrayList<>();
        Programme p = get(c, tenant, id);
        Set<String> seen = new LinkedHashSet<>();
        while (p.parentId() != null && seen.add(p.parentId())) {
            p = get(c, tenant, p.parentId());
            out.add(p);
        }
        return out;
    }

    /** Paged list under the caller's VI.4 predicate (alias {@code p}); capped count (A-16). */
    public Paging.PageResult<Programme> list(Connection c, String tenant, String kind, String parentId, Status status, SqlPredicate visible,
                                             Paging.PageRequest page) {
        StringBuilder where = new StringBuilder(" FROM PG_PROGRAMME p WHERE p.tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(Db.tenant(tenant)));
        if (kind != null) {
            where.append(" AND p.kind=?");
            params.add(kind);
        }
        if (parentId != null) {
            where.append(" AND p.parent_id=?");
            params.add(parentId);
        }
        if (status != null) {
            where.append(" AND p.status=?");
            params.add(status.name());
        }
        if (visible != null) {
            where.append(" AND (").append(visible.sql()).append(')');
            params.addAll(visible.params());
        }
        long total = Jdbc.one(c, Paging.cappedCount(dialect, where.toString()), rs -> rs.getLong(1), params.toArray()).orElse(0L);
        List<Programme> items = Jdbc.query(c, "SELECT p.*" + where + " ORDER BY p.code" + Paging.window(dialect, page),
                ProgrammeService::map, params.toArray());
        return Paging.of(items, total, page);
    }

    private static void checkIndicators(ProgrammeConfig cfg, List<String> keys) {
        if (keys == null) {
            return;
        }
        for (String k : keys) {
            if (!cfg.indicators().containsKey(k)) {
                throw ProgrammeException.invalid("unknown indicator " + k);
            }
        }
    }

    private static void checkDates(LocalDate s, LocalDate e) {
        if (s != null && e != null && e.isBefore(s)) {
            throw ProgrammeException.invalid("end date before start date");
        }
    }

    /** API boundary (SD-398): a budget is exact in its currency's minor units; more fraction digits are rejected. */
    static Long minor(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        if (currency == null) {
            throw ProgrammeException.invalid("budget needs a currency (ISO 4217)");
        }
        try {
            return Money.ofMajor(amount, currency).minor();
        } catch (RuntimeException e) {
            throw ProgrammeException.invalid("budget: " + e.getMessage());
        }
    }

    private static Long budgetMinor(ProgrammeInput in, Programme p) {
        String ccy = in.currency() == null ? p.currency() : in.currency();
        if (in.budget() != null) {
            return minor(in.budget(), ccy);
        }
        if (p.budget() != null && !p.budget().currencyCode().equals(ccy)) {
            throw ProgrammeException.invalid("changing the currency requires restating the budget");
        }
        return p.budget() == null ? null : p.budget().minor();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Programme map(ResultSet rs) throws SQLException {
        List<String> inds = (List) Db.list(rs.getString("indicators_json"));
        return new Programme(rs.getString("programme_id"), rs.getString("parent_id"), rs.getString("kind"), rs.getString("code"),
                rs.getString("name"), rs.getString("owner_org"), Db.date(rs, "starts_on"), Db.date(rs, "ends_on"),
                Status.valueOf(rs.getString("status")), Db.money(rs, "budget"), rs.getString("currency"), inds,
                Db.map(rs.getString("attributes_json")), rs.getInt("row_version"), rs.getString("created_by"),
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
    }
}
