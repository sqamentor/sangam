// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.app;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.repository.DeploymentBuilder;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.bpmn.SangamModelValidator;
import org.sangam.ports.cmmn.CmmnTaskPatterns;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.channel.InboundChannelManager;
import org.sangam.ports.forms.SangamFormService;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * App deployment (FLOWABLE-INTO-OPERATON FC-41, Flowable {@code AppRepositoryService.createDeployment}): deploys an {@link AppBundle} as one
 * unit, all or nothing, in <b>one engine transaction</b>:
 * <ol>
 *   <li>validator (FLOWABLE-INTO-OPERATON FC-46/R44) over every model; any error rejects the bundle;</li>
 *   <li>approval ({@link ApprovalPolicy}, R43: the approver signs the bundle hash);</li>
 *   <li>CMMN task patterns are expanded (FLOWABLE-INTO-OPERATON FC-11/FLOWABLE-INTO-OPERATON FC-58);</li>
 *   <li>one Operaton deployment with all BPMN/CMMN/DMN resources (name = app key, source = {@code sangam-app});</li>
 *   <li>forms, event and channel definitions are versioned with the app deployment id;</li>
 *   <li>an {@code APP_DEPLOYMENT} row records key, version, bundle hash and manifest.</li>
 * </ol>
 * The same bundle hash deployed again returns the existing app deployment (idempotent). After commit, inbound
 * channel consumers are refreshed.
 */
public class AppDeploymentService {

    /** R43 approval gate. Returns the approver id, or throws to refuse. */
    @FunctionalInterface
    public interface ApprovalPolicy {
        String approve(String tenantId, AppBundle bundle, String requestedBy);
    }

    public record AppDeployment(String id, String tenantId, String appKey, int version, String bundleSha256, String engineDeploymentId,
                                String state, String approvedBy, Instant createdAt, AppBundle.Manifest manifest) {
    }

    private final ProcessEngine engine;
    private final Dialect dialect;
    private final SangamModelValidator validator;
    private final ApprovalPolicy approval;
    private final SangamFormService forms;
    private final EventRegistry events;
    private final InboundChannelManager channelsOrNull;

    public AppDeploymentService(ProcessEngine engine, Dialect dialect, SangamModelValidator validator, ApprovalPolicy approval,
                                SangamFormService forms, EventRegistry events, InboundChannelManager channelsOrNull) {
        this.engine = engine;
        this.dialect = dialect;
        this.validator = validator;
        this.approval = approval;
        this.forms = forms;
        this.events = events;
        this.channelsOrNull = channelsOrNull;
    }

    public AppDeployment deploy(String tenant, AppBundle bundle, String requestedBy) {
        Optional<AppDeployment> same = byHash(tenant, bundle.manifest().key(), bundle.bundleSha256());
        if (same.isPresent()) {
            return same.get();
        }
        List<SangamModelValidator.Finding> errors = new ArrayList<>();
        for (var f : validator.validate(bundle.resources())) {
            if (f.severity() == SangamModelValidator.Severity.ERROR) {
                errors.add(f);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("app " + bundle.manifest().key() + " rejected: " + errors);
        }
        String approvedBy = approval.approve(tenant, bundle, requestedBy);
        Map<String, byte[]> engineResources = new LinkedHashMap<>();
        bundle.resources().forEach((n, b) -> {
            AppBundle.Kind k = AppBundle.kind(n);
            if (k == AppBundle.Kind.BPMN || k == AppBundle.Kind.CMMN || k == AppBundle.Kind.DMN) {
                engineResources.put(n, b);
            }
        });
        Map<String, byte[]> expanded = CmmnTaskPatterns.enrich(engineResources);
        AppDeployment result = EngineTx.execute(engine, cc -> {
            // Take the command's connection through EngineTx.connection() before any product write: it installs the
            // FW-23 rollback guard. Without it a failure after the forms/events/channels writes (which go through
            // plain command Transactions) left those rows committed while the engine deployment rolled back.
            EngineTx.connection();
            String id = UUID.randomUUID().toString();
            String engineDeploymentId = null;
            if (!expanded.isEmpty()) {
                DeploymentBuilder db = engine.getRepositoryService().createDeployment().name(bundle.manifest().key())
                        .source("sangam-app");
                if (tenant != null && !tenant.isEmpty()) {
                    db.tenantId(tenant);
                }
                expanded.forEach((n, b) -> db.addInputStream(n, new ByteArrayInputStream(b)));
                Deployment d = db.deploy();
                engineDeploymentId = d.getId();
            }
            for (Map.Entry<String, byte[]> r : bundle.resources().entrySet()) {
                String text = new String(r.getValue(), StandardCharsets.UTF_8);
                switch (AppBundle.kind(r.getKey())) {
                    case FORM -> forms.deploy(tenant, formKey(r.getKey(), text), text, id);
                    case EVENT -> events.deployEvent(tenantOrEmpty(tenant), text, id);
                    case CHANNEL -> events.deployChannel(tenantOrEmpty(tenant), text, id);
                    default -> {
                    }
                }
            }
            int version = Jdbc.one(EngineTx.connection(), "SELECT MAX(version) FROM APP_DEPLOYMENT WHERE tenant_id = ? AND app_key = ?",
                    rs -> rs.getInt(1), tenantOrEmpty(tenant), bundle.manifest().key()).orElse(0) + 1;
            Jdbc.update(EngineTx.connection(), "INSERT INTO APP_DEPLOYMENT(id, tenant_id, app_key, version, bundle_sha256, engine_deployment_id,"
                            + " manifest, state, approved_by, created_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',?," + dialect.now() + ")",
                    id, tenantOrEmpty(tenant), bundle.manifest().key(), version, bundle.bundleSha256(), engineDeploymentId,
                    Json.write(Map.of("manifest", bundle.manifest(), "resources", bundle.resourceHashes())), approvedBy);
            return new AppDeployment(id, tenant, bundle.manifest().key(), version, bundle.bundleSha256(), engineDeploymentId, "ACTIVE",
                    approvedBy, null, bundle.manifest());
        });
        events.invalidate();
        if (channelsOrNull != null) {
            channelsOrNull.refresh();
        }
        return result;
    }

    public List<AppDeployment> list(String tenant, String appKey) {
        return EngineTx.execute(engine, cc -> Jdbc.query(EngineTx.connection(), "SELECT id, tenant_id, app_key, version, bundle_sha256,"
                        + " engine_deployment_id, state, approved_by, created_at, manifest FROM APP_DEPLOYMENT WHERE tenant_id = ? AND app_key = ?"
                        + " ORDER BY version DESC", AppDeploymentService::map, tenantOrEmpty(tenant), appKey));
    }

    /** Retires an app version: marks it RETIRED and suspends its process definitions (running instances continue). */
    public void retire(String appDeploymentId) {
        EngineTx.execute(engine, cc -> {
            Optional<String> dep = Jdbc.one(EngineTx.connection(), "SELECT engine_deployment_id FROM APP_DEPLOYMENT WHERE id = ?",
                    rs -> rs.getString(1), appDeploymentId);
            dep.ifPresent(d -> engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(d).list()
                    .forEach(pd -> engine.getRepositoryService().updateProcessDefinitionSuspensionState().byProcessDefinitionId(pd.getId())
                            .includeProcessInstances(false).suspend()));
            Jdbc.update(EngineTx.connection(), "UPDATE APP_DEPLOYMENT SET state = 'RETIRED' WHERE id = ?", appDeploymentId);
            return null;
        });
    }

    private Optional<AppDeployment> byHash(String tenant, String key, String sha) {
        return EngineTx.execute(engine, cc -> Jdbc.query(EngineTx.connection(), "SELECT id, tenant_id, app_key, version, bundle_sha256,"
                + " engine_deployment_id, state, approved_by, created_at, manifest FROM APP_DEPLOYMENT WHERE tenant_id = ? AND app_key = ?"
                + " AND bundle_sha256 = ? AND state = 'ACTIVE'", AppDeploymentService::map, tenantOrEmpty(tenant), key, sha)
                .stream().findFirst());
    }

    private static AppDeployment map(java.sql.ResultSet rs) throws java.sql.SQLException {
        var m = Json.tree(rs.getString(10)).path("manifest");
        return new AppDeployment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), Jdbc.instant(rs, "created_at"), Json.MAPPER.convertValue(m, AppBundle.Manifest.class));
    }

    private static String formKey(String resourceName, String json) {
        var n = Json.tree(json);
        if (n.hasNonNull("key")) {
            return n.get("key").asText();
        }
        String base = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        return base.replaceAll("\\.form(\\.json)?$", "");
    }

    private static String tenantOrEmpty(String tenant) {
        return tenant == null ? "" : tenant;
    }
}
