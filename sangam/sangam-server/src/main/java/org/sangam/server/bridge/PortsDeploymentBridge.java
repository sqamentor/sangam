// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.sangam.ports.app.AppBundle;
import org.sangam.ports.app.AppDeploymentService;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;

/**
 * {@link DeploymentPort} with app bundles (FC-41): a deployment that carries a {@code sangam-app.json} / Flowable
 * {@code .app} manifest, or event-registry models ({@code .event}, {@code .channel}), is deployed as one app bundle
 * by {@link AppDeploymentService} (validator FC-46, event registry, channels, Sangam forms, then the BPMN/CMMN/DMN
 * engine deployment). Plain BPMN/DMN/CMMN/form-js deployments, and every read, use the default Operaton adapter.
 * Because the R43 package lifecycle ({@code /packages}) deploys through this port, approved packages that contain
 * event or channel models are deployed as app bundles too.
 */
public final class PortsDeploymentBridge implements DeploymentPort {

    private final DeploymentPort base;
    private final AppDeploymentService apps;
    private final AuditPort audit;

    public PortsDeploymentBridge(DeploymentPort base, AppDeploymentService apps, AuditPort audit) {
        this.base = base;
        this.apps = apps;
        this.audit = audit;
    }

    @Override
    public Deployment deploy(RequestContext ctx, DeployRequest request) {
        if (request == null || request.resources() == null || !isAppBundle(request.resources())) {
            return base.deploy(ctx, request);
        }
        Map<String, byte[]> files = new LinkedHashMap<>(request.resources());
        if (files.keySet().stream().noneMatch(PortsDeploymentBridge::isManifest)) {
            String key = (request.name() == null ? "app" : request.name()).replaceAll("[^A-Za-z0-9_.-]", "_");
            if (key.length() > 128) {
                key = key.substring(0, 128);
            }
            files.put("sangam-app.json", ("{\"key\":\"" + key + "\",\"name\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        AppBundle bundle = PortsErrors.call(() -> AppBundle.of(files));
        AppDeploymentService.AppDeployment d = PortsErrors.call(() -> apps.deploy(ctx.tenantId(), bundle, ctx.principalId()));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("app_deployment_id", d.id());
        p.put("app_key", d.appKey());
        p.put("version", d.version());
        p.put("bundle_sha256", d.bundleSha256());
        p.put("engine_deployment_id", d.engineDeploymentId());
        audit.record(ctx, "sec:" + ctx.tenantId(), "APP_DEPLOYED", p);
        if (d.engineDeploymentId() != null) {
            return base.get(ctx, d.engineDeploymentId());
        }
        return new Deployment(d.id(), d.appKey(), "sangam-app", d.tenantId(), d.createdAt(),
                List.copyOf(bundle.resources().keySet()), List.of());
    }

    @Override
    public Page<Deployment> list(RequestContext ctx, String name, Page.PageRequest page) {
        return base.list(ctx, name, page);
    }

    @Override
    public Deployment get(RequestContext ctx, String deploymentId) {
        return base.get(ctx, deploymentId);
    }

    @Override
    public byte[] resource(RequestContext ctx, String deploymentId, String resourceName) {
        return base.resource(ctx, deploymentId, resourceName);
    }

    @Override
    public void delete(RequestContext ctx, String deploymentId, boolean cascade, String reason) {
        base.delete(ctx, deploymentId, cascade, reason);
    }

    static boolean isAppBundle(Map<String, byte[]> resources) {
        for (String n : resources.keySet()) {
            String l = n.toLowerCase(Locale.ROOT);
            if (isManifest(n) || l.endsWith(".event") || l.endsWith(".channel") || l.endsWith(".event.json")
                    || l.endsWith(".channel.json")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isManifest(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        return l.equals("sangam-app.json") || l.endsWith(".app");
    }
}
