// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Raw engine deployments ({@code /deployments}). Governed releases go through {@link PackagePort} (R43). */
public interface DeploymentPort {

    /** kind: bpmn | dmn | cmmn | form | other. */
    record DeployedDefinition(String id, String key, String kind, int version, String name) {
    }

    record Deployment(String id, String name, String source, String tenantId, Instant deploymentTime,
                      List<String> resources, List<DeployedDefinition> definitions) {
    }

    record DeployRequest(String name, String source, boolean duplicateFiltering, Map<String, byte[]> resources) {
    }

    Deployment deploy(RequestContext ctx, DeployRequest request);

    Page<Deployment> list(RequestContext ctx, String name, Page.PageRequest page);

    Deployment get(RequestContext ctx, String deploymentId);

    byte[] resource(RequestContext ctx, String deploymentId, String resourceName);

    void delete(RequestContext ctx, String deploymentId, boolean cascade, String reason);
}
