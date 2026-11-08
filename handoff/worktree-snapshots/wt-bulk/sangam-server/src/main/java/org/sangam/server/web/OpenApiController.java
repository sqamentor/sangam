// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the OpenAPI 3.1 contract (sangam-rest {@code api/sangam-api.yaml}) offline, for air-gapped installs (AC-11). */
@RestController
public class OpenApiController {

    static final String RESOURCE = "sangam-api.yaml";
    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    @GetMapping(value = "/api/v1/openapi.yaml")
    public ResponseEntity<byte[]> openApi() throws IOException {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = resource.getInputStream()) {
            return ResponseEntity.ok().contentType(YAML).cacheControl(CacheControl.noCache()).body(in.readAllBytes());
        }
    }
}
