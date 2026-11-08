// SPDX-License-Identifier: Apache-2.0
package org.sangam.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guard: Operaton's Spring Boot starter auto-deploys every BPMN/DMN/CMMN resource on the class path when the context
 * starts. A model under {@code src/test/resources} would be deployed into every Spring Boot test of this module (and
 * one invalid or rule-breaking model stops them all). Test models use a non-scanned suffix (for example
 * {@code .bpmn.tpl}, {@code .bpmn.model}) and each test deploys its own explicitly.
 */
class TestResourcesNotAutoDeployedTest {

    @Test
    void noAutoDeployableModelUnderTestResources() throws IOException {
        Path root = Path.of("src", "test", "resources");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> models = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("(?i).*\\.(bpmn|bpmn20\\.xml|dmn|dmn11\\.xml|cmmn|cmmn11\\.xml)$"))
                    .toList();
            assertTrue(models.isEmpty(), "auto-deployable models in test resources: " + models);
        }
    }
}
