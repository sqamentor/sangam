// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register L-7: the optional site font manifest ({@code fonts/site/fonts.json}, fonts.ts) is asked for at every page
 * load and answers 404 where no site fonts are installed. fonts.ts has the switch ({@code fonts.manifest} in
 * config.json, "" disables it), but the front's config.json did not carry the key, so no deployment could use it.
 */
class FrontFontManifestTest {

    private static final Path DEPLOY = Path.of("..", "deploy").toAbsolutePath().normalize();

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** L-7: the front's config.json carries fonts.manifest; the image default is today's path; the fragment turns it off. */
    @Test
    @SuppressWarnings("unchecked")
    void theSiteFontManifestCanBeTurnedOffAndTheFragmentDoesSo() throws IOException {
        String template = read(DEPLOY.resolve("presentation").resolve("sangam-path.conf.template"));
        assertTrue(template.contains("\"fonts\":{\"manifest\":\"${SANGAM_FONTS_MANIFEST}\"}"),
                "/sangam/config.json carries fonts.manifest from SANGAM_FONTS_MANIFEST");
        String dockerfile = read(DEPLOY.resolve("presentation").resolve("Dockerfile"));
        assertTrue(dockerfile.contains("SANGAM_FONTS_MANIFEST=/sangam/fonts/site/fonts.json"),
                "the image default is the path fonts.ts asks for without the key (no change unless a deployer sets it)");
        Map<String, Object> doc = new Yaml().load(read(DEPLOY.resolve("demo").resolve("sangam-compose-fragment.yml")));
        Map<String, Object> front = (Map<String, Object>) ((Map<String, Object>) doc.get("services")).get("sangam-front");
        Map<String, Object> env = (Map<String, Object>) front.get("environment");
        assertTrue(env.containsKey("SANGAM_FONTS_MANIFEST"), "the fragment names SANGAM_FONTS_MANIFEST");
        assertEquals("", String.valueOf(env.get("SANGAM_FONTS_MANIFEST")), "empty: no site fonts on this host, no request, no 404");
        assertTrue(read(DEPLOY.resolve("presentation").resolve("README.md")).contains("`SANGAM_FONTS_MANIFEST`"), "documented in the README");
        assertTrue(read(DEPLOY.resolve("presentation").resolve("presentation.env.example")).contains("SANGAM_FONTS_MANIFEST="),
                "listed in presentation.env.example");
    }
}