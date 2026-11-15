// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.integration.lifecycle.LifecyclePorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Decisions 143 and 152 applied to data erasure: the erasure service never runs with the no-op document eraser
 * ({@link LifecyclePorts.DocumentEraser#NONE}) outside a validated development environment. With the no-op an erasure
 * (DPDP erasure on purpose completion, the instance erase route) scrubs the database and reports success while every
 * document of the instance stays in the document store. The server supplies the eraser of its own document store
 * ({@code ContentDocumentEraser}, with the ports module); a server without one is refused at start, by name.
 */
final class DocumentEraserRequirement {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentEraserRequirement.class);

    /** The name of the refusal. */
    static final String REFUSAL = "DOCUMENT_ERASER_REQUIRED";

    private DocumentEraserRequirement() {
    }

    /**
     * The eraser the erasure service uses: {@code configured} when there is one; without one the no-op, only in a
     * validated development environment and with a warning.
     *
     * @throws IllegalStateException {@value #REFUSAL} in production (a missing or contradictory environment setting
     *                               counts as production)
     */
    static LifecyclePorts.DocumentEraser require(LifecyclePorts.DocumentEraser configured, Environment env) {
        if (configured != null) {
            return configured;
        }
        if (!ProductionEnvironment.developmentDefaultsAllowed(env)) {
            throw new IllegalStateException(REFUSAL + ": data erasure needs a document eraser"
                    + " (LifecyclePorts.DocumentEraser) so that an erased instance's documents leave the document store;"
                    + " the server's own is ContentDocumentEraser (sangam.modules.ports.enabled=true). Without one an"
                    + " erasure would report success and keep every document (decisions 143, 152)");
        }
        LOG.warn("DEVELOPMENT: no document eraser is configured; data erasure leaves documents in the document store"
                + " (refused outside a development environment: {})", REFUSAL);
        return LifecyclePorts.DocumentEraser.NONE;
    }
}
