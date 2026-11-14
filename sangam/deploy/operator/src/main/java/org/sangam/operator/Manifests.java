// SPDX-License-Identifier: Apache-2.0
package org.sangam.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * The Kubernetes objects of one installation, shaped like the Helm chart's (deploy/helm/sangam/templates:
 * liquibase-job.yaml, server-deployment.yaml, server-service.yaml): the same image runs the migrate-only Job (R12) and
 * the serving pods, which only validate the schema; rolling updates never lower the ready pods (R32, maxUnavailable 0).
 * Only the settings the operator needs are set here; everything else reaches the server through {@code spec.env},
 * {@code spec.envFrom} and {@code spec.configMapName}, as with the chart's {@code server.env}/{@code server.envFrom}.
 */
public final class Manifests {

    public static final String API_VERSION = "sangam.io/v1alpha1";
    public static final String INSTALLATION_LABEL = "sangam.io/installation";
    static final String RESTART_ANNOTATION = "sangam.io/restart-token";
    static final String CELL_HASH_ANNOTATION = "sangam.io/cell-config";
    static final int HTTP_PORT = 8080;
    static final int MANAGEMENT_PORT = 8081;
    static final int RUN_AS = 10001;
    private static final ObjectMapper JSON = new ObjectMapper();

    private Manifests() {
    }

    // ---- names ---------------------------------------------------------------------------------

    static String serverName(InstallationSpec s) {
        return s.name() + "-server";
    }

    static String cellConfigName(InstallationSpec s) {
        return s.name() + "-cell";
    }

    static String stateClaim(InstallationSpec s) {
        return s.state().claimName().isEmpty() ? s.name() + "-state" : s.state().claimName();
    }

    /** One migration Job per image: the name is derived from the image, so creating it again is a no-op (409). */
    static String migrationJobName(InstallationSpec s, String image) {
        return s.name() + "-migrate-" + sha256(image).substring(0, 10);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- objects -------------------------------------------------------------------------------

    /** The migrate-only Job (Liquibase session lock DATABASECHANGELOGLOCK + Operaton schema update), not retried. */
    static ObjectNode migrationJob(InstallationSpec s, String image) {
        ObjectNode job = object("batch/v1", "Job", s, migrationJobName(s, image), "migration");
        ObjectNode spec = job.putObject("spec");
        spec.put("backoffLimit", s.migration().backoffLimit());
        spec.put("activeDeadlineSeconds", s.migration().activeDeadlineSeconds());
        spec.put("ttlSecondsAfterFinished", s.migration().ttlSecondsAfterFinished());
        ObjectNode template = spec.putObject("template");
        template.putObject("metadata").set("labels", podLabels(s, "migration"));
        ObjectNode pod = podSpec(s, image, true, null);
        pod.put("restartPolicy", "Never");
        template.set("spec", pod);
        return job;
    }

    /** The serving Deployment: image {@code image} (already migrated), validate-only schema mode. */
    static ObjectNode deployment(InstallationSpec s, String image, CellSpec cell) {
        ObjectNode d = object("apps/v1", "Deployment", s, serverName(s), "server");
        ObjectNode spec = d.putObject("spec");
        spec.put("replicas", s.replicas());
        spec.put("revisionHistoryLimit", 5);
        ObjectNode strategy = spec.putObject("strategy").put("type", "RollingUpdate");
        strategy.putObject("rollingUpdate").put("maxUnavailable", 0).put("maxSurge", 1);
        spec.putObject("selector").set("matchLabels", selector(s, "server"));
        ObjectNode template = spec.putObject("template");
        ObjectNode meta = template.putObject("metadata");
        meta.set("labels", podLabels(s, "server"));
        ObjectNode annotations = meta.putObject("annotations");
        if (!s.restartToken().isBlank()) {
            annotations.put(RESTART_ANNOTATION, s.restartToken());
        }
        if (cell != null) {
            // A changed cell setting rolls the pods, like the chart's checksum/config annotation.
            annotations.put(CELL_HASH_ANNOTATION, sha256(cell.environment().toString()).substring(0, 16));
        }
        template.set("spec", podSpec(s, image, false, cell));
        return d;
    }

    static ObjectNode service(InstallationSpec s) {
        ObjectNode svc = object("v1", "Service", s, serverName(s), "server");
        ObjectNode spec = svc.putObject("spec").put("type", "ClusterIP");
        spec.set("selector", selector(s, "server"));
        ArrayNode ports = spec.putArray("ports");
        ports.addObject().put("name", "http").put("port", HTTP_PORT).put("targetPort", "http").put("protocol", "TCP");
        ports.addObject().put("name", "management").put("port", MANAGEMENT_PORT).put("targetPort", "management")
                .put("protocol", "TCP");
        return svc;
    }

    /** The cell's environment (owned by the installation, so the Deployment's reference never dangles). */
    static ObjectNode cellConfig(InstallationSpec s, CellSpec cell) {
        ObjectNode cm = object("v1", "ConfigMap", s, cellConfigName(s), "cell");
        ObjectNode data = cm.putObject("data");
        for (Map.Entry<String, String> e : cell.environment().entrySet()) {
            data.put(e.getKey(), e.getValue());
        }
        return cm;
    }

    /** The state claim the operator creates when none is named. It has no owner, so deleting the resource keeps the data. */
    static ObjectNode stateClaimObject(InstallationSpec s) {
        ObjectNode pvc = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "PersistentVolumeClaim");
        ObjectNode meta = pvc.putObject("metadata").put("name", stateClaim(s)).put("namespace", s.namespace());
        meta.set("labels", labels(s, "server"));
        ObjectNode spec = pvc.putObject("spec");
        spec.set("accessModes", s.state().accessModes().deepCopy());
        if (!s.state().storageClassName().isBlank()) {
            spec.put("storageClassName", s.state().storageClassName());
        }
        spec.putObject("resources").putObject("requests").put("storage", s.state().size());
        return pvc;
    }

    // ---- pod -----------------------------------------------------------------------------------

    private static ObjectNode podSpec(InstallationSpec s, String image, boolean migration, CellSpec cell) {
        ObjectNode pod = JSON.createObjectNode();
        if (!s.passthrough().imagePullSecrets().isEmpty()) {
            pod.set("imagePullSecrets", s.passthrough().imagePullSecrets().deepCopy());
        }
        if (!s.serviceAccountName().isEmpty()) {
            pod.put("serviceAccountName", s.serviceAccountName());
        }
        pod.put("automountServiceAccountToken", false);
        ObjectNode psc = pod.putObject("securityContext").put("runAsNonRoot", true).put("runAsUser", RUN_AS)
                .put("runAsGroup", RUN_AS).put("fsGroup", RUN_AS);
        psc.putObject("seccompProfile").put("type", "RuntimeDefault");
        if (!migration) {
            pod.put("terminationGracePeriodSeconds", 90);
        }
        ObjectNode c = pod.putArray("containers").addObject();
        c.put("name", migration ? "migrate" : "server");
        c.put("image", image);
        c.put("imagePullPolicy", s.imagePullPolicy());
        ObjectNode sc = c.putObject("securityContext").put("allowPrivilegeEscalation", false).put("readOnlyRootFilesystem", true);
        sc.putObject("capabilities").putArray("drop").add("ALL");
        c.set("env", env(s, migration, cell));
        ArrayNode envFrom = s.passthrough().envFrom().deepCopy();
        if (cell != null && !migration) {
            envFrom.addObject().putObject("configMapRef").put("name", cellConfigName(s));
        }
        if (!envFrom.isEmpty()) {
            c.set("envFrom", envFrom);
        }
        c.set("resources", (migration ? s.passthrough().migrationResources() : s.passthrough().resources()).deepCopy());
        if (!migration) {
            ArrayNode ports = c.putArray("ports");
            ports.addObject().put("name", "http").put("containerPort", HTTP_PORT).put("protocol", "TCP");
            ports.addObject().put("name", "management").put("containerPort", MANAGEMENT_PORT).put("protocol", "TCP");
            c.set("startupProbe", probe("/actuator/health/liveness", 5, 72));
            c.set("livenessProbe", probe("/actuator/health/liveness", 10, 6));
            c.set("readinessProbe", probe("/actuator/health/readiness", 5, 3));
            c.putObject("lifecycle").putObject("preStop").putObject("sleep").put("seconds", 10);
        }
        ArrayNode mounts = c.putArray("volumeMounts");
        ArrayNode volumes = pod.putArray("volumes");
        mounts.addObject().put("name", "tmp").put("mountPath", "/tmp");
        volumes.addObject().put("name", "tmp").putObject("emptyDir").put("sizeLimit", migration ? "512Mi" : "1Gi");
        mounts.addObject().put("name", "state").put("mountPath", "/var/lib/sangam");
        if (migration) {
            // one-shot run: no compliance scheduler, so a scratch directory is enough (as the chart's Job)
            volumes.addObject().put("name", "state").putObject("emptyDir").put("sizeLimit", "64Mi");
        } else {
            volumes.addObject().put("name", "state").putObject("persistentVolumeClaim").put("claimName", stateClaim(s));
        }
        mounts.addObject().put("name", "drivers").put("mountPath", "/opt/sangam/drivers").put("readOnly", true);
        ObjectNode drivers = volumes.addObject().put("name", "drivers");
        if (s.driversClaimName().isEmpty()) {
            drivers.putObject("emptyDir").put("sizeLimit", "256Mi");
        } else {
            drivers.putObject("persistentVolumeClaim").put("claimName", s.driversClaimName()).put("readOnly", true);
        }
        if (!s.configMapName().isEmpty()) {
            mounts.addObject().put("name", "config").put("mountPath", "/opt/sangam/config").put("readOnly", true);
            volumes.addObject().put("name", "config").putObject("configMap").put("name", s.configMapName());
        }
        if (!s.keysSecret().isEmpty()) {
            mounts.addObject().put("name", "keys").put("mountPath", "/opt/sangam/secrets").put("readOnly", true);
            volumes.addObject().put("name", "keys").putObject("secret").put("secretName", s.keysSecret())
                    .put("defaultMode", 0440);
        }
        return pod;
    }

    private static ArrayNode env(InstallationSpec s, boolean migration, CellSpec cell) {
        ArrayNode env = JSON.createArrayNode();
        env.addObject().put("name", "SANGAM_DB_URL").put("value", s.database().jdbcUrl());
        secretEnv(env, "SANGAM_DB_USER", s.database().existingSecret(), s.database().usernameKey());
        secretEnv(env, "SANGAM_DB_PASSWORD", s.database().existingSecret(), s.database().passwordKey());
        if (cell != null) {
            secretEnv(env, "SANGAM_CONTROL_PLANE_TOKEN", cell.existingSecret(), "controlPlaneToken");
            secretEnv(env, "SANGAM_ROLLUP_TOKEN", cell.existingSecret(), "rollupToken");
            secretEnv(env, "SANGAM_SERVICE_TOKEN", cell.existingSecret(), "serviceToken");
        }
        env.addObject().put("name", "SPRING_PROFILES_ACTIVE").put("value", s.springProfilesActive());
        env.addObject().put("name", "JAVA_TOOL_OPTIONS").put("value", s.javaToolOptions() + " -Djava.io.tmpdir=/tmp");
        String outbox = outboxDestinations(s, cell);
        if (!outbox.isEmpty()) {
            env.addObject().put("name", "SANGAM_OUTBOX_DESTINATIONS").put("value", outbox);
        }
        if (migration) {
            env.addObject().put("name", "SANGAM_LIQUIBASE_MODE").put("value", "migrate-only");
            env.addObject().put("name", "SANGAM_JOB_EXECUTOR_ENABLED").put("value", "false");
            env.addObject().put("name", "SANGAM_COMPLIANCE_SCHEDULE").put("value", "false");
            env.addObject().put("name", "TZ").put("value", "UTC");
        } else {
            // job and none: serving pods only validate; schema changes come from the Job or the DBA (R12)
            env.addObject().put("name", "SANGAM_LIQUIBASE_MODE").put("value", "validate");
            ObjectNode id = env.addObject().put("name", "SANGAM_INSTANCE_ID");
            id.putObject("valueFrom").putObject("fieldRef").put("fieldPath", "metadata.name");
            env.addObject().put("name", "SPRING_CONFIG_ADDITIONAL_LOCATION").put("value", "optional:file:/opt/sangam/config/");
        }
        for (JsonNode e : s.passthrough().env()) {
            env.add(e.deepCopy());
        }
        return env;
    }

    /** As the chart: a cell always publishes to the national roll-up ({@code rollup:national}). */
    static String outboxDestinations(InstallationSpec s, CellSpec cell) {
        String d = s.outboxDestinations().trim();
        if (cell != null && !(("," + d + ",").contains(",rollup:national,"))) {
            d = d.isEmpty() ? "rollup:national" : d + ",rollup:national";
        }
        return d;
    }

    private static void secretEnv(ArrayNode env, String name, String secret, String key) {
        ObjectNode e = env.addObject().put("name", name);
        e.putObject("valueFrom").putObject("secretKeyRef").put("name", secret).put("key", key);
    }

    private static ObjectNode probe(String path, int period, int failures) {
        ObjectNode p = JSON.createObjectNode();
        p.putObject("httpGet").put("path", path).put("port", "management");
        p.put("periodSeconds", period).put("failureThreshold", failures).put("timeoutSeconds", 3);
        return p;
    }

    // ---- metadata ------------------------------------------------------------------------------

    private static ObjectNode object(String apiVersion, String kind, InstallationSpec s, String name, String component) {
        ObjectNode o = JSON.createObjectNode().put("apiVersion", apiVersion).put("kind", kind);
        ObjectNode meta = o.putObject("metadata").put("name", name).put("namespace", s.namespace());
        meta.set("labels", labels(s, component));
        meta.putArray("ownerReferences").add(ownerReference(s));
        return o;
    }

    static ObjectNode ownerReference(InstallationSpec s) {
        return JSON.createObjectNode().put("apiVersion", API_VERSION).put("kind", "SangamInstallation").put("name", s.name())
                .put("uid", s.uid()).put("controller", true).put("blockOwnerDeletion", true);
    }

    static ObjectNode selector(InstallationSpec s, String component) {
        return JSON.createObjectNode().put("app.kubernetes.io/name", "sangam").put("app.kubernetes.io/instance", s.name())
                .put("app.kubernetes.io/component", component);
    }

    private static ObjectNode labels(InstallationSpec s, String component) {
        ObjectNode l = selector(s, component);
        l.put("app.kubernetes.io/managed-by", "sangam-operator");
        l.put(INSTALLATION_LABEL, s.name());
        return l;
    }

    private static ObjectNode podLabels(InstallationSpec s, String component) {
        ObjectNode l = labels(s, component);
        l.put("sangam.io/db-client", "true");   // the chart's NetworkPolicy lets these pods reach the database
        return l;
    }
}
