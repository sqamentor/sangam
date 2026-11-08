// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.test;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk model-test runs with stored reports, and test export from design to runtime. {@link Executor} runs one case: the integrator binds
 * it to the REST test endpoint or a sandbox engine. A failing or throwing case never stops the batch. Non-deterministic verdicts count
 * apart from deterministic ones. The export bundle is hash-sealed, and {@link #verifyBundle} rejects a changed bundle.
 */
public final class BulkModelTests {

    public record TestCase(String id, String processKey, Map<String, Object> definition) {
    }

    public enum Status { PASSED, FAILED, ERROR, NOT_JUDGED }

    public record CaseResult(String caseId, Status status, boolean deterministic, long millis, String message) {
    }

    public record Report(String runId, String packageId, int passed, int failed, int notJudged, List<CaseResult> cases) {
    }

    @FunctionalInterface
    public interface Executor {
        CaseResult run(String tenantId, TestCase testCase);
    }

    private final Dialect dialect;
    private final Transactions tx;

    public BulkModelTests(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    public Report run(String tenantId, String packageId, List<TestCase> cases, Executor executor, Duration totalBudget, String actor) {
        long deadline = System.nanoTime() + totalBudget.toNanos();
        List<CaseResult> out = new ArrayList<>();
        for (TestCase tc : cases) {
            if (System.nanoTime() > deadline) {
                out.add(new CaseResult(tc.id(), Status.ERROR, true, 0, "not run: batch time budget used"));
                continue;
            }
            long t0 = System.nanoTime();
            try {
                out.add(executor.run(tenantId, tc));
            } catch (RuntimeException e) {
                out.add(new CaseResult(tc.id(), Status.ERROR, true, (System.nanoTime() - t0) / 1_000_000, e.toString()));
            }
        }
        int passed = (int) out.stream().filter(c -> c.status() == Status.PASSED).count();
        int notJudged = (int) out.stream().filter(c -> c.status() == Status.NOT_JUDGED).count();
        int failed = out.size() - passed - notJudged;
        String id = UUID.randomUUID().toString();
        Report report = new Report(id, packageId, passed, failed, notJudged, out);
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO MOD_TEST_RUN(run_id,tenant_id,package_id,report,passed,failed,not_judged,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenantId, packageId, Json.write(report), passed, failed, notJudged, actor, Jdbc.dbNow(c, dialect)));
        return report;
    }

    public List<Map<String, Object>> reports(String tenantId, String packageId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT report FROM MOD_TEST_RUN WHERE tenant_id=? AND package_id=? ORDER BY created_at DESC", rs -> Json.readMap(rs.getString(1)), tenantId, packageId));
    }

    /** Exports test cases and their mock rules from design, sealed with a hash, for import on a runtime environment. */
    public static String exportBundle(String packageId, String packageRevision, List<TestCase> cases, List<Map<String, Object>> mocks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", "sangam-test-bundle-v1");
        body.put("packageId", packageId);
        body.put("packageRevision", packageRevision);
        body.put("cases", cases);
        body.put("mocks", mocks);
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("sha256", Hashes.hex(Hashes.sha256(CanonicalJson.write(Json.MAPPER.convertValue(body, Map.class)))));
        return Json.write(out);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> verifyBundle(String json) {
        Map<String, Object> m = new LinkedHashMap<>(Json.readMap(json));
        Object sent = m.remove("sha256");
        if (!"sangam-test-bundle-v1".equals(m.get("format")) || !Hashes.hex(Hashes.sha256(CanonicalJson.write(m))).equals(sent)) {
            throw new IllegalArgumentException("TEST_BUNDLE_INVALID");
        }
        return m;
    }
}
