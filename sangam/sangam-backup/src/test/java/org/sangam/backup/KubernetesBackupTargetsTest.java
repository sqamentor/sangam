// SPDX-License-Identifier: Apache-2.0
package org.sangam.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.sangam.kube.Kube;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision 155: targets are the chart's backup CronJobs (by label), a trigger is a Job from the jobTemplate like
 * {@code kubectl create job --from}, and the history is the Jobs controlled by those CronJobs.
 */
class KubernetesBackupTargetsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String NS = "sangam";
    static final String SELECTOR = "app.kubernetes.io/component=backup,app.kubernetes.io/instance=prod";

    /** In-memory API: CronJobs answer only the configured selector; Jobs are listed whole. */
    static final class Fake implements Kube {
        final Map<String, ObjectNode> cronJobs = new LinkedHashMap<>();
        final Map<String, ObjectNode> jobs = new LinkedHashMap<>();
        final List<String> paths = new ArrayList<>();

        @Override
        public JsonNode get(String path) {
            paths.add("GET " + path);
            String name = path.substring(path.lastIndexOf('/') + 1);
            return path.contains("/cronjobs/") ? cronJobs.get(name) : jobs.get(name);
        }

        @Override
        public JsonNode list(String path) {
            paths.add("LIST " + path);
            ArrayNode items = JSON.createArrayNode();
            if (path.startsWith("/apis/batch/v1/namespaces/" + NS + "/cronjobs?labelSelector=")) {
                cronJobs.values().forEach(items::add);
            } else if (path.equals("/apis/batch/v1/namespaces/" + NS + "/jobs")) {
                jobs.values().forEach(items::add);
            } else {
                throw new AssertionError("unexpected list " + path);
            }
            return items;
        }

        @Override
        public JsonNode create(String collectionPath, ObjectNode body) {
            paths.add("POST " + collectionPath);
            String name = body.path("metadata").path("name").asText();
            if (jobs.containsKey(name)) {
                return null;
            }
            ObjectNode stored = body.deepCopy();
            jobs.put(name, stored);
            return stored;
        }

        @Override
        public JsonNode apply(String path, ObjectNode body) {
            throw new AssertionError("the console never applies");
        }

        @Override
        public JsonNode patchStatus(String path, ObjectNode status) {
            throw new AssertionError("the console never patches status");
        }

        ObjectNode cronJob(String name, String uid, String backupName) {
            ObjectNode cj = JSON.createObjectNode();
            cj.putObject("metadata").put("name", name).put("uid", uid);
            ObjectNode spec = cj.putObject("spec").put("schedule", "15 1 * * *").put("timeZone", "Etc/UTC");
            ObjectNode jt = spec.putObject("jobTemplate");
            jt.putObject("metadata").putObject("labels").put("team", "dba");
            ObjectNode js = jt.putObject("spec").put("backoffLimit", 1);
            ObjectNode tpl = js.putObject("template");
            tpl.putObject("metadata").putObject("labels").put(KubernetesBackupTargets.NAME_LABEL, backupName);
            tpl.putObject("spec").put("restartPolicy", "Never");
            cj.putObject("status").put("lastScheduleTime", "2026-11-12T01:15:00Z").put("lastSuccessfulTime", "2026-11-12T01:40:00Z");
            cronJobs.put(name, cj);
            return cj;
        }

        ObjectNode job(String name, String ownerUid, boolean manual, String conditionType) {
            ObjectNode j = JSON.createObjectNode();
            ObjectNode meta = j.putObject("metadata").put("name", name);
            if (manual) {
                meta.putObject("annotations").put(KubernetesBackupTargets.INSTANTIATE, "manual");
            }
            meta.putArray("ownerReferences").addObject().put("kind", "CronJob").put("uid", ownerUid).put("controller", true);
            ObjectNode st = j.putObject("status").put("startTime", "2026-11-12T01:15:05Z");
            if (conditionType == null) {
                st.put("active", 1);
            } else {
                st.putArray("conditions").addObject().put("type", conditionType).put("status", "True")
                        .put("reason", "BackoffLimitExceeded").put("message", "Job has reached the specified backoff limit")
                        .put("lastTransitionTime", "2026-11-12T01:20:00Z");
                if (conditionType.equals("Complete")) {
                    st.put("completionTime", "2026-11-12T01:39:00Z");
                }
            }
            jobs.put(name, j);
            return j;
        }
    }

    final Fake kube = new Fake();
    final KubernetesBackupTargets targets = new KubernetesBackupTargets(kube, NS, SELECTOR);

    @Test
    void targetsAreTheSelectedCronJobsWithTheChartBackupName() throws IOException {
        kube.cronJob("prod-sangam-pg-logical", "u2", "pg-logical");
        kube.cronJob("prod-sangam-pg-full", "u1", "pg-full");
        List<BackupTargets.Target> list = targets.targets();
        assertEquals(List.of("prod-sangam-pg-full", "prod-sangam-pg-logical"), list.stream().map(BackupTargets.Target::id).toList());
        BackupTargets.Target t = list.get(0);
        assertEquals("pg-full", t.name());
        assertEquals("15 1 * * *", t.schedule());
        assertEquals("Etc/UTC", t.timeZone());
        assertFalse(t.suspended());
        assertEquals(Instant.parse("2026-11-12T01:40:00Z"), t.lastSuccessfulTime());
        assertTrue(kube.paths.get(0).contains("labelSelector=app.kubernetes.io%2Fcomponent%3Dbackup%2C"),
                "the configured selector is sent, URL-encoded");
    }

    @Test
    void aTriggerIsAJobFromTheJobTemplateOwnedByTheCronJob() throws IOException {
        kube.cronJob("prod-sangam-pg-full", "u1", "pg-full");
        BackupTargets.Target t = targets.targets().get(0);
        String id = targets.runId(t, Instant.parse("2026-11-12T10:00:00Z"));
        assertEquals("prod-sangam-pg-full-m" + Long.toString(Instant.parse("2026-11-12T10:00:00Z").getEpochSecond(), 36), id);
        BackupTargets.Run run = targets.start(t, id).orElseThrow();
        assertEquals(BackupTargets.Trigger.MANUAL, run.trigger());
        assertEquals(BackupTargets.Status.PENDING, run.status());
        JsonNode job = kube.jobs.get(id);
        assertEquals("manual", job.path("metadata").path("annotations").path(KubernetesBackupTargets.INSTANTIATE).asText());
        assertEquals("dba", job.path("metadata").path("labels").path("team").asText(), "jobTemplate labels are kept");
        JsonNode owner = job.path("metadata").path("ownerReferences").get(0);
        assertEquals("CronJob", owner.path("kind").asText());
        assertEquals("u1", owner.path("uid").asText());
        assertTrue(owner.path("controller").asBoolean());
        assertTrue(owner.path("blockOwnerDeletion").isMissingNode(), "no blockOwnerDeletion: it would need more rights");
        assertEquals(1, job.path("spec").path("backoffLimit").asInt(), "the spec is the jobTemplate spec");
        assertEquals("pg-full", job.path("spec").path("template").path("metadata").path("labels")
                .path(KubernetesBackupTargets.NAME_LABEL).asText());
    }

    @Test
    void aSecondStartWithTheSameIdIsEmpty() throws IOException {
        kube.cronJob("prod-sangam-pg-full", "u1", "pg-full");
        BackupTargets.Target t = targets.targets().get(0);
        String id = targets.runId(t, Instant.parse("2026-11-12T10:00:00Z"));
        assertTrue(targets.start(t, id).isPresent());
        assertTrue(targets.start(t, id).isEmpty());
    }

    @Test
    void historyIsTheJobsControlledByTheTargetsWithTheirOutcome() throws IOException {
        kube.cronJob("prod-sangam-pg-full", "u1", "pg-full");
        kube.cronJob("prod-sangam-pg-logical", "u2", "pg-logical");
        kube.job("prod-sangam-pg-full-29000001", "u1", false, "Complete");
        kube.job("prod-sangam-pg-full-mabc", "u1", true, null);
        kube.job("prod-sangam-pg-logical-29000002", "u2", false, "Failed");
        kube.job("someone-elses-job", "u9", false, "Complete");
        List<BackupTargets.Run> all = targets.runs(null);
        assertEquals(3, all.size(), "jobs of other owners are not shown");
        Map<String, BackupTargets.Run> byId = new LinkedHashMap<>();
        all.forEach(r -> byId.put(r.id(), r));
        BackupTargets.Run done = byId.get("prod-sangam-pg-full-29000001");
        assertEquals(BackupTargets.Status.SUCCEEDED, done.status());
        assertEquals(BackupTargets.Trigger.SCHEDULED, done.trigger());
        assertEquals(Instant.parse("2026-11-12T01:39:00Z"), done.finishedAt());
        BackupTargets.Run running = byId.get("prod-sangam-pg-full-mabc");
        assertEquals(BackupTargets.Status.RUNNING, running.status());
        assertEquals(BackupTargets.Trigger.MANUAL, running.trigger());
        BackupTargets.Run failed = byId.get("prod-sangam-pg-logical-29000002");
        assertEquals(BackupTargets.Status.FAILED, failed.status());
        assertEquals(Instant.parse("2026-11-12T01:20:00Z"), failed.finishedAt());
        assertTrue(failed.message().startsWith("BackoffLimitExceeded"));
        assertEquals(1, targets.runs("prod-sangam-pg-logical").size());
    }

    @Test
    void aSelectorAndAValidNamespaceAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> new KubernetesBackupTargets(kube, NS, " "));
        assertThrows(IllegalArgumentException.class, () -> new KubernetesBackupTargets(kube, "../x", SELECTOR));
    }
}
