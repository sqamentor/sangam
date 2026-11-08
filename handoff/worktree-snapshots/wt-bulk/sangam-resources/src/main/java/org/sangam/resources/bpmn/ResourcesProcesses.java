// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.repository.DeploymentBuilder;

import java.io.InputStream;
import java.util.List;

/**
 * The default generic processes shipped in this module's resources (classpath {@code bpmn/}). Deploy them once at start
 * (shared, no tenant) with {@link #deploy}; {@code enableDuplicateFiltering} makes the call idempotent across restarts and
 * nodes. A pack or tenant deployment under the same process key takes precedence for that tenant.
 */
public final class ResourcesProcesses {

    public static final String BOOKING_APPROVAL = "sangam-booking-approval";
    public static final String ROSTER_PUBLISH = "sangam-roster-publish";

    /** Classpath resources of the default models. */
    public static final List<String> RESOURCES = List.of(
            "bpmn/sangam-booking-approval.bpmn",
            "bpmn/sangam-roster-publish.bpmn");

    private ResourcesProcesses() {
    }

    public static Deployment deploy(RepositoryService repository) {
        DeploymentBuilder b = repository.createDeployment().name("sangam-resources-defaults").source("sangam-resources")
                .enableDuplicateFiltering(true);
        ClassLoader cl = ResourcesProcesses.class.getClassLoader();
        for (String r : RESOURCES) {
            InputStream in = cl.getResourceAsStream(r);
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + r);
            }
            b.addInputStream(r.substring(r.lastIndexOf('/') + 1), in);
        }
        return b.deploy();
    }
}
