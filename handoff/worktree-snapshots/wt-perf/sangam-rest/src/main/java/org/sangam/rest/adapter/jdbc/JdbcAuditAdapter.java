// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Default {@link AuditPort} over the core {@link AuditChain} (R42 / X.2.3): class-B events hash-chained per root
 * (tables AUDIT_EVENT / AUDIT_PAYLOAD). Timelines may merge class-A Operaton history, marked {@code evidence=false}
 * (rule D-0). Every read is itself audited ({@code AUDIT_VIEWED}) and every export writes {@code AUDIT_EXPORTED} on the
 * tenant security chain {@code sec:<tenant>}, each in its own transaction; verification writes {@code AUDIT_VERIFIED}
 * (X.2.3.4 rule 5).
 */
public class JdbcAuditAdapter implements AuditPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final int MAX_CHAINS = 10_000;

    private final TxSupport tx;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final RestProperties props;
    private final AuditChain chain;

    public JdbcAuditAdapter(TxSupport tx, HistoryService historyService, RuntimeService runtimeService,
                            RestProperties props) {
        this.tx = tx;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.props = props;
        this.chain = new AuditChain(tx.dialect());
    }

    // ------------------------------------------------------------------ write

    @Override
    public String record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("audit chain id is required");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach((k, v) -> p.put(k, sanitize(v)));
        }
        p.put("actor", sanitize(ctx.actor()));
        String tenant = ctx.tenantId();
        return tx.withConnection(c -> chain.append(c, tenant, chainId, eventType, p).eventId());
    }

    private void securityEvent(RequestContext ctx, String type, Map<String, Object> payload) {
        tx.inNewTx(() -> record(ctx, "sec:" + ctx.tenantId(), type, payload));
    }

    // ------------------------------------------------------------------ read

    @Override
    public Page<AuditEntry> timeline(RequestContext ctx, String businessKey, boolean includeHistory,
                                     Page.PageRequest page) {
        if (businessKey == null || businessKey.isBlank()) {
            throw ApiProblem.badRequest("BUSINESS_KEY_REQUIRED", "businessKey is required");
        }
        List<String> rootIds = rootIds(ctx, businessKey);
        Set<String> chains = new LinkedHashSet<>(rootIds);
        chains.add(businessKey);
        int limit = (int) Math.min(Integer.MAX_VALUE, Math.max(props.getCountCap(), page.firstResult() + page.size()) + 1);
        List<AuditEntry> all = new ArrayList<>(tx.withConnection(c -> readChains(c, ctx.tenantId(), chains, limit)));
        if (includeHistory) {
            for (String root : rootIds) {
                List<HistoricActivityInstance> acts = historyService.createHistoricActivityInstanceQuery()
                        .processInstanceId(root).orderByHistoricActivityInstanceStartTime().asc().listPage(0, limit);
                for (HistoricActivityInstance a : acts) {
                    all.add(historyEntry(a));
                }
            }
        }
        all.sort(Comparator.comparing((AuditEntry e) -> e.occurredAt() == null ? Instant.EPOCH : e.occurredAt())
                .thenComparing(e -> e.seq() == null ? Long.MAX_VALUE : e.seq()));
        Map<String, Object> viewed = new LinkedHashMap<>();
        viewed.put("businessKey", businessKey);
        viewed.put("view", "timeline");
        viewed.put("includeHistory", includeHistory);
        securityEvent(ctx, "AUDIT_VIEWED", viewed);
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Page<AuditEntry> search(RequestContext ctx, AuditFilter filter, Page.PageRequest page) {
        AuditFilter f = filter == null ? new AuditFilter(null, null, null, null, null) : filter;
        StringBuilder where = new StringBuilder(" WHERE e.tenant_id=?");
        List<Object> params = new ArrayList<>();
        params.add(ctx.tenantId());
        if (f.chainId() != null) {
            where.append(" AND e.chain_id=?");
            params.add(f.chainId());
        }
        if (f.type() != null) {
            where.append(" AND e.event_type=?");
            params.add(f.type());
        }
        if (f.from() != null) {
            where.append(" AND e.occurred_at>=?");
            params.add(f.from());
        }
        if (f.to() != null) {
            where.append(" AND e.occurred_at<=?");
            params.add(f.to());
        }
        if (f.actor() != null) {
            where.append(" AND p.payload LIKE ?");
            params.add("%\"principal_id\":" + jsonString(f.actor()) + "%");
        }
        String from = " FROM AUDIT_EVENT e LEFT JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id";
        String sql = "SELECT e.chain_id, e.seq, e.event_id, e.event_type, e.occurred_at, e.hash, e.prev_hash, e.envelope, p.payload, p.erased_at"
                + from + where + " ORDER BY e.occurred_at DESC, e.seq DESC";
        long cap = props.getCountCap();
        Page<AuditEntry> result = tx.withConnection(c -> {
            List<AuditEntry> rows = limited(c, sql, page.firstResult() + page.size(), JdbcAuditAdapter::auditRow,
                    params.toArray());
            List<AuditEntry> items = rows.subList(Math.min(rows.size(), page.firstResult()), rows.size());
            List<Long> probe = limited(c, "SELECT e.seq" + from + where, (int) Math.min(Integer.MAX_VALUE, cap + 1),
                    rs -> rs.getLong(1), params.toArray());
            boolean capped = probe.size() > cap;
            return Page.of(items, capped ? cap : probe.size(), capped);
        });
        Map<String, Object> viewed = new LinkedHashMap<>();
        viewed.put("view", "search");
        viewed.put("chainId", f.chainId());
        viewed.put("type", f.type());
        viewed.put("actor", f.actor());
        securityEvent(ctx, "AUDIT_VIEWED", viewed);
        return result;
    }

    @Override
    public VerifyResult verify(RequestContext ctx, String chainId, String businessKey) {
        String tenant = ctx.tenantId();
        Instant now = Instant.now();
        List<ChainVerification> results = tx.withConnection(c -> {
            Set<String> chains = new LinkedHashSet<>();
            if (chainId != null) {
                chains.add(chainId);
            } else if (businessKey != null) {
                chains.addAll(rootIds(ctx, businessKey));
                chains.add(businessKey);
            } else {
                chains.addAll(limited(c, "SELECT DISTINCT chain_id FROM AUDIT_EVENT WHERE tenant_id=?", MAX_CHAINS,
                        rs -> rs.getString(1), tenant));
            }
            List<ChainVerification> out = new ArrayList<>();
            for (String ch : chains) {
                AuditChain.VerifyResult r = chain.verify(c, tenant, ch);
                out.add(new ChainVerification(ch, r.ok(), r.eventsChecked(), r.firstBadSeq(), r.reason()));
            }
            return out;
        });
        boolean ok = results.stream().allMatch(ChainVerification::ok);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("chainId", chainId);
        p.put("businessKey", businessKey);
        p.put("chains", results.size());
        p.put("ok", ok);
        p.put("failed", results.stream().filter(r -> !r.ok()).map(ChainVerification::chainId).toList());
        securityEvent(ctx, "AUDIT_VERIFIED", p);
        return new VerifyResult(tenant, ok, now, results);
    }

    @Override
    public Download export(RequestContext ctx, String businessKey, String format) {
        if (businessKey == null || businessKey.isBlank()) {
            throw ApiProblem.badRequest("BUSINESS_KEY_REQUIRED", "businessKey is required");
        }
        String fmt = format == null ? "json" : format.toLowerCase(java.util.Locale.ROOT);
        if (!fmt.equals("json") && !fmt.equals("csv")) {
            throw ApiProblem.badRequest("UNSUPPORTED_FORMAT", "format must be json or csv");
        }
        List<String> rootIds = rootIds(ctx, businessKey);
        Set<String> chains = new LinkedHashSet<>(rootIds);
        chains.add(businessKey);
        int limit = (int) Math.min(Integer.MAX_VALUE, props.getCountCap());
        List<AuditEntry> entries = tx.withConnection(c -> readChains(c, ctx.tenantId(), chains, limit));
        byte[] content = fmt.equals("json") ? json(entries) : csv(entries);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("businessKey", businessKey);
        p.put("format", fmt);
        p.put("events", entries.size());
        p.put("sha256", Hashes.hex(Hashes.sha256(content)));
        securityEvent(ctx, "AUDIT_EXPORTED", p);
        String safe = businessKey.replaceAll("[^A-Za-z0-9._-]", "_");
        return new Download(fmt.equals("json") ? "application/json" : "text/csv; charset=UTF-8",
                "audit-" + safe + "." + fmt, content);
    }

    // ------------------------------------------------------------------ helpers

    private List<String> rootIds(RequestContext ctx, String businessKey) {
        var q = historyService.createHistoricProcessInstanceQuery().processInstanceBusinessKey(businessKey);
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        List<String> ids = new ArrayList<>();
        for (HistoricProcessInstance h : q.listPage(0, MAX_CHAINS)) {
            ids.add(h.getId());
        }
        if (ids.isEmpty()) {
            // history level none: fall back to runtime instances
            var rq = runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(businessKey);
            if (ctx.tenantId() != null) {
                rq.tenantIdIn(ctx.tenantId());
            }
            rq.listPage(0, MAX_CHAINS).forEach(pi -> ids.add(pi.getId()));
        }
        return ids;
    }

    private List<AuditEntry> readChains(Connection c, String tenant, Collection<String> chains, int limit) {
        List<AuditEntry> out = new ArrayList<>();
        List<String> list = new ArrayList<>(chains);
        for (int i = 0; i < list.size() && out.size() < limit; i += 500) {
            List<String> part = list.subList(i, Math.min(list.size(), i + 500));
            String in = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            List<Object> params = new ArrayList<>();
            params.add(tenant);
            params.addAll(part);
            out.addAll(limited(c, "SELECT e.chain_id, e.seq, e.event_id, e.event_type, e.occurred_at, e.hash, e.prev_hash, e.envelope, p.payload,"
                            + " p.erased_at FROM AUDIT_EVENT e LEFT JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                            + " WHERE e.tenant_id=? AND e.chain_id IN (" + in + ") ORDER BY e.occurred_at, e.seq",
                    limit - out.size(), JdbcAuditAdapter::auditRow, params.toArray()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static AuditEntry auditRow(ResultSet rs) throws SQLException {
        String chainId = rs.getString("chain_id");
        String payloadText = rs.getString("payload");
        Instant erasedAt = Jdbc.instant(rs, "erased_at");
        Map<String, Object> payload = null;
        if (payloadText != null && erasedAt == null) {
            try {
                payload = JSON.readValue(payloadText, MAP);
            } catch (java.io.IOException e) {
                payload = Map.of("unparsed", payloadText);
            }
        }
        Map<String, Object> actor = payload != null && payload.get("actor") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String pi = payload == null ? null : str(payload.get("processInstanceId"));
        if (pi == null && !chainId.startsWith("sec:")) {
            pi = chainId;
        }
        return new AuditEntry(rs.getString("event_id"), "B", chainId, rs.getLong("seq"), rs.getString("event_type"),
                Jdbc.instant(rs, "occurred_at"), str(actor.get("principal_id")), str(actor.get("seat_id")),
                str(actor.get("acted_for_seat_id")), pi, payload == null ? null : str(payload.get("activityId")),
                payload == null ? null : str(payload.get("taskId")), payload, erasedAt != null,
                Hashes.hex(rs.getBytes("hash")), true, Hashes.hex(rs.getBytes("prev_hash")),
                Hashes.hex(rs.getBytes("hash")), rs.getString("envelope"));
    }

    private static AuditEntry historyEntry(HistoricActivityInstance a) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("activityName", a.getActivityName());
        p.put("activityType", a.getActivityType());
        p.put("endTime", a.getEndTime() == null ? null : a.getEndTime().toInstant().toString());
        p.put("durationMs", a.getDurationInMillis());
        p.put("canceled", a.isCanceled());
        String type = a.getEndTime() == null ? "ACTIVITY_STARTED" : (a.isCanceled() ? "ACTIVITY_CANCELED" : "ACTIVITY_COMPLETED");
        return new AuditEntry(a.getId(), "A", a.getProcessInstanceId(), null, type,
                a.getStartTime() == null ? null : a.getStartTime().toInstant(), a.getAssignee(), null, null,
                a.getProcessInstanceId(), a.getActivityId(), a.getTaskId(), p, false, null, false, null, null, null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** CanonicalJson accepts only integral numbers (R33); decimals and floats are carried as strings. */
    @SuppressWarnings("unchecked")
    static Object sanitize(Object v) {
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            return new BigDecimal(v.toString()).toPlainString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((k, x) -> out.put(String.valueOf(k), sanitize(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(JdbcAuditAdapter::sanitize).toList();
        }
        if (v instanceof Instant || v instanceof java.util.Date || v instanceof java.time.LocalDate || v instanceof Enum<?>) {
            return v instanceof java.util.Date d ? d.toInstant().toString() : v.toString();
        }
        if (v instanceof byte[] b) {
            return Hashes.hex(b);
        }
        return v;
    }

    private static String jsonString(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> List<T> limited(Connection c, String sql, int maxRows, Jdbc.RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (maxRows > 0) {
                ps.setMaxRows(maxRows);
            }
            Jdbc.bind(ps, 1, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next() && (maxRows <= 0 || out.size() < maxRows)) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException(sql, e);
        }
    }

    private static Map<String, Object> asMap(AuditEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.eventId());
        m.put("eventClass", e.eventClass());
        m.put("chainId", e.chainId());
        m.put("seq", e.seq());
        m.put("type", e.type());
        m.put("occurredAt", e.occurredAt() == null ? null : e.occurredAt().toString());
        m.put("actor", e.actor());
        m.put("seatId", e.seatId());
        m.put("actedForSeatId", e.actedForSeatId());
        m.put("processInstanceId", e.processInstanceId());
        m.put("activityId", e.activityId());
        m.put("taskId", e.taskId());
        m.put("payload", e.payload());
        m.put("erased", e.erased());
        m.put("hash", e.hash());
        m.put("prevHash", e.prevHash());
        m.put("entryHash", e.entryHash());
        m.put("evidence", e.evidence());
        return m;
    }

    private static byte[] json(List<AuditEntry> entries) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries.stream().map(JdbcAuditAdapter::asMap).toList());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] csv(List<AuditEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("eventId,chainId,seq,type,occurredAt,actor,seatId,actedForSeatId,processInstanceId,activityId,taskId,"
                + "erased,hash,payload\r\n");
        for (AuditEntry e : entries) {
            String payload;
            try {
                payload = e.payload() == null ? "" : JSON.writeValueAsString(e.payload());
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                payload = "";
            }
            String[] cols = {e.eventId(), e.chainId(), e.seq() == null ? "" : e.seq().toString(), e.type(),
                    e.occurredAt() == null ? "" : e.occurredAt().toString(), e.actor(), e.seatId(), e.actedForSeatId(),
                    e.processInstanceId(), e.activityId(), e.taskId(), Boolean.toString(e.erased()), e.hash(), payload};
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(csvCell(cols[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** RFC 4180 quoting; a leading formula character is neutralised (CSV injection). */
    private static String csvCell(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
