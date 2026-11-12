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

    /**
     * A raw deployment. The package deploy identity (name prefix {@code pkg:}, source {@code sangam-package}) is
     * reserved for {@link #deployPackage} and refused here with 422 MODEL_INVALID.
     */
    Deployment deploy(RequestContext ctx, DeployRequest request);

    /**
     * M-068: a package's own deploy, called only by the package lifecycle ({@link PackagePort}, after review, approval
     * and the signature check). The deployment is named {@code pkg:<packageKey>:<packageHash>} with source
     * {@code sangam-package}, and the engine is given the proof that it is this package's deploy, so it may bind the
     * package's API contract operations. This default (for adapters without the proof) answers as {@link #deploy},
     * which refuses the reserved identity.
     */
    default Deployment deployPackage(RequestContext ctx, String packageKey, String packageHash,
                                     java.util.Map<String, byte[]> resources) {
        return deploy(ctx, new DeployRequest(PACKAGE_NAME_PREFIX + packageKey + ":" + packageHash, PACKAGE_SOURCE, false,
                resources));
    }

    /** The reserved package deploy identity (the engine's {@code OperationRefPinning} enforces the same values). */
    String PACKAGE_NAME_PREFIX = "pkg:";
    String PACKAGE_SOURCE = "sangam-package";

    /** True when a raw deployment request claims the reserved package deploy identity. */
    static boolean claimsPackageIdentity(String name, String source) {
        return (name != null && name.startsWith(PACKAGE_NAME_PREFIX)) || PACKAGE_SOURCE.equals(source);
    }

    Page<Deployment> list(RequestContext ctx, String name, Page.PageRequest page);

    Deployment get(RequestContext ctx, String deploymentId);

    byte[] resource(RequestContext ctx, String deploymentId, String resourceName);

    void delete(RequestContext ctx, String deploymentId, boolean cascade, String reason);
}
