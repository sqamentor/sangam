// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.file.FileTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Runs one pipeline run (N11). Sangam orchestrates; heavy engines stay outside (D-22): this is for bounded feeds.
 *
 * <ul>
 *   <li><b>Staging.</b> Each EXTRACT/TRANSFORM step writes a JSON-lines file under {@code stageRoot/<runId>/}, renamed into
 *       place when complete.</li>
 *   <li><b>Restart from the last good step.</b> DONE steps are skipped; a failed step runs again from its input. A LOAD
 *       resumes from its last committed chunk.</li>
 *   <li><b>Row rejects.</b> A row a transform or load refuses goes to {@code <step>.rejects.jsonl} with its reason; more than
 *       {@code maxRejects} in a run fails it.</li>
 *   <li><b>Idempotent loads keyed by run id.</b>
 *       jdbc: {@code runIdColumn}+{@code chunkColumn} deletes this run's rows from the resume chunk before writing, or
 *       {@code keyColumns} deletes by key before each insert;
 *       file/sftp: a run-named file, replaced atomically;
 *       http: {@code Idempotency-Key: runId:step:chunk};
 *       outbox: events and checkpoint in one transaction.</li>
 *   <li><b>Fencing.</b> Every checkpoint checks the run epoch, so a node that lost its claim stops.</li>
 * </ul>
 */
public final class PipelineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineRunner.class);
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,127}");
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final java.util.concurrent.ScheduledExecutorService HEARTBEATS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sangam-pipeline-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** Told about every run that reached a final state. */
    @FunctionalInterface
    public interface CompletionListener {
        void completed(PipelineStore.Run run);
    }

    private static final class GateFailed extends RuntimeException {
        GateFailed(String message) {
            super(message);
        }
    }

    private final PipelineStore store;
    private final PipelineResources res;
    private final Transactions tx;
    private final FeelEvaluator feel;
    private final String node;
    private volatile CompletionListener listener = r -> {
    };

    /** @param tx transactions on the product database (checkpoints, outbox loads) */
    public PipelineRunner(PipelineStore store, PipelineResources res, Transactions tx, FeelEvaluator feel, String node) {
        this.store = store;
        this.res = res;
        this.tx = tx;
        this.feel = feel;
        this.node = node;
    }

    public PipelineRunner onCompletion(CompletionListener l) {
        this.listener = l;
        return this;
    }

    /** Runs a claimable run to its end on this thread. False when another node holds it or it is already final. */
    public boolean execute(String runId) {
        long epoch = store.claim(runId, node, TTL);
        if (epoch < 0) {
            return false;
        }
        PipelineStore.Run run = store.run(runId).orElseThrow();
        PipelineDefinition def = PipelineDefinition.parse(run.definitionJson());
        Map<String, Object> params = Json.readMap(run.paramsJson());
        Ctx ctx = new Ctx(run, def, params, epoch, res.stageRoot().resolve(runId));
        String current = null;
        java.util.concurrent.ScheduledFuture<?> beat = HEARTBEATS.scheduleWithFixedDelay(() -> {
            try {
                store.heartbeat(runId, epoch, TTL);
            } catch (RuntimeException e) {
                LOG.debug("pipeline heartbeat for {}: {}", runId, e.getMessage());   // the next fenced write stops the run
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
        try {
            Files.createDirectories(ctx.dir);
            for (int i = 0; i < def.steps().size(); i++) {
                PipelineDefinition.Step s = def.steps().get(i);
                current = s.id();
                var prior = store.step(runId, s.id());
                if (prior.isPresent() && "DONE".equals(prior.get().state())) {
                    ctx.absorb(prior.get());
                    continue;
                }
                store.heartbeat(runId, epoch, TTL);
                store.stepStarted(runId, s.id(), i);
                ctx.stepRejected = 0;
                Tally t = switch (s.kind()) {
                    case EXTRACT -> extract(ctx, s);
                    case TRANSFORM -> transform(ctx, s);
                    case GATE -> gate(ctx, s);
                    case LOAD -> load(ctx, s, prior.map(PipelineStore.StepRun::checkpoint).orElse(0L));
                };
                store.stepDone(runId, epoch, s.id(), t.in, t.out, t.rejected, t.ref);
                ctx.absorb(new PipelineStore.StepRun(runId, s.id(), i, "DONE", t.in, t.out, t.rejected, t.ref, 0, null));
            }
            store.finish(runId, epoch, "SUCCEEDED", Math.max(0, ctx.firstIn), ctx.lastOut, ctx.rejected, null);
            event(run, def, "sangam.pipeline.succeeded", ctx);
            if (def.dataset() != null) {
                event(run, def, "sangam.dataset.available", ctx);
            }
            cleanStage(ctx.dir);
        } catch (PipelineStore.LostClaimException e) {
            LOG.warn("{}: another node took over", e.getMessage());
            return false;
        } catch (GateFailed e) {
            end(ctx, current, "FAILED_QUALITY", e.getMessage());
        } catch (IOException | SQLException | RuntimeException e) {
            LOG.warn("pipeline run {} failed at step {}", runId, current, e);
            end(ctx, current, "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            beat.cancel(false);
        }
        store.run(runId).ifPresent(r -> {
            try {
                listener.completed(r);
            } catch (RuntimeException e) {
                LOG.warn("pipeline completion listener failed for {}", runId, e);
            }
        });
        return true;
    }

    private void end(Ctx ctx, String step, String state, String error) {
        try {
            if (step != null) {
                store.stepFailed(ctx.run.runId(), step, error);
            }
            store.finish(ctx.run.runId(), ctx.epoch, state, Math.max(0, ctx.firstIn), ctx.lastOut, ctx.rejected, error);
            event(ctx.run, ctx.def, "sangam.pipeline.failed", ctx);
        } catch (PipelineStore.LostClaimException e) {
            LOG.warn(e.getMessage());
        }
    }

    private void event(PipelineStore.Run run, PipelineDefinition def, String type, Ctx ctx) {
        if (res.outbox() == null) {
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", run.runId());
        p.put("pipelineId", def.id());
        p.put("version", def.version());
        p.put("dataset", def.dataset());
        p.put("recordsIn", Math.max(0, ctx.firstIn));
        p.put("recordsOut", ctx.lastOut);
        p.put("rejected", ctx.rejected);
        try {
            tx.inTransaction(c -> res.outbox().append(c, run.tenantId(), "pipeline:" + run.runId(), type, p));
        } catch (RuntimeException e) {
            LOG.warn("cannot append {} for run {}", type, run.runId(), e);
        }
    }

    /** Staged data may hold personal data: removed on success. Reject files stay for the operator. */
    private static void cleanStage(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                if (!p.getFileName().toString().endsWith(".rejects.jsonl")) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    // ------------------------------------------------------------------ extract

    private Tally extract(Ctx ctx, PipelineDefinition.Step s) throws IOException, SQLException {
        Path tmp = ctx.dir.resolve(s.id() + ".part");
        long[] n = {0};
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            Consumer<Map<String, Object>> sink = rec -> {
                writeLine(w, rec);
                n[0]++;
                if (n[0] % 10_000 == 0) {
                    store.heartbeat(ctx.run.runId(), ctx.epoch, TTL);
                }
            };
            switch (s.type()) {
                case "jdbc" -> extractJdbc(ctx, s, sink);
                case "file", "sftp" -> parse(new String(res.file(s.require("transport")).read(s.require("dir"), ctx.template(s.require("name"))),
                        StandardCharsets.UTF_8), s.str("format"), sink);
                case "http" -> extractHttp(ctx, s, sink);
                default -> throw new IllegalArgumentException("unknown extract type " + s.type());
            }
        }
        Path out = ctx.dir.resolve(s.id() + ".jsonl");
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        return new Tally(n[0], n[0], 0, out.toString());
    }

    private void extractJdbc(Ctx ctx, PipelineDefinition.Step s, Consumer<Map<String, Object>> sink) throws SQLException {
        try (Connection c = res.dataSource(s.require("datasource")).getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);   // PostgreSQL streams only inside a transaction
            try (PreparedStatement ps = c.prepareStatement(s.require("sql"))) {
                ps.setFetchSize(1000);
                int i = 1;
                for (Object name : s.list("params")) {
                    ps.setObject(i++, ctx.params.get(String.valueOf(name)));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> rec = new LinkedHashMap<>();
                        for (int k = 1; k <= md.getColumnCount(); k++) {
                            Object v = rs.getObject(k);
                            rec.put(md.getColumnLabel(k), v == null || v instanceof Number || v instanceof Boolean || v instanceof String ? v : v.toString());
                        }
                        sink.accept(rec);
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    private void extractHttp(Ctx ctx, PipelineDefinition.Step s, Consumer<Map<String, Object>> sink) {
        HttpTransport.Outcome o = res.http().execute(ctx.run.tenantId(), HttpTransport.Call.get(URI.create(ctx.template(s.require("url"))), headers(s)), 0);
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw new IllegalStateException("HTTP extract failed: " + o);
        }
        JsonNode n = Json.tree(r.body());
        if (s.str("recordsPath") != null && n != null) {
            for (String part : s.str("recordsPath").split("\\.")) {
                n = n.path(part);
            }
        }
        if (n == null || !n.isArray()) {
            throw new IllegalStateException("HTTP extract did not return a JSON array");
        }
        n.forEach(e -> sink.accept(Json.toMap(e)));
    }

    private Map<String, String> headers(PipelineDefinition.Step s) {
        Map<String, String> h = new LinkedHashMap<>();
        s.map("headers").forEach((k, v) -> h.put(k, String.valueOf(v)));
        if (s.str("authorization") != null) {
            h.put("Authorization", res.secrets().require(s.str("authorization")));
        }
        return h;
    }

    static void parse(String text, String format, Consumer<Map<String, Object>> sink) throws IOException {
        String f = format == null ? "csv" : format;
        switch (f) {
            case "jsonl" -> {
                try (BufferedReader r = new BufferedReader(new StringReader(text))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.isBlank()) {
                            sink.accept(Json.readMap(line));
                        }
                    }
                }
            }
            case "json" -> {
                JsonNode n = Json.tree(text);
                if (n == null || !n.isArray()) {
                    throw new IllegalArgumentException("file is not a JSON array");
                }
                n.forEach(e -> sink.accept(Json.toMap(e)));
            }
            case "csv" -> {
                List<List<String>> rows = csv(text);
                if (rows.isEmpty()) {
                    return;
                }
                List<String> head = rows.get(0);
                for (List<String> row : rows.subList(1, rows.size())) {
                    Map<String, Object> rec = new LinkedHashMap<>();
                    for (int i = 0; i < head.size(); i++) {
                        rec.put(head.get(i), i < row.size() ? row.get(i) : null);
                    }
                    sink.accept(rec);
                }
            }
            default -> throw new IllegalArgumentException("unknown format " + f);
        }
    }

    /** RFC 4180: quotes, doubled quotes, line breaks inside quotes. */
    static List<List<String>> csv(String t) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < t.length() && t.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    f.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (ch == '\n') {
                row.add(f.toString());
                f.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (ch != '\r') {
                f.append(ch);
            }
        }
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ transform

    private Tally transform(Ctx ctx, PipelineDefinition.Step s) throws IOException {
        Path tmp = ctx.dir.resolve(s.id() + ".part");
        Path rejects = ctx.dir.resolve(s.id() + ".rejects.jsonl");
        Files.deleteIfExists(rejects);
        long in = 0, out = 0;
        try (BufferedReader r = Files.newBufferedReader(ctx.input(), StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                in++;
                Map<String, Object> rec = Json.readMap(line);
                try {
                    Map<String, Object> result = apply(ctx, s, rec);
                    if (result != null) {
                        writeLine(w, result);
                        out++;
                    }
                } catch (RuntimeException e) {
                    reject(ctx, s, rejects, rec, e.getMessage());
                }
                if (in % 10_000 == 0) {
                    store.heartbeat(ctx.run.runId(), ctx.epoch, TTL);
                }
            }
        }
        Path output = ctx.dir.resolve(s.id() + ".jsonl");
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        return new Tally(in, out, ctx.stepRejected, output.toString());
    }

    /** Null drops the record (a FEEL filter); an exception rejects it. */
    private Map<String, Object> apply(Ctx ctx, PipelineDefinition.Step s, Map<String, Object> rec) {
        switch (s.type()) {
            case "mapping" -> {
                Map<String, Object> out = "true".equals(s.str("keepUnmapped")) ? new LinkedHashMap<>(rec) : new LinkedHashMap<>();
                s.map("fields").forEach((target, spec) -> {
                    if (spec instanceof Map<?, ?> m && m.containsKey("const")) {
                        out.put(target, m.get("const"));
                    } else {
                        out.put(target, rec.get(String.valueOf(spec)));
                    }
                });
                return out;
            }
            case "feel" -> {
                Map<String, Object> vars = new LinkedHashMap<>(ctx.params);
                vars.putAll(rec);
                if (s.str("filter") != null && !Boolean.TRUE.equals(feel.evaluate(s.str("filter"), vars))) {
                    return null;
                }
                Map<String, Object> out = new LinkedHashMap<>(rec);
                s.map("fields").forEach((target, expr) -> out.put(target, feel.evaluate(String.valueOf(expr), vars)));
                return out;
            }
            case "dmn" -> {
                Map<String, Object> vars = new LinkedHashMap<>(ctx.params);
                vars.putAll(rec);
                Map<String, Object> out = new LinkedHashMap<>(rec);
                Map<String, Object> d = decisions().evaluate(ctx.run.tenantId(), s.require("decisionKey"), vars);
                if (d != null) {
                    out.putAll(d);
                }
                if (Boolean.FALSE.equals(out.get("valid"))) {
                    throw new IllegalArgumentException(String.valueOf(out.getOrDefault("reason", "rejected by decision")));
                }
                return out;
            }
            case "validate" -> {
                for (Object o : s.list("rules")) {
                    validate((Map<?, ?>) o, rec);
                }
                return rec;
            }
            default -> throw new IllegalStateException("unknown transform type " + s.type());
        }
    }

    private static void validate(Map<?, ?> rule, Map<String, Object> rec) {
        String field = String.valueOf(rule.get("field"));
        Object v = rec.get(field);
        if (v == null || v.toString().isEmpty()) {
            if (Boolean.TRUE.equals(rule.get("required"))) {
                throw new IllegalArgumentException(field + " is required");
            }
            return;
        }
        if (rule.get("regex") != null && !v.toString().matches(String.valueOf(rule.get("regex")))) {
            throw new IllegalArgumentException(field + " does not match the pattern");
        }
        if (rule.get("min") != null || rule.get("max") != null) {
            BigDecimal d;
            try {
                d = new BigDecimal(v.toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " is not a number");
            }
            if (rule.get("min") != null && d.compareTo(new BigDecimal(rule.get("min").toString())) < 0) {
                throw new IllegalArgumentException(field + " is below the minimum");
            }
            if (rule.get("max") != null && d.compareTo(new BigDecimal(rule.get("max").toString())) > 0) {
                throw new IllegalArgumentException(field + " is above the maximum");
            }
        }
    }

    private PipelineResources.DecisionPort decisions() {
        if (res.decisions() == null) {
            throw new IllegalStateException("no DMN decision port configured");
        }
        return res.decisions();
    }

    // ------------------------------------------------------------------ quality gate

    private Tally gate(Ctx ctx, PipelineDefinition.Step s) {
        Map<String, Object> m = new LinkedHashMap<>(ctx.params);
        m.put("recordsIn", Math.max(0, ctx.firstIn));
        m.put("recordsOut", ctx.lastOut);
        m.put("rejected", ctx.rejected);
        boolean pass = switch (s.type()) {
            case "feel" -> Boolean.TRUE.equals(feel.evaluate(s.require("expression"), m));
            case "dmn" -> {
                Map<String, Object> d = decisions().evaluate(ctx.run.tenantId(), s.require("decisionKey"), m);
                yield d != null && Boolean.TRUE.equals(d.get("pass"));
            }
            default -> throw new IllegalArgumentException("unknown gate type " + s.type());
        };
        if (!pass) {
            throw new GateFailed("quality gate " + s.id() + " failed: " + m);
        }
        return new Tally(ctx.lastOut, ctx.lastOut, 0, ctx.currentRef);
    }

    // ------------------------------------------------------------------ load

    private Tally load(Ctx ctx, PipelineDefinition.Step s, long resumeChunk) throws IOException, SQLException {
        Path rejects = ctx.dir.resolve(s.id() + ".rejects.jsonl");
        if (s.type().equals("file") || s.type().equals("sftp")) {
            return loadFile(ctx, s);
        }
        PipelineStore.StepRun prior = store.step(ctx.run.runId(), s.id()).orElseThrow();
        long in = resumeChunk > 0 ? prior.recordsIn() : 0;
        long out = resumeChunk > 0 ? prior.recordsOut() : 0;
        ctx.stepRejected = resumeChunk > 0 ? prior.rejected() : 0;
        ctx.rejected += ctx.stepRejected;
        if (s.type().equals("jdbc")) {
            prepareJdbc(ctx, s, resumeChunk);
        }
        long chunk = 0;
        try (BufferedReader r = Files.newBufferedReader(ctx.input(), StandardCharsets.UTF_8)) {
            List<Map<String, Object>> buf = new ArrayList<>(s.chunkSize());
            String line;
            while (true) {
                line = r.readLine();
                if (line != null) {
                    if (chunk >= resumeChunk) {
                        buf.add(Json.readMap(line));
                    } else {
                        buf.add(null);   // committed earlier: only counted to find the chunk boundary
                    }
                }
                if (buf.size() >= s.chunkSize() || (line == null && !buf.isEmpty())) {
                    if (chunk >= resumeChunk) {
                        in += buf.size();
                        out += writeChunk(ctx, s, buf, chunk, rejects, in, out);
                    }
                    chunk++;
                    buf.clear();
                }
                if (line == null) {
                    break;
                }
            }
        }
        return new Tally(in, out, ctx.stepRejected, null);
    }

    /** Writes one chunk and its checkpoint; returns rows written. */
    private long writeChunk(Ctx ctx, PipelineDefinition.Step s, List<Map<String, Object>> rows, long chunk, Path rejects, long in, long out)
            throws IOException, SQLException {
        String run = ctx.run.runId();
        switch (s.type()) {
            case "jdbc" -> {
                long n = loadJdbcChunk(ctx, s, rows, chunk, rejects);
                store.checkpoint(run, ctx.epoch, s.id(), chunk + 1, in, out + n, ctx.stepRejected);
                return n;
            }
            case "http" -> {
                Map<String, String> h = headers(s);
                h.put("Idempotency-Key", run + ":" + s.id() + ":" + chunk);
                h.put("Content-Type", "application/json");
                HttpTransport.Outcome o = res.http().execute(ctx.run.tenantId(),
                        HttpTransport.Call.post(URI.create(ctx.template(s.require("url"))), h, Json.bytes(rows), true), 0);
                if (o instanceof HttpTransport.Outcome.Response r && r.status() >= 400 && r.status() < 500 && r.status() != 408 && r.status() != 429) {
                    for (Map<String, Object> rec : rows) {
                        reject(ctx, s, rejects, rec, "HTTP " + r.status());
                    }
                    store.checkpoint(run, ctx.epoch, s.id(), chunk + 1, in, out, ctx.stepRejected);
                    return 0;
                }
                if (!(o instanceof HttpTransport.Outcome.Response r2) || !r2.is2xx()) {
                    throw new IllegalStateException("HTTP load failed: " + o);
                }
                store.checkpoint(run, ctx.epoch, s.id(), chunk + 1, in, out + rows.size(), ctx.stepRejected);
                return rows.size();
            }
            case "outbox" -> {
                String type = s.require("eventType");
                String rootField = s.str("rootField");
                tx.inTransaction(c -> {
                    for (Map<String, Object> rec : rows) {
                        Object root = rootField == null ? null : rec.get(rootField);
                        res.outbox().append(c, ctx.run.tenantId(), root == null ? "pipeline:" + run : root.toString(), type, canonical(rec));
                    }
                    store.checkpoint(c, run, ctx.epoch, s.id(), chunk + 1, in, out + rows.size(), ctx.stepRejected);
                    return null;
                });
                return rows.size();
            }
            default -> throw new IllegalArgumentException("unknown load type " + s.type());
        }
    }

    /** R33: decimals as strings, which the canonical outbox writer requires. */
    private static Map<String, Object> canonical(Map<String, Object> rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        rec.forEach((k, v) -> out.put(k, v instanceof BigDecimal || v instanceof Double || v instanceof Float ? v.toString() : v));
        return out;
    }

    private Tally loadFile(Ctx ctx, PipelineDefinition.Step s) throws IOException {
        String format = s.str("format") == null ? "jsonl" : s.str("format");
        if (!format.equals("jsonl")) {
            throw new IllegalArgumentException("file load supports jsonl only");
        }
        String name = ctx.template(s.str("name") == null ? ctx.def.id() + "-{runId}.jsonl" : s.str("name"));
        if (!name.contains(ctx.run.runId())) {
            throw new IllegalArgumentException("file load name must contain {runId} (idempotent load)");
        }
        FileTransport f = res.file(s.require("transport"));
        byte[] data = Files.readAllBytes(ctx.input());
        f.write(s.require("dir"), name, data);
        return new Tally(ctx.lastOut, ctx.lastOut, 0, name);
    }

    private void prepareJdbc(Ctx ctx, PipelineDefinition.Step s, long resumeChunk) throws SQLException {
        String runCol = s.str("runIdColumn");
        if (runCol == null) {
            if (s.list("keyColumns").isEmpty()) {
                throw new IllegalArgumentException("step " + s.id() + ": jdbc load needs runIdColumn+chunkColumn or keyColumns (idempotent load)");
            }
            return;
        }
        String chunkCol = ident(s.require("chunkColumn"));
        try (Connection c = res.dataSource(s.require("datasource")).getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + ident(s.require("table")) + " WHERE " + ident(runCol) + "=? AND " + chunkCol + ">=?")) {
            ps.setString(1, ctx.run.runId());
            ps.setLong(2, resumeChunk);
            ps.executeUpdate();
            if (!c.getAutoCommit()) {
                c.commit();
            }
        }
    }

    private long loadJdbcChunk(Ctx ctx, PipelineDefinition.Step s, List<Map<String, Object>> rows, long chunk, Path rejects)
            throws SQLException, IOException {
        try (Connection c = res.dataSource(s.require("datasource")).getConnection()) {
            c.setAutoCommit(false);
            try {
                for (Map<String, Object> rec : rows) {
                    writeRow(c, ctx, s, rec, chunk);
                }
                c.commit();
                return rows.size();
            } catch (SQLException e) {
                c.rollback();
                if (!isDataError(e)) {
                    throw e;
                }
            }
            // a bad row in the chunk: row by row, rejecting only the bad ones
            long n = 0;
            for (Map<String, Object> rec : rows) {
                try {
                    writeRow(c, ctx, s, rec, chunk);
                    c.commit();
                    n++;
                } catch (SQLException e) {
                    c.rollback();
                    if (!isDataError(e)) {
                        throw e;
                    }
                    reject(ctx, s, rejects, rec, e.getMessage());
                }
            }
            return n;
        }
    }

    private void writeRow(Connection c, Ctx ctx, PipelineDefinition.Step s, Map<String, Object> rec, long chunk) throws SQLException {
        String table = ident(s.require("table"));
        Map<String, Object> columns = s.map("columns");
        List<Object> keys = s.list("keyColumns");
        if (!keys.isEmpty()) {
            StringBuilder del = new StringBuilder("DELETE FROM ").append(table).append(" WHERE ");
            List<Object> vals = new ArrayList<>();
            for (Object k : keys) {
                del.append(vals.isEmpty() ? "" : " AND ").append(ident(k.toString())).append("=?");
                vals.add(rec.get(String.valueOf(columns.get(k.toString()))));
            }
            try (PreparedStatement ps = c.prepareStatement(del.toString())) {
                for (int i = 0; i < vals.size(); i++) {
                    ps.setObject(i + 1, vals.get(i));
                }
                ps.executeUpdate();
            }
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        List<Object> vals = new ArrayList<>();
        columns.forEach((col, field) -> {
            cols.append(vals.isEmpty() ? "" : ",").append(ident(col));
            marks.append(vals.isEmpty() ? "?" : ",?");
            vals.add(rec.get(String.valueOf(field)));
        });
        if (s.str("runIdColumn") != null) {
            cols.append(',').append(ident(s.str("runIdColumn"))).append(',').append(ident(s.require("chunkColumn")));
            marks.append(",?,?");
            vals.add(ctx.run.runId());
            vals.add(chunk);
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")")) {
            for (int i = 0; i < vals.size(); i++) {
                ps.setObject(i + 1, vals.get(i));
            }
            ps.executeUpdate();
        }
    }

    /** SQLState class 22 (data) or 23 (constraint) is the row's fault; anything else stops the step. */
    private static boolean isDataError(SQLException e) {
        String st = e.getSQLState();
        return st != null && (st.startsWith("22") || st.startsWith("23"));
    }

    private static String ident(String s) {
        if (!IDENT.matcher(s).matches()) {
            throw new IllegalArgumentException("invalid SQL identifier " + s);
        }
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private void reject(Ctx ctx, PipelineDefinition.Step s, Path file, Map<String, Object> rec, String reason) {
        ctx.stepRejected++;
        ctx.rejected++;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("reason", reason);
        line.put("record", rec);
        try {
            Files.writeString(file, Json.write(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (ctx.rejected > ctx.def.maxRejects()) {
            throw new TooManyRejects("run " + ctx.run.runId() + " exceeded maxRejects " + ctx.def.maxRejects() + " at step " + s.id());
        }
    }

    /** Thrown from inside the row-level catch blocks, so it ends the step instead of rejecting another row. */
    private static final class TooManyRejects extends RuntimeException {
        TooManyRejects(String message) {
            super(message);
        }
    }

    private static void writeLine(BufferedWriter w, Map<String, Object> rec) {
        try {
            w.write(Json.write(rec));
            w.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Tally(long in, long out, long rejected, String ref) {
    }

    private static final class Ctx {
        final PipelineStore.Run run;
        final PipelineDefinition def;
        final Map<String, Object> params;
        final long epoch;
        final Path dir;
        long firstIn = -1;
        long lastOut;
        long rejected;
        long stepRejected;
        String currentRef;

        Ctx(PipelineStore.Run run, PipelineDefinition def, Map<String, Object> params, long epoch, Path dir) {
            this.run = run;
            this.def = def;
            this.params = params;
            this.epoch = epoch;
            this.dir = dir;
        }

        void absorb(PipelineStore.StepRun s) {
            if (firstIn < 0) {
                firstIn = s.recordsIn();
            }
            lastOut = s.recordsOut();
            rejected += stepRejectedOf(s);
            if (s.outputRef() != null && s.outputRef().endsWith(".jsonl")) {
                currentRef = s.outputRef();
            }
            stepRejected = 0;
        }

        private long stepRejectedOf(PipelineStore.StepRun s) {
            return s.rejected() - stepRejected;   // rows already counted live while the step ran
        }

        Path input() {
            if (currentRef == null) {
                throw new IllegalStateException("no staged input");
            }
            return Path.of(currentRef);
        }

        /** {@code {runId}} and {@code {param}} placeholders. */
        String template(String t) {
            String out = t.replace("{runId}", run.runId());
            for (Map.Entry<String, Object> e : params.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
            return out;
        }
    }
}
