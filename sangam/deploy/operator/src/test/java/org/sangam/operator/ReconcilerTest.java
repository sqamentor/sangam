// SPDX-License-Identifier: Apache-2.0
package org.sangam.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class ReconcilerTest {

    static final String NS = "prod";
    static final String CR = "/apis/sangam.io/v1alpha1/namespaces/prod/sangaminstallations/sangam";
    static final String CELL = "/apis/sangam.io/v1alpha1/namespaces/prod/sangamcells/cell-a";
    static final String JOBS = "/apis/batch/v1/namespaces/prod/jobs";
    static final String DEPLOY = "/apis/apps/v1/namespaces/prod/deployments/sangam-server";
    static final String SVC = "/api/v1/namespaces/prod/services/sangam-server";
    static final String PVC = "/api/v1/namespaces/prod/persistentvolumeclaims/sangam-state";
    static final String CM = "/api/v1/namespaces/prod/configmaps/sangam-cell";

    final FakeKube kube = new FakeKube();
    final Reconciler reconciler = new Reconciler(kube, new PrintStream(new ByteArrayOutputStream()));

    static String installation(String image, String extra) {
        return """
                {"apiVersion":"sangam.io/v1alpha1","kind":"SangamInstallation",
                 "metadata":{"name":"sangam","namespace":"prod","uid":"u-1","generation":1},
                 "spec":{"image":"%s","replicas":2,
                   "database":{"type":"postgres","jdbcUrl":"jdbc:postgresql://db:5432/sangam","existingSecret":"db"}%s}}
                """.formatted(image, extra);
    }

    void pass() throws Exception {
        reconciler.reconcileNamespace(NS);
    }

    JsonNode status() {
        return kube.objects.get(CR).path("status");
    }

    String jobPath(String image) throws Exception {
        return JOBS + "/" + Manifests.migrationJobName(InstallationSpec.parse(kube.get(CR)), image);
    }

    void finishJob(String image, String condition) throws Exception {
        ObjectNode job = kube.objects.get(jobPath(image));
        ObjectNode st = job.putObject("status");
        st.putArray("conditions").addObject().put("type", condition).put("status", "True");
        if (condition.equals("Complete")) {
            st.put("succeeded", 1);
        }
    }

    void rolledOut(int replicas) {
        ObjectNode d = kube.objects.get(DEPLOY);
        d.putObject("status").put("observedGeneration", d.path("metadata").path("generation").asLong())
                .put("replicas", replicas).put("updatedReplicas", replicas).put("availableReplicas", replicas)
                .put("readyReplicas", replicas);
    }

    @Test
    void firstInstallMigratesBeforeAnyServerPodExists() throws Exception {
        kube.put(CR, installation("reg/sangam-server:0.1.0", ""));
        pass();
        assertEquals("Migrating", status().path("phase").asText());
        JsonNode job = kube.get(jobPath("reg/sangam-server:0.1.0"));
        assertNotNull(job, "one migrate-only Job for the image");
        assertNull(kube.get(DEPLOY), "no server pod before the migration completed");
        JsonNode c = job.path("spec").path("template").path("spec").path("containers").get(0);
        assertEquals("reg/sangam-server:0.1.0", c.path("image").asText());
        assertEquals("migrate-only", env(c, "SANGAM_LIQUIBASE_MODE"));
        assertEquals("false", env(c, "SANGAM_JOB_EXECUTOR_ENABLED"));
        assertEquals(0, job.path("spec").path("backoffLimit").asInt(-1), "a failed migration is not retried blindly");
        assertEquals("Never", job.path("spec").path("template").path("spec").path("restartPolicy").asText());

        pass();   // still running: nothing new
        assertEquals("Migrating", status().path("phase").asText());
        assertNull(kube.get(DEPLOY));

        finishJob("reg/sangam-server:0.1.0", "Complete");
        pass();
        assertEquals("reg/sangam-server:0.1.0", status().path("migratedImage").asText());
        JsonNode d = kube.get(DEPLOY);
        JsonNode sc = d.path("spec").path("template").path("spec").path("containers").get(0);
        assertEquals("reg/sangam-server:0.1.0", sc.path("image").asText());
        assertEquals("validate", env(sc, "SANGAM_LIQUIBASE_MODE"), "serving pods only validate the schema");
        assertEquals(0, d.path("spec").path("strategy").path("rollingUpdate").path("maxUnavailable").asInt(-1));
        assertNotNull(kube.get(SVC));
        assertNotNull(kube.get(PVC), "state claim created when none is named");
        assertTrue(kube.get(PVC).path("metadata").path("ownerReferences").isMissingNode(), "the claim outlives the resource");
        assertEquals("RollingOut", status().path("phase").asText());

        rolledOut(2);
        pass();
        assertEquals("Ready", status().path("phase").asText());
    }

    @Test
    void upgradeKeepsTheOldImageServingUntilTheNewMigrationCompletes() throws Exception {
        installed("reg/sangam-server:0.1.0");
        ObjectNode before = (ObjectNode) status().deepCopy();
        kube.put(CR, installation("reg/sangam-server:0.1.1", "")).set("status", before);
        pass();
        assertEquals("Migrating", status().path("phase").asText());
        assertNotNull(kube.get(jobPath("reg/sangam-server:0.1.1")));
        assertEquals("reg/sangam-server:0.1.0", serverImage(), "N-1 keeps serving during the migration (R32)");

        finishJob("reg/sangam-server:0.1.1", "Complete");
        pass();
        assertEquals("reg/sangam-server:0.1.1", serverImage());
        assertEquals("reg/sangam-server:0.1.1", status().path("migratedImage").asText());
    }

    @Test
    void failedMigrationStopsTheRolloutAndIsNotRetried() throws Exception {
        installed("reg/sangam-server:0.1.0");
        ObjectNode status = (ObjectNode) status().deepCopy();
        kube.put(CR, installation("reg/sangam-server:0.2.0", "")).set("status", status);
        pass();
        finishJob("reg/sangam-server:0.2.0", "Failed");
        int writes = kube.writes.size();
        pass();
        assertEquals("MigrationFailed", status().path("phase").asText());
        assertTrue(status().path("message").asText().contains("delete the Job to retry"));
        assertEquals("reg/sangam-server:0.1.0", serverImage());
        assertTrue(kube.writes.subList(writes, kube.writes.size()).stream().noneMatch(w -> w.startsWith("create " + JOBS)),
                "no second Job");
    }

    @Test
    void anotherActiveMigrationOfTheSameInstallationIsWaitedFor() throws Exception {
        kube.put(CR, installation("reg/sangam-server:0.1.0", ""));
        pass();
        kube.objects.get(jobPath("reg/sangam-server:0.1.0")).putObject("status").put("active", 1);
        ObjectNode status = (ObjectNode) status().deepCopy();
        kube.put(CR, installation("reg/sangam-server:0.1.1", "")).set("status", status);
        pass();
        assertEquals("Migrating", status().path("phase").asText());
        assertTrue(status().path("message").asText().startsWith("waiting for migration Job"));
        assertNull(kube.get(jobPath("reg/sangam-server:0.1.1")), "never two migrations at once");
    }

    @Test
    void modeNoneRollsOutWithoutAJob() throws Exception {
        kube.put(CR, installation("reg/sangam-server:0.1.0", ",\"migration\":{\"mode\":\"none\"}"));
        pass();
        assertEquals("reg/sangam-server:0.1.0", serverImage());
        assertTrue(kube.writes.stream().noneMatch(w -> w.startsWith("create " + JOBS)));
    }

    @Test
    void invalidSpecIsReportedAndNothingIsCreated() throws Exception {
        kube.put(CR, installation("reg/sangam-server:0.1.0", ",\"env\":[{\"name\":\"SANGAM_DB_URL\",\"value\":\"x\"}]"));
        pass();
        assertEquals("Invalid", status().path("phase").asText());
        assertTrue(status().path("message").asText().contains("SANGAM_DB_URL"));
        assertEquals(1, kube.writes.size(), "only the status");
    }

    @Test
    void pausedChangesNothing() throws Exception {
        kube.put(CR, installation("reg/sangam-server:0.1.0", ",\"paused\":true"));
        pass();
        assertEquals("Paused", status().path("phase").asText());
        assertNull(kube.get(jobPath("reg/sangam-server:0.1.0")));
    }

    @Test
    void restartTokenRollsThePods() throws Exception {
        installed("reg/sangam-server:0.1.0");
        ObjectNode status = (ObjectNode) status().deepCopy();
        kube.put(CR, installation("reg/sangam-server:0.1.0", ",\"restartToken\":\"2026-11-12T17:00Z\"")).set("status", status);
        pass();
        assertEquals("2026-11-12T17:00Z", kube.get(DEPLOY).path("spec").path("template").path("metadata").path("annotations")
                .path(Manifests.RESTART_ANNOTATION).asText());
    }

    @Test
    void idlePassWritesNoStatus() throws Exception {
        installed("reg/sangam-server:0.1.0");
        rolledOut(2);
        pass();
        int before = (int) kube.writes.stream().filter(w -> w.startsWith("status ")).count();
        pass();
        assertEquals(before, kube.writes.stream().filter(w -> w.startsWith("status ")).count());
    }

    @Test
    void cellIsBoundAndItsIdCannotChange() throws Exception {
        kube.put(CR, installation("reg/sangam-server:0.1.0", ",\"migration\":{\"mode\":\"none\"},\"outboxDestinations\":\"kafka:main\""));
        kube.put(CELL, cell("in-ra-01"));
        pass();
        assertEquals("Bound", kube.objects.get(CELL).path("status").path("phase").asText());
        assertEquals("in-ra-01", kube.objects.get(CELL).path("status").path("cellId").asText());
        assertEquals("in-ra-01", kube.get(CM).path("data").path("SANGAM_CELL_ID").asText());
        assertEquals("IN", kube.get(CM).path("data").path("SANGAM_CELL_RESIDENCY").asText(), "residency defaults to jurisdiction");
        JsonNode c = kube.get(DEPLOY).path("spec").path("template").path("spec").path("containers").get(0);
        assertEquals("kafka:main,rollup:national", env(c, "SANGAM_OUTBOX_DESTINATIONS"));
        assertEquals("sangam-cell", c.path("envFrom").get(0).path("configMapRef").path("name").asText());
        assertEquals("controlPlaneToken", c.path("env").findValues("secretKeyRef").stream()
                .filter(n -> n.path("name").asText().equals("cell-tokens")).findFirst().orElseThrow().path("key").asText());

        ObjectNode cellStatus = (ObjectNode) kube.objects.get(CELL).path("status").deepCopy();
        kube.put(CELL, cell("in-ra-02")).set("status", cellStatus);
        String deployBefore = kube.get(DEPLOY).toString();
        pass();
        assertEquals("Invalid", status().path("phase").asText());
        assertEquals("Invalid", kube.objects.get(CELL).path("status").path("phase").asText());
        assertEquals(deployBefore, kube.get(DEPLOY).toString(), "nothing rolled out with a changed cell id");
    }

    @Test
    void installationThatWasACellCannotRebindToAnotherId() throws Exception {
        kube.put(CR, installation("reg/sangam-server:0.1.0", ",\"migration\":{\"mode\":\"none\"}"));
        kube.put(CELL, cell("in-ra-01"));
        pass();
        kube.objects.remove(CELL);
        pass();
        assertEquals("in-ra-01", status().path("cellId").asText(), "kept after the cell is removed");
        kube.put(CELL, cell("in-ra-09"));
        pass();
        assertEquals("Invalid", status().path("phase").asText());
    }

    @Test
    void cellNamingAMissingInstallationIsUnbound() throws Exception {
        kube.put(CELL, cell("in-ra-01"));
        pass();
        assertEquals("Unbound", kube.objects.get(CELL).path("status").path("phase").asText());
    }

    // ---- helpers -------------------------------------------------------------------------------

    void installed(String image) throws Exception {
        kube.put(CR, installation(image, ""));
        pass();
        finishJob(image, "Complete");
        pass();
    }

    String serverImage() {
        return kube.get(DEPLOY).path("spec").path("template").path("spec").path("containers").get(0).path("image").asText();
    }

    static String env(JsonNode container, String name) {
        for (JsonNode e : container.path("env")) {
            if (e.path("name").asText().equals(name)) {
                return e.path("value").asText();
            }
        }
        return null;
    }

    static String cell(String id) {
        return """
                {"apiVersion":"sangam.io/v1alpha1","kind":"SangamCell",
                 "metadata":{"name":"cell-a","namespace":"prod","uid":"c-1","generation":1},
                 "spec":{"installationRef":"sangam","cellId":"%s","jurisdiction":"IN",
                   "publicUrl":"https://ra.example.test","controlPlaneUrl":"http://cp:8090",
                   "rollupIngestUrl":"http://rollup:8095/api/v1/ingest","gatewayUrl":"http://gw:8000",
                   "existingSecret":"cell-tokens"}}
                """.formatted(id);
    }
}
