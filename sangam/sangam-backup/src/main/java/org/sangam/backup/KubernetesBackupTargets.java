// SPDX-License-Identifier: Apache-2.0
package org.sangam.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.kube.Kube;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Backup targets on Kubernetes (owner decision 155): the backup CronJobs of the Helm chart
 * ({@code templates/backup-cronjobs.yaml}), selected by label in one namespace.
 *
 * <ul>
 *   <li>Targets: the CronJobs matching the selector (the chart sets {@code app.kubernetes.io/component=backup} and
 *       {@code app.kubernetes.io/instance=<release>}). The display name is the pod template label
 *       {@code sangam.io/backup} (for example {@code pg-full}).</li>
 *   <li>Start: a Job from the CronJob's {@code jobTemplate}, as {@code kubectl create job --from=cronjob/...} makes it:
 *       the template's labels and annotations, the annotation {@code cronjob.kubernetes.io/instantiate: manual} and a
 *       controller owner reference to the CronJob (without {@code blockOwnerDeletion}, which would need more rights).
 *       The name is {@code <cronjob>-m<epoch seconds, base 36>}, at most 63 characters.</li>
 *   <li>History: the Jobs whose controller is one of the targets (matched by uid), scheduled and manual alike. The
 *       platform keeps them as the CronJob's history limits say; the audit chain keeps every manual trigger.</li>
 * </ul>
 *
 * <p>The server needs a namespaced Role with {@code get, list} on {@code cronjobs} and {@code get, list, create} on
 * {@code jobs}; the chart grants it only when the console is enabled (off by default).
 */
public final class KubernetesBackupTargets implements BackupTargets {

    static final String NAME_LABEL = "sangam.io/backup";
    static final String INSTANTIATE = "cronjob.kubernetes.io/instantiate";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final int MAX_JOB_NAME = 63;

    private final Kube kube;
    private final String namespace;
    private final String selector;

    public KubernetesBackupTargets(Kube kube, String namespace, String selector) {
        if (namespace == null || !DNS_LABEL.matcher(namespace).matches()) {
            throw new IllegalArgumentException("not a namespace name: " + namespace);
        }
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("a label selector is required, so the console sees only the backup CronJobs");
        }
        this.kube = kube;
        this.namespace = namespace;
        this.selector = selector.trim();
    }

    @Override
    public List<Target> targets() throws IOException {
        List<Target> out = new ArrayList<>();
        for (JsonNode cj : kube.list(cronJobs() + Kube.labelSelector(selector))) {
            out.add(target(cj));
        }
        out.sort(Comparator.comparing(Target::id));
        return out;
    }

    @Override
    public List<Run> runs(String targetId) throws IOException {
        Map<String, String> targetByUid = new HashMap<>();
        for (JsonNode cj : kube.list(cronJobs() + Kube.labelSelector(selector))) {
            String name = cj.path("metadata").path("name").asText();
            if (targetId == null || targetId.equals(name)) {
                targetByUid.put(cj.path("metadata").path("uid").asText(), name);
            }
        }
        List<Run> out = new ArrayList<>();
        if (targetByUid.isEmpty()) {
            return out;
        }
        for (JsonNode job : kube.list(jobs())) {
            String owner = controllerUid(job);
            if (owner != null && targetByUid.containsKey(owner)) {
                out.add(run(job, targetByUid.get(owner)));
            }
        }
        return out;
    }

    @Override
    public String runId(Target target, Instant now) {
        String id = target.id() + "-m" + Long.toString(now.getEpochSecond(), 36);
        if (id.length() > MAX_JOB_NAME) {
            throw new IllegalStateException("job name " + id + " is longer than " + MAX_JOB_NAME + " characters");
        }
        return id;
    }

    @Override
    public Optional<Run> start(Target target, String runId) throws IOException {
        JsonNode cj = kube.get(cronJobs() + "/" + target.id());
        if (cj == null) {
            throw new IOException("CronJob " + target.id() + " no longer exists");
        }
        JsonNode template = cj.path("spec").path("jobTemplate");
        ObjectNode job = JSON.createObjectNode();
        job.put("apiVersion", "batch/v1");
        job.put("kind", "Job");
        ObjectNode meta = job.putObject("metadata");
        meta.put("name", runId);
        meta.put("namespace", namespace);
        ObjectNode labels = meta.putObject("labels");
        template.path("metadata").path("labels").properties().forEach(e -> labels.set(e.getKey(), e.getValue()));
        ObjectNode annotations = meta.putObject("annotations");
        template.path("metadata").path("annotations").properties().forEach(e -> annotations.set(e.getKey(), e.getValue()));
        annotations.put(INSTANTIATE, "manual");
        ObjectNode owner = meta.putArray("ownerReferences").addObject();
        owner.put("apiVersion", "batch/v1");
        owner.put("kind", "CronJob");
        owner.put("name", cj.path("metadata").path("name").asText());
        owner.put("uid", cj.path("metadata").path("uid").asText());
        owner.put("controller", true);
        job.set("spec", template.path("spec").deepCopy());
        JsonNode created = kube.create(jobs(), job);
        if (created == null) {
            return Optional.empty();
        }
        return Optional.of(run(created, target.id()));
    }

    private String cronJobs() {
        return "/apis/batch/v1/namespaces/" + namespace + "/cronjobs";
    }

    private String jobs() {
        return "/apis/batch/v1/namespaces/" + namespace + "/jobs";
    }

    static Target target(JsonNode cj) {
        String id = cj.path("metadata").path("name").asText();
        JsonNode spec = cj.path("spec");
        String name = spec.path("jobTemplate").path("spec").path("template").path("metadata").path("labels")
                .path(NAME_LABEL).asText(id);
        JsonNode status = cj.path("status");
        return new Target(id, name, spec.path("schedule").asText(""), text(spec.path("timeZone")),
                spec.path("suspend").asBoolean(false), status.path("active").size(),
                instant(status.path("lastScheduleTime")), instant(status.path("lastSuccessfulTime")));
    }

    static Run run(JsonNode job, String targetId) {
        JsonNode status = job.path("status");
        Status s;
        Instant finished = instant(status.path("completionTime"));
        String message = null;
        JsonNode failed = condition(status, "Failed");
        if (condition(status, "Complete") != null) {
            s = Status.SUCCEEDED;
        } else if (failed != null) {
            s = Status.FAILED;
            if (finished == null) {
                finished = instant(failed.path("lastTransitionTime"));
            }
            String reason = text(failed.path("reason"));
            String detail = text(failed.path("message"));
            message = reason == null ? detail : detail == null ? reason : reason + ": " + detail;
        } else if (status.path("active").asInt(0) > 0) {
            s = Status.RUNNING;
        } else {
            s = Status.PENDING;
        }
        Trigger trigger = "manual".equals(job.path("metadata").path("annotations").path(INSTANTIATE).asText(null))
                ? Trigger.MANUAL : Trigger.SCHEDULED;
        return new Run(job.path("metadata").path("name").asText(), targetId, trigger, s,
                instant(status.path("startTime")), finished, null, message);
    }

    private static JsonNode condition(JsonNode status, String type) {
        for (JsonNode c : status.path("conditions")) {
            if (type.equals(c.path("type").asText()) && "True".equals(c.path("status").asText())) {
                return c;
            }
        }
        return null;
    }

    private static String controllerUid(JsonNode job) {
        for (JsonNode ref : job.path("metadata").path("ownerReferences")) {
            if ("CronJob".equals(ref.path("kind").asText()) && ref.path("controller").asBoolean(false)) {
                return ref.path("uid").asText(null);
            }
        }
        return null;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isEmpty() ? null : n.asText();
    }

    private static Instant instant(JsonNode n) {
        String s = text(n);
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
