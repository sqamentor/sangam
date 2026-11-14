// SPDX-License-Identifier: Apache-2.0
package org.sangam.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.kube.Kube;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Level-triggered reconciliation of every {@code SangamInstallation} and {@code SangamCell} in one namespace (M-090).
 *
 * <p>For each installation, in this order:
 * <ol>
 *   <li><b>Migration under the schema lock (R12).</b> When the desired image has not been migrated yet
 *       ({@code status.migratedImage}), one migrate-only Job is created for that image (its name is derived from the
 *       image, so a second create is a no-op). Liquibase's session lock serialises it against anything else that
 *       migrates; the operator also never starts it while another migration Job of the same installation is active.
 *       The serving Deployment is not touched until the Job has completed: the running N-1 pods keep serving
 *       (expand/contract changelogs, R32). A failed Job is not retried (backoffLimit 0, as the chart): the phase is
 *       {@code MigrationFailed} until an operator deletes the Job or reverts {@code spec.image}.</li>
 *   <li><b>Rollout.</b> The Deployment, Service, state claim and (with a cell) the cell ConfigMap are applied with the
 *       migrated image. Changing {@code spec.restartToken} rolls the pods (rolling restart, maxUnavailable 0).</li>
 *   <li><b>Status.</b> {@code Ready} when the Deployment has rolled out every replica at the current generation.</li>
 * </ol>
 * With {@code spec.migration.mode: none} the DBA applies the schema out of band and the operator only rolls out.
 * Every write is idempotent (deterministic Job names, server-side apply), so two operator replicas acting at once
 * cannot run two migrations for one image or produce different objects.
 */
public final class Reconciler {

    static final String CRD_BASE = "/apis/sangam.io/v1alpha1/namespaces/";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Kube kube;
    private final PrintStream log;

    public Reconciler(Kube kube, PrintStream log) {
        this.kube = kube;
        this.log = log;
    }

    /** One pass over a namespace. A failure on one resource is logged and recorded; the others still reconcile. */
    public void reconcileNamespace(String ns) throws IOException {
        List<JsonNode> installations = items(kube.list(CRD_BASE + ns + "/sangaminstallations"));
        List<JsonNode> cells = items(kube.list(CRD_BASE + ns + "/sangamcells"));
        for (JsonNode cr : installations) {
            try {
                reconcileInstallation(cr, cells);
            } catch (IOException | RuntimeException e) {
                String name = cr.path("metadata").path("name").asText("?");
                log.println("sangam-operator: " + ns + "/" + name + ": " + e.getMessage());
                try {
                    writeStatus(path(ns, "sangaminstallations", name), cr.path("status"),
                            status(cr.path("status"), "Error", e.getMessage(), cr.path("metadata").path("generation").asLong(0)));
                } catch (IOException statusFailed) {
                    log.println("sangam-operator: " + ns + "/" + name + ": status not written: " + statusFailed.getMessage());
                }
            }
        }
        List<String> names = installations.stream().map(i -> i.path("metadata").path("name").asText()).toList();
        for (JsonNode cell : cells) {
            String ref = cell.path("spec").path("installationRef").asText("");
            if (!names.contains(ref)) {
                cellStatus(cell, "Unbound", "no SangamInstallation named '" + ref + "' in this namespace", null);
            }
        }
    }

    /** Reconciles one installation; returns the status phase it wrote. */
    String reconcileInstallation(JsonNode cr, List<JsonNode> cells) throws IOException {
        JsonNode status = cr.path("status");
        String ns = cr.path("metadata").path("namespace").asText("");
        String crPath = path(ns, "sangaminstallations", cr.path("metadata").path("name").asText(""));
        long generation = cr.path("metadata").path("generation").asLong(0);
        InstallationSpec s;
        try {
            s = InstallationSpec.parse(cr);
        } catch (InstallationSpec.InvalidSpec e) {
            return writeStatus(crPath, status, status(status, "Invalid", e.getMessage(), generation));
        }
        if (s.paused()) {
            return writeStatus(crPath, status, status(status, "Paused", "spec.paused is true: nothing is changed", generation));
        }
        CellSpec cell;
        try {
            cell = cellFor(s, cells, status.path("cellId").asText(""));
        } catch (InstallationSpec.InvalidSpec e) {
            return writeStatus(crPath, status, status(status, "Invalid", e.getMessage(), generation));
        }

        String migrated = status.path("migratedImage").asText("");
        ObjectNode next = status(status, null, null, generation);
        if (s.migration().mode().equals("none")) {
            migrated = s.image();
        } else if (!s.image().equals(migrated)) {
            String jobName = Manifests.migrationJobName(s, s.image());
            String jobs = "/apis/batch/v1/namespaces/" + ns + "/jobs";
            JsonNode job = kube.get(jobs + "/" + jobName);
            next.put("migrationJob", jobName);
            if (job == null) {
                String busy = activeMigration(s, jobs, jobName);
                if (busy != null) {
                    return writeStatus(crPath, status, phase(next, "Migrating", "waiting for migration Job " + busy + " to finish"));
                }
                kube.create(jobs, Manifests.migrationJob(s, s.image()));
                return writeStatus(crPath, status, phase(next, "Migrating", "migration Job " + jobName + " started for " + s.image()));
            }
            if (condition(job, "Failed")) {
                return writeStatus(crPath, status, phase(next, "MigrationFailed", "migration Job " + jobName
                        + " failed; the server keeps running " + (migrated.isEmpty() ? "nothing" : migrated)
                        + ". Read the Job's log, then delete the Job to retry or set spec.image back"));
            }
            if (!condition(job, "Complete") && job.path("status").path("succeeded").asInt(0) < 1) {
                return writeStatus(crPath, status, phase(next, "Migrating", "migration Job " + jobName + " is running"));
            }
            migrated = s.image();
            log.println("sangam-operator: " + ns + "/" + s.name() + ": schema migrated for " + migrated);
        }
        next.put("migratedImage", migrated);

        if (cell != null) {
            kube.apply(cmPath(ns, Manifests.cellConfigName(s)), Manifests.cellConfig(s, cell));
        }
        if (s.state().claimName().isEmpty()) {
            kube.create("/api/v1/namespaces/" + ns + "/persistentvolumeclaims", Manifests.stateClaimObject(s));
        }
        String deployPath = "/apis/apps/v1/namespaces/" + ns + "/deployments/" + Manifests.serverName(s);
        JsonNode d = kube.apply(deployPath, Manifests.deployment(s, migrated, cell));
        kube.apply("/api/v1/namespaces/" + ns + "/services/" + Manifests.serverName(s), Manifests.service(s));
        if (cell != null) {
            next.put("cellId", cell.cellId());
            String cellName = cell.name();
            cellStatus(cells.stream().filter(c -> c.path("metadata").path("name").asText().equals(cellName)).findFirst()
                    .orElseThrow(), "Bound", "bound to installation " + s.name(), cell.cellId());
        }
        // status.cellId is kept after a cell is removed: the installation can never become a cell with another id.
        next.put("serverImage", migrated);
        JsonNode ds = d == null ? null : d.path("status");
        int ready = ds == null ? 0 : ds.path("readyReplicas").asInt(0);
        next.put("readyReplicas", ready);
        boolean rolledOut = ds != null && d.path("metadata").path("generation").asLong(-1) <= ds.path("observedGeneration").asLong(-2)
                && ds.path("updatedReplicas").asInt(0) >= s.replicas() && ds.path("availableReplicas").asInt(0) >= s.replicas()
                && ds.path("replicas").asInt(0) == s.replicas();
        return writeStatus(crPath, status, rolledOut ? phase(next, "Ready", s.replicas() + " replica(s) of " + migrated + " ready")
                : phase(next, "RollingOut", ready + " of " + s.replicas() + " replica(s) ready on " + migrated));
    }

    /**
     * The one cell bound to {@code s}, or {@code null}. A changed cell id is refused (ids are immutable, never reused),
     * whether it changed on the SangamCell or the installation was bound to another id before ({@code installationCellId}).
     */
    private CellSpec cellFor(InstallationSpec s, List<JsonNode> cells, String installationCellId) throws IOException {
        List<JsonNode> mine = new ArrayList<>();
        for (JsonNode c : cells) {
            if (c.path("spec").path("installationRef").asText("").equals(s.name())) {
                mine.add(c);
            }
        }
        if (mine.isEmpty()) {
            return null;
        }
        if (mine.size() > 1) {
            for (JsonNode c : mine) {
                cellStatus(c, "Invalid", "more than one SangamCell names installation " + s.name(), null);
            }
            throw new InstallationSpec.InvalidSpec("more than one SangamCell names this installation");
        }
        JsonNode c = mine.get(0);
        CellSpec cell;
        try {
            cell = CellSpec.parse(c);
        } catch (InstallationSpec.InvalidSpec e) {
            cellStatus(c, "Invalid", e.getMessage(), null);
            throw new InstallationSpec.InvalidSpec("SangamCell " + c.path("metadata").path("name").asText() + ": " + e.getMessage());
        }
        String bound = c.path("status").path("cellId").asText("");
        if (bound.isEmpty()) {
            bound = installationCellId;
        }
        if (!bound.isEmpty() && !bound.equals(cell.cellId())) {
            String why = "spec.cellId changed from " + bound + " to " + cell.cellId() + ": cell ids are immutable and never reused";
            cellStatus(c, "Invalid", why, bound);
            throw new InstallationSpec.InvalidSpec("SangamCell " + cell.name() + ": " + why);
        }
        return cell;
    }

    /** The name of another active migration Job of this installation, or {@code null}. */
    private String activeMigration(InstallationSpec s, String jobs, String except) throws IOException {
        String selector = URLEncoder.encode(Manifests.INSTALLATION_LABEL + "=" + s.name() + ",app.kubernetes.io/component=migration",
                StandardCharsets.UTF_8);
        for (JsonNode j : items(kube.list(jobs + "?labelSelector=" + selector))) {
            String n = j.path("metadata").path("name").asText();
            if (!n.equals(except) && j.path("status").path("active").asInt(0) > 0) {
                return n;
            }
        }
        return null;
    }

    private void cellStatus(JsonNode cell, String phase, String message, String cellId) throws IOException {
        JsonNode cur = cell.path("status");
        ObjectNode next = status(cur, phase, message, cell.path("metadata").path("generation").asLong(0));
        if (cellId != null) {
            next.put("cellId", cellId);
        }
        writeStatus(path(cell.path("metadata").path("namespace").asText(""), "sangamcells",
                cell.path("metadata").path("name").asText("")), cur, next);
    }

    // ---- helpers -------------------------------------------------------------------------------

    static boolean condition(JsonNode obj, String type) {
        for (JsonNode c : obj.path("status").path("conditions")) {
            if (type.equals(c.path("type").asText()) && "True".equals(c.path("status").asText())) {
                return true;
            }
        }
        return false;
    }

    /** A copy of the current status with phase, message and observed generation replaced. */
    private static ObjectNode status(JsonNode current, String phase, String message, long generation) {
        ObjectNode n = current != null && current.isObject() ? ((ObjectNode) current).deepCopy() : JSON.createObjectNode();
        n.put("observedGeneration", generation);
        if (phase != null) {
            phase(n, phase, message);
        }
        return n;
    }

    private static ObjectNode phase(ObjectNode n, String phase, String message) {
        n.put("phase", phase);
        n.put("message", message == null ? "" : message);
        return n;
    }

    /** Writes the status only when it changed, so an idle pass makes no API writes. */
    private String writeStatus(String crPath, JsonNode current, ObjectNode next) throws IOException {
        if (!next.equals(current)) {
            kube.patchStatus(crPath, next);
        }
        return next.path("phase").asText();
    }

    private static List<JsonNode> items(JsonNode list) {
        List<JsonNode> out = new ArrayList<>();
        if (list != null) {
            list.forEach(out::add);
        }
        return out;
    }

    static String path(String ns, String plural, String name) {
        return CRD_BASE + ns + "/" + plural + "/" + name;
    }

    private static String cmPath(String ns, String name) {
        return "/api/v1/namespaces/" + ns + "/configmaps/" + name;
    }
}
