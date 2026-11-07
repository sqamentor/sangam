// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenant export / import / checksum / purge for cell-to-cell tenant moves (IR-4; contract
 * {@code sangam-control-plane/.../move/CellOps}). Implemented over JDBC because neither sangam-cell nor the control
 * plane provides a row mover.
 *
 * <p><b>Rows of a tenant.</b> Every table of the current schema (DatabaseMetaData) with a {@code tenant_id} /
 * {@code TENANT_ID_} column, filtered on it, plus tenant-less child tables by their parent
 * ({@code AUDIT_PAYLOAD}, {@code RECONCILIATION_ATTEMPT}, {@code CONTENT_VERSION}, {@code PORT_BATCH_ITEM}) and
 * {@code ACT_GE_BYTEARRAY} rows of the tenant or of its deployments. Not moved: transient coordination tables
 * (leases of other tenants, leader, fetch/acquire locks), {@code OUTBOX}/{@code OUTBOX_DELIVERY} (the tenant is
 * drained first; delivered events stay at the source until purge), Liquibase bookkeeping, engine properties, identity
 * and metrics tables, and shared (tenant-less) definitions.
 *
 * <p><b>Identity keys.</b> {@code RECONCILIATION_ITEM}, {@code INTEGRATION_DEAD_LETTER}, {@code EVT_LOG} and
 * {@code INSTANCE_LINK} have database-generated ids that may collide across cells: they are re-inserted with new ids,
 * and {@code RECONCILIATION_ATTEMPT.item_id} is re-mapped. These id columns are left out of the checksum.
 *
 * <p><b>Foreign keys.</b> Tables are ordered by their NOT NULL foreign keys; nullable foreign-key columns (engine
 * parent/super references, also self references) are written in a second pass, so any order and any cycle through a
 * nullable column loads. Deletes collect primary keys first, clear nullable foreign keys, then delete children first.
 *
 * <p><b>Consistency.</b> Export runs in one READ COMMITTED transaction (the isolation every Sangam pool enforces) and
 * is allowed only when the tenant is FROZEN and drained (no command in progress, no pending outbox delivery, no locked
 * external work), so the tenant's rows cannot change during the read. Timers still due run on until the move
 * activates the target; they are reported by {@link #drainStatus}.
 *
 * <p><b>Checksum.</b> Per table: row count and the sum modulo 2^256 of SHA-256 over each row's canonical form (sorted
 * lower-case column names; timestamps as epoch milliseconds, numbers without trailing zeros, booleans as 1/0, bytes
 * as hex), so it is order-independent and comparable across database vendors; the content hash is SHA-256 over the
 * sorted {@code table:count:hash} lines.
 */
public final class CellTenantMover {

    private static final Logger LOG = LoggerFactory.getLogger(CellTenantMover.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigInteger MOD = BigInteger.ONE.shiftLeft(256);
    public static final String EXPORT_KIND = "cell:export";
    public static final String FORMAT = "sangam-cell-export/1";

    private static final Set<String> EXCLUDED = Set.of("OUTBOX", "OUTBOX_DELIVERY", "INTEGRATION_LEADER",
            "SANGAM_FETCH_LOCK", "JOB_ACQUIRE_LOCK", "PACK_DEFINITION", "DATABASECHANGELOG", "DATABASECHANGELOGLOCK",
            "ACT_GE_PROPERTY", "ACT_GE_SCHEMA_LOG", "ACT_RU_METER_LOG", "ACT_RU_TASK_METER_LOG", "ACT_ID_USER",
            "ACT_ID_GROUP", "ACT_ID_MEMBERSHIP", "ACT_ID_INFO", "ACT_ID_TENANT", "ACT_ID_TENANT_MEMBER",
            "ACT_RU_AUTHORIZATION");

    /** Tables re-inserted with new database-generated ids: table → id column. */
    private static final Map<String, String> REMAPPED = Map.of("RECONCILIATION_ITEM", "ID", "INTEGRATION_DEAD_LETTER",
            "ID", "EVT_LOG", "ID", "INSTANCE_LINK", "ID", "CFG_CHANGE", "CHANGE_ID");

    /** Tables keyed by configuration scope, not tenant: table → scope column. The scope is the tenant id or its office overlays. */
    private static final Map<String, String> SCOPED = Map.of("CFG_PROFILE", "PROFILE_ID", "CFG_CHANGE", "PROFILE_ID",
            "CFG_MASTER_CODE", "SCOPE_ID", "CFG_SERIES", "SCOPE_ID");

    /** Export section: active profile documents the tenant's chain needs outside its own scope (inserted only if absent). */
    private static final String SHARED_PROFILES = "CFG_PROFILE#SHARED";

    /** Tenant-less child tables: table → [column, parent table, parent key column]. */
    private static final Map<String, String[]> CHILDREN = Map.of(
            "AUDIT_PAYLOAD", new String[]{"EVENT_ID", "AUDIT_EVENT", "EVENT_ID"},
            "RECONCILIATION_ATTEMPT", new String[]{"ITEM_ID", "RECONCILIATION_ITEM", "ID"},
            "CONTENT_VERSION", new String[]{"ITEM_ID", "CONTENT_ITEM", "ID"},
            "PORT_BATCH_ITEM", new String[]{"BATCH_ID", "PORT_BATCH", "ID"},
            "AI_AGENT_STEP", new String[]{"RUN_ID", "AI_AGENT_RUN", "RUN_ID"},
            "PIPELINE_STEP_RUN", new String[]{"RUN_ID", "PIPELINE_RUN", "RUN_ID"},
            "SANGAM_FEEL_BINDING", new String[]{"DEPLOYMENT_ID", "ACT_RE_DEPLOYMENT", "ID_"});

    public record DrainStatus(long inFlightCommands, long pendingOutbox, long lockedWork, long dueTimers) {
        public boolean drained() {
            return inFlightCommands == 0 && pendingOutbox == 0 && lockedWork == 0;
        }
    }

    public record ExportHandle(String url, String token, long rows, String watermark, String auditHead) {
    }

    public record TenantChecksum(Map<String, Long> rowCounts, String contentHash) {
    }

    /** One table to move: its real name, columns, tenant filter and FK/PK metadata. */
    private record Plan(String table, String upper, List<String> columns, Map<String, Integer> sqlTypes,
                        String where, int whereParams, List<String> pk, Set<String> nullableFks,
                        Set<String> requiredParents) {
    }

    private final TxSupport tx;
    private final DocumentStore documents;
    private final AuditPort audit;
    private final Path exportDir;
    private final String adminBaseUrl;
    private final Duration exportTtl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CellTenantMover(TxSupport tx, DocumentStore documents, AuditPort audit, Path exportDir, String adminBaseUrl,
                           Duration exportTtl) {
        this.tx = tx;
        this.documents = documents;
        this.audit = audit;
        this.exportDir = exportDir;
        this.adminBaseUrl = adminBaseUrl == null ? "" : adminBaseUrl.replaceAll("/+$", "");
        this.exportTtl = exportTtl == null ? Duration.ofHours(2) : exportTtl;
    }

    // ------------------------------------------------------------------ drain status

    public DrainStatus drainStatus(String tenant) {
        return tx.withConnection(c -> {
            Timestamp now = Timestamp.from(Jdbc.dbNow(c, tx.dialect()));
            return new DrainStatus(
                    count(c, "SELECT COUNT(*) FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND state='IN_PROGRESS'", tenant),
                    count(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY WHERE tenant_id=? AND state IN ('PENDING','CLAIMED')",
                            tenant),
                    count(c, "SELECT COUNT(*) FROM ACT_RU_EXT_TASK WHERE TENANT_ID_=? AND LOCK_EXP_TIME_ > ?", tenant, now)
                            + count(c, "SELECT COUNT(*) FROM SANGAM_LEASE WHERE tenant_id=? AND worker_id IS NOT NULL"
                            + " AND expires_at_ms > ?", tenant, now.getTime()),
                    count(c, "SELECT COUNT(*) FROM ACT_RU_JOB WHERE TENANT_ID_=? AND DUEDATE_ <= ? AND RETRIES_ > 0",
                            tenant, now));
        });
    }

    /** Chain head digest of the tenant: SHA-256 over the sorted {@code chain:headHash} lines of ROOT_SEQ. */
    public String auditHead(String tenant) {
        return tx.withConnection(c -> auditHead(c, tenant));
    }

    private String auditHead(Connection c, String tenant) {
        List<String> lines = new ArrayList<>(Jdbc.query(c,
                "SELECT root_id, audit_head_hash FROM ROOT_SEQ WHERE tenant_id=?",
                rs -> rs.getString(1) + ":" + (rs.getBytes(2) == null ? "-" : Hashes.hex(rs.getBytes(2))), tenant));
        java.util.Collections.sort(lines);
        return Hashes.hex(Hashes.sha256(String.join("\n", lines)));
    }

    // ------------------------------------------------------------------ export

    public ExportHandle export(RequestContext ctx, String tenant, String moveId) {
        DrainStatus d = drainStatus(tenant);
        if (!d.drained()) {
            throw ApiProblem.conflict("NOT_DRAINED", "tenant " + tenant + " is not drained yet")
                    .with("drainStatus", d);
        }
        String exportId = DocumentStore.newId();
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Path file;
        try {
            Files.createDirectories(exportDir);
            file = exportDir.resolve(exportId + ".ndjson.gz");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create the export directory " + exportDir, e);
        }
        record Result(long rows, String watermark, String head) {
        }
        Result r = tx.withConnection(c -> {
            String watermark = Jdbc.dbNow(c, tx.dialect()).toString();
            String head = auditHead(c, tenant);
            long rows = 0;
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8))) {
                ObjectNode header = JSON.createObjectNode().put("format", FORMAT).put("tenant", tenant)
                        .put("moveId", moveId).put("watermark", watermark).put("auditHead", head);
                w.write(header.toString());
                w.newLine();
                for (Plan p : plans(c)) {
                    rows += exportTable(c, p, tenant, w);
                }
                exportSharedProfiles(c, tenant, w);
            } catch (IOException | SQLException e) {
                throw new IllegalStateException("export of tenant " + tenant + " failed", e);
            }
            return new Result(rows, watermark, head);
        });
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("tenant", tenant);
        doc.put("moveId", moveId);
        doc.put("file", file.toString());
        doc.put("tokenSha256", Hashes.hex(Hashes.sha256(token)));
        doc.put("expiresAt", Instant.now().plus(exportTtl).toString());
        doc.put("rows", r.rows());
        doc.put("watermark", r.watermark());
        doc.put("auditHead", r.head());
        documents.insert(DocumentStore.GLOBAL, EXPORT_KIND, exportId, tenant, "READY", ctx.principalId(), moveId, doc,
                ctx.principalId());
        auditCell(ctx, tenant, "TENANT_EXPORTED", Map.of("moveId", String.valueOf(moveId), "rows", r.rows(),
                "watermark", r.watermark(), "auditHead", r.head()));
        LOG.info("Tenant {} exported: {} rows (move {})", tenant, r.rows(), moveId);
        return new ExportHandle(adminBaseUrl + "/api/v1/admin/cell/exports/" + exportId, token, r.rows(),
                r.watermark(), r.head());
    }

    /** Opens an export for the pulling target cell; the token is checked against its stored hash and expiry. */
    public InputStream openExport(String exportId, String token) throws IOException {
        DocumentStore.Doc d = documents.get(DocumentStore.GLOBAL, EXPORT_KIND, exportId)
                .orElseThrow(() -> ApiProblem.notFound("export", exportId));
        Map<String, Object> b = d.body();
        if (token == null || !java.security.MessageDigest.isEqual(
                Hashes.hex(Hashes.sha256(token)).getBytes(StandardCharsets.US_ASCII),
                String.valueOf(b.get("tokenSha256")).getBytes(StandardCharsets.US_ASCII))) {
            throw ApiProblem.forbidden("EXPORT_TOKEN_INVALID", "the export token does not match");
        }
        if (Instant.parse(String.valueOf(b.get("expiresAt"))).isBefore(Instant.now())) {
            throw ApiProblem.gone("EXPORT_EXPIRED", "export " + exportId + " has expired");
        }
        return Files.newInputStream(Path.of(String.valueOf(b.get("file"))));
    }

    /** Walks prefixes, extends and adopt from the tenant's documents, and writes the chain documents outside its scope. */
    private void exportSharedProfiles(Connection c, String tenant, BufferedWriter w) throws SQLException, IOException {
        List<String> cols = List.of("profile_id", "version", "layer_id", "status", "effective_from", "effective_to",
                "content_hash", "document_json", "updated_at");
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        queue.add(tenant);
        String select = "SELECT " + String.join(", ", cols) + " FROM CFG_PROFILE WHERE profile_id=? AND status='ACTIVE'";
        ObjectNode head = JSON.createObjectNode().put("table", SHARED_PROFILES);
        ArrayNode ca = head.putArray("columns");
        cols.forEach(ca::add);
        w.write(head.toString());
        w.newLine();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            for (int dot = id.lastIndexOf('.'); dot > 0; dot = id.lastIndexOf('.', dot - 1)) {
                queue.add(id.substring(0, dot));
            }
            try (PreparedStatement ps = c.prepareStatement(select)) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonNode header = JSON.readTree(rs.getString("document_json")).path("profile");
                        header.path("extends").forEach(x -> queue.add(x.asText()));
                        header.path("adopt").forEach(x -> queue.add(x.path("profile").asText()));
                        if (!id.equals(tenant)) {
                            ArrayNode row = JSON.createArrayNode();
                            for (int i = 1; i <= cols.size(); i++) {
                                row.add(encode(rs, i));
                            }
                            w.write(JSON.createObjectNode().set("r", row).toString());
                            w.newLine();
                        }
                    }
                }
            }
        }
    }

    private void loadSharedProfiles(Connection c, Plan p, List<String> cols, List<JsonNode> rows,
                                    Map<String, Map<String, Object>> idMaps, List<Runnable> secondPass) throws SQLException {
        int id = indexOfIgnoreCase(cols, "profile_id");
        int version = indexOfIgnoreCase(cols, "version");
        List<JsonNode> absent = new ArrayList<>();
        for (JsonNode r : rows) {
            if (count(c, "SELECT COUNT(*) FROM CFG_PROFILE WHERE profile_id=? AND version=?", decode(r.get(id)), decode(r.get(version))) == 0) {
                absent.add(r);
            }
        }
        if (!absent.isEmpty()) {
            insertRows(c, p, cols, absent, idMaps, secondPass);
        }
    }

    private long exportTable(Connection c, Plan p, String tenant, BufferedWriter w) throws SQLException, IOException {
        ObjectNode head = JSON.createObjectNode().put("table", p.upper());
        ArrayNode cols = head.putArray("columns");
        p.columns().forEach(cols::add);
        w.write(head.toString());
        w.newLine();
        long n = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT " + String.join(", ", p.columns()) + " FROM "
                + p.table() + " WHERE " + p.where())) {
            ps.setFetchSize(500);
            bindTenant(ps, p, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ArrayNode row = JSON.createArrayNode();
                    for (int i = 1; i <= p.columns().size(); i++) {
                        row.add(encode(rs, i));
                    }
                    w.write(JSON.createObjectNode().set("r", row).toString());
                    w.newLine();
                    n++;
                }
            }
        }
        w.write(JSON.createObjectNode().put("end", p.upper()).put("rows", n).toString());
        w.newLine();
        return n;
    }

    // ------------------------------------------------------------------ import (target)

    public Map<String, Long> importFrom(RequestContext ctx, String tenant, String moveId, String url, String token,
                                        String watermark) {
        if (url == null || token == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "url and token are required");
        }
        // Remote call first, outside any database transaction (VI.1 rule 5).
        Path tmp;
        try {
            Files.createDirectories(exportDir);
            tmp = Files.createTempFile(exportDir, "import-" + tenant + "-", ".ndjson.gz");
            HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30))
                    .header("X-Sangam-Export-Token", token).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw ApiProblem.unprocessable("EXPORT_UNAVAILABLE", "the source cell answered HTTP " + r.statusCode());
            }
        } catch (IOException e) {
            throw ApiProblem.unavailable("EXPORT_UNREACHABLE", "cannot fetch the export: " + e.getMessage(), 30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiProblem.unavailable("EXPORT_UNREACHABLE", "interrupted", 30);
        }
        try {
            Map<String, Long> counts = tx.withConnection(c -> {
                if (count(c, "SELECT COUNT(*) FROM ACT_RE_DEPLOYMENT WHERE TENANT_ID_=?", tenant)
                        + count(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=?", tenant)
                        + count(c, "SELECT COUNT(*) FROM ROOT_SEQ WHERE tenant_id=?", tenant) > 0) {
                    throw ApiProblem.conflict("TENANT_NOT_EMPTY", "tenant " + tenant + " already has rows in this cell");
                }
                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(tmp)), StandardCharsets.UTF_8))) {
                    return load(c, tenant, watermark, in);
                } catch (IOException | SQLException e) {
                    throw new IllegalStateException("import of tenant " + tenant + " failed", e);
                }
            });
            auditCell(ctx, tenant, "TENANT_IMPORTED", Map.of("moveId", String.valueOf(moveId), "rowCounts", counts));
            return counts;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                LOG.warn("temporary import file not removed: {}", tmp);
            }
        }
    }

    private Map<String, Long> load(Connection c, String tenant, String watermark, BufferedReader in)
            throws IOException, SQLException {
        JsonNode header = JSON.readTree(in.readLine());
        if (header == null || !FORMAT.equals(header.path("format").asText())) {
            throw ApiProblem.unprocessable("EXPORT_FORMAT", "not a " + FORMAT + " stream");
        }
        if (!tenant.equals(header.path("tenant").asText())) {
            throw ApiProblem.unprocessable("EXPORT_TENANT_MISMATCH", "the export is for tenant "
                    + header.path("tenant").asText());
        }
        if (watermark != null && !watermark.isBlank() && !watermark.equals(header.path("watermark").asText())) {
            throw ApiProblem.unprocessable("EXPORT_WATERMARK_MISMATCH", "the export watermark differs");
        }
        Map<String, Plan> targetPlans = new HashMap<>();
        for (Plan p : plans(c)) {
            targetPlans.put(p.upper(), p);
        }
        // Read every table into memory groups by table, then insert in FK order (exports are tenant-sized).
        Map<String, List<String>> exportCols = new LinkedHashMap<>();
        Map<String, List<JsonNode>> rows = new LinkedHashMap<>();
        String current = null;
        String line;
        while ((line = in.readLine()) != null) {
            JsonNode n = JSON.readTree(line);
            if (n.has("table")) {
                current = n.get("table").asText();
                List<String> cs = new ArrayList<>();
                n.get("columns").forEach(x -> cs.add(x.asText()));
                exportCols.put(current, cs);
                rows.put(current, new ArrayList<>());
            } else if (n.has("r")) {
                rows.get(current).add(n.get("r"));
            }
        }
        Map<String, Long> counts = new TreeMap<>();
        Map<String, Map<String, Object>> idMaps = new HashMap<>();
        List<Runnable> secondPass = new ArrayList<>();
        for (Plan p : order(new ArrayList<>(targetPlans.values()))) {
            if (SCOPED.containsKey(p.upper()) && rows.containsKey(p.upper())) {
                // release data for this scope may exist here; the source's state replaces it
                try (PreparedStatement del = c.prepareStatement("DELETE FROM " + p.table() + " WHERE " + p.where())) {
                    bindTenant(del, p, tenant);
                    del.executeUpdate();
                }
            }
            List<JsonNode> tableRows = rows.get(p.upper());
            if (tableRows == null || tableRows.isEmpty()) {
                continue;
            }
            List<String> cols = exportCols.get(p.upper());
            counts.put(p.upper(), insertRows(c, p, cols, tableRows, idMaps, secondPass));
        }
        if (rows.containsKey(SHARED_PROFILES) && targetPlans.containsKey("CFG_PROFILE")) {
            loadSharedProfiles(c, targetPlans.get("CFG_PROFILE"), exportCols.get(SHARED_PROFILES), rows.get(SHARED_PROFILES),
                    idMaps, secondPass);
        }
        for (Runnable r : secondPass) {
            r.run();
        }
        return counts;
    }

    private long insertRows(Connection c, Plan p, List<String> exportCols, List<JsonNode> rows,
                            Map<String, Map<String, Object>> idMaps, List<Runnable> secondPass) throws SQLException {
        String remapCol = REMAPPED.get(p.upper());
        Map<String, String> byUpper = new HashMap<>();
        p.columns().forEach(x -> byUpper.put(x.toUpperCase(Locale.ROOT), x));
        List<Integer> idx = new ArrayList<>();
        List<String> insertCols = new ArrayList<>();
        for (int i = 0; i < exportCols.size(); i++) {
            String u = exportCols.get(i).toUpperCase(Locale.ROOT);
            if (!byUpper.containsKey(u) || u.equals(remapCol)) {
                continue;
            }
            idx.add(i);
            insertCols.add(byUpper.get(u));
        }
        String[] child = CHILDREN.get(p.upper());
        Map<String, Object> idMap = new HashMap<>();
        String sql = "INSERT INTO " + p.table() + " (" + String.join(", ", insertCols) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(insertCols.size(), "?")) + ")";
        long n = 0;
        try (PreparedStatement ps = remapCol == null ? c.prepareStatement(sql) : null) {
            for (JsonNode row : rows) {
                Object[] values = new Object[insertCols.size()];
                Map<String, Object> deferred = new LinkedHashMap<>();
                for (int k = 0; k < insertCols.size(); k++) {
                    String col = insertCols.get(k);
                    Object v = decode(row.get(idx.get(k)));
                    String u = col.toUpperCase(Locale.ROOT);
                    if (child != null && u.equals(child[0]) && idMaps.containsKey(child[1])) {
                        Object mapped = idMaps.get(child[1]).get(String.valueOf(v));
                        v = mapped == null ? v : mapped;
                    }
                    if (v != null && p.nullableFks().contains(u)) {
                        deferred.put(col, v);
                        v = null;
                    }
                    values[k] = v;
                }
                if (remapCol != null) {
                    Object oldId = decode(row.get(exportCols.stream().map(x -> x.toUpperCase(Locale.ROOT)).toList()
                            .indexOf(remapCol)));
                    long newId = Jdbc.insertReturningId(c, tx.dialect(), sql, byUpper.get(remapCol), values);
                    idMap.put(String.valueOf(oldId), newId);
                    deferUpdate(c, p, Map.of(byUpper.get(remapCol), newId), deferred, secondPass);
                } else {
                    bind(ps, p, insertCols, values);
                    ps.addBatch();
                    deferUpdate(c, p, pkValues(p, insertCols, values, deferred), deferred, secondPass);
                    if (++n % 500 == 0) {
                        ps.executeBatch();
                    }
                    continue;
                }
                n++;
            }
            if (ps != null) {
                ps.executeBatch();
            }
        }
        if (remapCol != null) {
            idMaps.put(p.upper(), idMap);
        }
        return n == 0 ? rows.size() : n;
    }

    private static Map<String, Object> pkValues(Plan p, List<String> cols, Object[] values,
                                                Map<String, Object> deferred) {
        Map<String, Object> pk = new LinkedHashMap<>();
        for (String k : p.pk()) {
            int i = indexOfIgnoreCase(cols, k);
            Object v = i >= 0 ? values[i] : null;
            if (v == null) {
                v = deferred.get(k);
            }
            pk.put(k, v);
        }
        return pk;
    }

    private void deferUpdate(Connection c, Plan p, Map<String, Object> pk, Map<String, Object> deferred,
                             List<Runnable> secondPass) {
        if (deferred.isEmpty() || pk.isEmpty() || pk.values().stream().anyMatch(java.util.Objects::isNull)) {
            return;
        }
        List<String> sets = new ArrayList<>(deferred.keySet());
        List<Object> params = new ArrayList<>(deferred.values());
        params.addAll(pk.values());
        String sql = "UPDATE " + p.table() + " SET " + String.join(", ", sets.stream().map(s -> s + "=?").toList())
                + " WHERE " + String.join(" AND ", pk.keySet().stream().map(k -> k + "=?").toList());
        secondPass.add(() -> Jdbc.update(c, sql, params.toArray()));
    }

    // ------------------------------------------------------------------ checksum

    public TenantChecksum checksum(String tenant) {
        return tx.withConnection(c -> {
            Map<String, Long> counts = new TreeMap<>();
            List<String> lines = new ArrayList<>();
            try {
                for (Plan p : plans(c)) {
                    String excluded = REMAPPED.get(p.upper());
                    String[] child = CHILDREN.get(p.upper());
                    Set<String> skip = new LinkedHashSet<>();
                    if (excluded != null) {
                        skip.add(excluded);
                    }
                    if (child != null && REMAPPED.containsKey(child[1])) {
                        skip.add(child[0]);
                    }
                    long n = 0;
                    BigInteger sum = BigInteger.ZERO;
                    try (PreparedStatement ps = c.prepareStatement("SELECT " + String.join(", ", p.columns())
                            + " FROM " + p.table() + " WHERE " + p.where())) {
                        ps.setFetchSize(500);
                        bindTenant(ps, p, tenant);
                        try (ResultSet rs = ps.executeQuery()) {
                            ResultSetMetaData md = rs.getMetaData();
                            while (rs.next()) {
                                TreeMap<String, String> canon = new TreeMap<>();
                                for (int i = 1; i <= md.getColumnCount(); i++) {
                                    String name = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                                    if (!skip.contains(name.toUpperCase(Locale.ROOT))) {
                                        canon.put(name, canonical(rs, i));
                                    }
                                }
                                sum = sum.add(new BigInteger(1, Hashes.sha256(canon.toString()))).mod(MOD);
                                n++;
                            }
                        }
                    }
                    if (n > 0) {
                        counts.put(p.upper(), n);
                        lines.add(p.upper() + ":" + n + ":" + sum.toString(16));
                    }
                }
            } catch (SQLException e) {
                throw new Jdbc.LayerSqlException("checksum of tenant " + tenant, e);
            }
            java.util.Collections.sort(lines);
            return new TenantChecksum(counts, Hashes.hex(Hashes.sha256(String.join("\n", lines))));
        });
    }

    // ------------------------------------------------------------------ drop / purge

    /** Removes every row of the tenant (drop of a partial import, or purge after a move). */
    public Map<String, Long> deleteTenant(String tenant, boolean includeOutbox) {
        return tx.withConnection(c -> {
            Map<String, Long> deleted = new TreeMap<>();
            try {
                List<Plan> plans = order(plans(c));
                java.util.Collections.reverse(plans);
                // children without FKs first (their tenant filter reads the parent)
                plans.sort((a, b) -> Boolean.compare(!CHILDREN.containsKey(a.upper()), !CHILDREN.containsKey(b.upper())));
                Map<Plan, List<Object[]>> keys = new LinkedHashMap<>();
                for (Plan p : plans) {
                    keys.put(p, collectKeys(c, p, tenant));
                }
                for (Plan p : plans) {
                    if (p.nullableFks().isEmpty() || p.pk().isEmpty()) {
                        continue;
                    }
                    String set = String.join(", ", p.nullableFks().stream().map(f -> actual(p, f) + "=NULL").toList());
                    for (Object[] k : keys.get(p)) {
                        Jdbc.update(c, "UPDATE " + p.table() + " SET " + set + " WHERE " + pkWhere(p), k);
                    }
                }
                for (Plan p : plans) {
                    long n = 0;
                    if (p.pk().isEmpty()) {
                        Object[] params = new Object[p.whereParams()];
                        java.util.Arrays.fill(params, tenant);
                        n = Jdbc.update(c, "DELETE FROM " + p.table() + " WHERE " + p.where(), params);
                    } else {
                        for (Object[] k : keys.get(p)) {
                            n += Jdbc.update(c, "DELETE FROM " + p.table() + " WHERE " + pkWhere(p), k);
                        }
                    }
                    if (n > 0) {
                        deleted.put(p.upper(), n);
                    }
                }
                if (includeOutbox) {
                    long d = Jdbc.update(c, "DELETE FROM OUTBOX_DELIVERY WHERE tenant_id=?", tenant);
                    long o = Jdbc.update(c, "DELETE FROM OUTBOX WHERE tenant_id=?", tenant);
                    if (d + o > 0) {
                        deleted.put("OUTBOX_DELIVERY", d);
                        deleted.put("OUTBOX", o);
                    }
                }
            } catch (SQLException e) {
                throw new Jdbc.LayerSqlException("delete of tenant " + tenant, e);
            }
            return deleted;
        });
    }

    /** Active legal holds of the tenant (R61): a purge is refused while any exists. */
    public long activeLegalHolds(String tenant) {
        return tx.withConnection(c -> count(c, "SELECT COUNT(*) FROM LEGAL_HOLD WHERE tenant_id=? AND released_at IS NULL",
                tenant));
    }

    /** Deletes this tenant's export files and export documents. */
    public void deleteExports(String tenant) {
        for (DocumentStore.Doc d : documents.all(DocumentStore.GLOBAL, EXPORT_KIND, DocumentStore.Filter.key(tenant))) {
            try {
                Files.deleteIfExists(Path.of(String.valueOf(d.body().get("file"))));
            } catch (IOException e) {
                LOG.warn("export file not removed: {}", d.body().get("file"));
            }
            documents.delete(DocumentStore.GLOBAL, EXPORT_KIND, d.id());
        }
    }

    public void auditCell(RequestContext ctx, String tenant, String type, Map<String, ?> payload) {
        RequestContext asTenant = new RequestContext(tenant, ctx.principalId(), ctx.groups(), null, null,
                ctx.idempotencyKey(), ctx.traceId(), ctx.clientIp(), ctx.authMethod());
        audit.record(asTenant, "sec:" + tenant, type, payload);
    }

    // ------------------------------------------------------------------ table plans

    private List<Plan> plans(Connection c) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        String catalog = c.getCatalog();
        String schema = c.getSchema();
        List<Plan> out = new ArrayList<>();
        Map<String, String> realNames = new HashMap<>();
        try (ResultSet t = md.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (t.next()) {
                realNames.put(t.getString("TABLE_NAME").toUpperCase(Locale.ROOT), t.getString("TABLE_NAME"));
            }
        }
        for (Map.Entry<String, String> e : realNames.entrySet()) {
            String upper = e.getKey();
            String table = e.getValue();
            if (EXCLUDED.contains(upper)) {
                continue;
            }
            List<String> cols = new ArrayList<>();
            Map<String, Integer> types = new HashMap<>();
            Set<String> nullable = new LinkedHashSet<>();
            try (ResultSet rc = md.getColumns(catalog, schema, table, "%")) {
                while (rc.next()) {
                    String col = rc.getString("COLUMN_NAME");
                    cols.add(col);
                    types.put(col.toUpperCase(Locale.ROOT), rc.getInt("DATA_TYPE"));
                    if (rc.getInt("NULLABLE") == DatabaseMetaData.columnNullable) {
                        nullable.add(col.toUpperCase(Locale.ROOT));
                    }
                }
            }
            String tenantCol = cols.stream().filter(x -> x.equalsIgnoreCase("tenant_id") || x.equalsIgnoreCase("TENANT_ID_"))
                    .findFirst().orElse(null);
            String where;
            int params;
            if (upper.equals("ACT_GE_BYTEARRAY")) {
                // the tenant's own byte arrays (variables, history details) and every resource of its deployments
                String dep = realNames.getOrDefault("ACT_RE_DEPLOYMENT", "ACT_RE_DEPLOYMENT");
                String byDeployment = "DEPLOYMENT_ID_ IN (SELECT ID_ FROM " + dep + " WHERE TENANT_ID_=?)";
                where = tenantCol == null ? byDeployment : "(" + tenantCol + "=? OR " + byDeployment + ")";
                params = tenantCol == null ? 1 : 2;
            } else if (SCOPED.containsKey(upper)) {
                String col = actualName(cols, SCOPED.get(upper));
                where = "(" + col + "=? OR " + col + " LIKE ? ESCAPE '!')";
                params = 2;
            } else if (tenantCol != null) {
                where = tenantCol + "=?";
                params = 1;
            } else if (CHILDREN.containsKey(upper) && realNames.containsKey(CHILDREN.get(upper)[1])) {
                String[] ch = CHILDREN.get(upper);
                where = actualName(cols, ch[0]) + " IN (SELECT " + ch[2] + " FROM " + realNames.get(ch[1])
                        + " WHERE " + (ch[1].startsWith("ACT_") ? "TENANT_ID_" : "tenant_id") + "=?)";
                params = 1;
            } else {
                LOG.debug("Table {} has no tenant column or mapping: not moved", upper);
                continue;
            }
            List<String> pk = new ArrayList<>();
            try (ResultSet rp = md.getPrimaryKeys(catalog, schema, table)) {
                TreeMap<Integer, String> seq = new TreeMap<>();
                while (rp.next()) {
                    seq.put(rp.getInt("KEY_SEQ"), rp.getString("COLUMN_NAME"));
                }
                pk.addAll(seq.values());
            }
            Set<String> nullableFks = new LinkedHashSet<>();
            Set<String> required = new LinkedHashSet<>();
            try (ResultSet rf = md.getImportedKeys(catalog, schema, table)) {
                while (rf.next()) {
                    String fkCol = rf.getString("FKCOLUMN_NAME").toUpperCase(Locale.ROOT);
                    String parent = rf.getString("PKTABLE_NAME").toUpperCase(Locale.ROOT);
                    if (nullable.contains(fkCol)) {
                        nullableFks.add(fkCol);
                    } else if (!parent.equals(upper)) {
                        required.add(parent);
                    }
                }
            }
            out.add(new Plan(table, upper, cols, types, where, params, pk, nullableFks, required));
        }
        return out;
    }

    /** Parents before children by NOT NULL foreign keys; parents of logical child tables first. */
    private static List<Plan> order(List<Plan> plans) {
        Map<String, Plan> byName = new LinkedHashMap<>();
        plans.stream().sorted((a, b) -> a.upper().compareTo(b.upper())).forEach(p -> byName.put(p.upper(), p));
        List<Plan> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String n : byName.keySet()) {
            visit(n, byName, done, visiting, out);
        }
        return out;
    }

    private static void visit(String n, Map<String, Plan> byName, Set<String> done, Set<String> visiting, List<Plan> out) {
        if (done.contains(n) || !byName.containsKey(n) || !visiting.add(n)) {
            return;
        }
        Plan p = byName.get(n);
        Set<String> deps = new LinkedHashSet<>(p.requiredParents());
        if (CHILDREN.containsKey(n)) {
            deps.add(CHILDREN.get(n)[1]);
        }
        for (String d : deps) {
            visit(d, byName, done, visiting, out);
        }
        visiting.remove(n);
        if (done.add(n)) {
            out.add(p);
        }
    }

    private List<Object[]> collectKeys(Connection c, Plan p, String tenant) throws SQLException {
        List<Object[]> keys = new ArrayList<>();
        if (p.pk().isEmpty()) {
            return keys;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT " + String.join(", ", p.pk()) + " FROM " + p.table()
                + " WHERE " + p.where())) {
            bindTenant(ps, p, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object[] k = new Object[p.pk().size()];
                    for (int i = 0; i < k.length; i++) {
                        k[i] = rs.getObject(i + 1);
                    }
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    private static String pkWhere(Plan p) {
        return String.join(" AND ", p.pk().stream().map(k -> k + "=?").toList());
    }

    private static String actual(Plan p, String upper) {
        return actualName(p.columns(), upper);
    }

    private static String actualName(List<String> cols, String upper) {
        return cols.stream().filter(x -> x.equalsIgnoreCase(upper)).findFirst().orElse(upper);
    }

    private static int indexOfIgnoreCase(List<String> l, String s) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).equalsIgnoreCase(s)) {
                return i;
            }
        }
        return -1;
    }

    private static void bindTenant(PreparedStatement ps, Plan p, String tenant) throws SQLException {
        if (SCOPED.containsKey(p.upper())) {
            ps.setString(1, tenant);
            ps.setString(2, tenant.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "/%");
            return;
        }
        for (int i = 1; i <= p.whereParams(); i++) {
            ps.setString(i, tenant);
        }
    }

    private static void bind(PreparedStatement ps, Plan p, List<String> cols, Object[] values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) {
                Integer t = p.sqlTypes().get(cols.get(i).toUpperCase(Locale.ROOT));
                ps.setNull(i + 1, t == null ? Types.VARCHAR : t);
            } else if (v instanceof Instant instant) {
                ps.setTimestamp(i + 1, Timestamp.from(instant));
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }

    // ------------------------------------------------------------------ value encoding

    private static JsonNode encode(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v == null) {
            return JSON.nullNode();
        }
        if (v instanceof byte[] b) {
            return JSON.createObjectNode().put("b", Base64.getEncoder().encodeToString(b));
        }
        if (v instanceof Blob blob) {
            return JSON.createObjectNode().put("b", Base64.getEncoder().encodeToString(blob.getBytes(1, (int) blob.length())));
        }
        if (v instanceof Clob clob) {
            return JSON.getNodeFactory().textNode(clob.getSubString(1, (int) clob.length()));
        }
        Instant ts = instant(v, rs, i);
        if (ts != null) {
            return JSON.createObjectNode().put("ts", ts.toString());
        }
        if (v instanceof Boolean bool) {
            return JSON.getNodeFactory().booleanNode(bool);
        }
        if (v instanceof BigDecimal d) {
            return JSON.createObjectNode().put("d", d.toPlainString());
        }
        if (v instanceof Double || v instanceof Float) {
            double dv = ((Number) v).doubleValue();
            return Double.isFinite(dv) ? JSON.createObjectNode().put("d", new BigDecimal(v.toString()).toPlainString())
                    : JSON.getNodeFactory().textNode(v.toString());
        }
        if (v instanceof Number n) {
            return JSON.getNodeFactory().numberNode(n.longValue());
        }
        return JSON.getNodeFactory().textNode(v.toString());
    }

    private static Object decode(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            if (n.has("b")) {
                return Base64.getDecoder().decode(n.get("b").asText());
            }
            if (n.has("ts")) {
                return Instant.parse(n.get("ts").asText());
            }
            if (n.has("d")) {
                return new BigDecimal(n.get("d").asText());
            }
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber()) {
            return n.longValue();
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        return n.asText();
    }

    private static String canonical(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v == null) {
            return "∅";
        }
        if (v instanceof byte[] b) {
            return Hashes.hex(b);
        }
        if (v instanceof Blob blob) {
            return Hashes.hex(blob.getBytes(1, (int) blob.length()));
        }
        if (v instanceof Clob clob) {
            return clob.getSubString(1, (int) clob.length());
        }
        Instant ts = instant(v, rs, i);
        if (ts != null) {
            return Long.toString(ts.toEpochMilli());
        }
        if (v instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (v instanceof Number num) {
            BigDecimal d = new BigDecimal(num.toString()).stripTrailingZeros();
            return d.toPlainString();
        }
        return v.toString();
    }

    private static Instant instant(Object v, ResultSet rs, int i) throws SQLException {
        if (v instanceof Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (v instanceof java.time.LocalDateTime l) {
            return l.toInstant(java.time.ZoneOffset.UTC);
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        String cls = v.getClass().getName();
        if (cls.startsWith("oracle.sql.TIMESTAMP") || cls.startsWith("microsoft.sql.DateTimeOffset")
                || cls.contains("TimestampWithTimeZone")) {
            Timestamp t = rs.getTimestamp(i);
            return t == null ? null : t.toInstant();
        }
        return null;
    }

    private static long count(Connection c, String sql, Object... params) {
        try {
            return Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L);
        } catch (RuntimeException e) {
            // a table of a module that is not installed in this cell
            return 0L;
        }
    }
}
