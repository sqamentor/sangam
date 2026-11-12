// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * M-013: the UI plugin asset route takes a path with slashes, and the contract says so. The server maps
 * {@code /{id}/assets/{*path}} (decoded, the rest of the path); the contract keeps {@code {path}} as the last template
 * segment and marks it {@code x-sangam-path-wildcard: true}. A change on either side fails here.
 */
class UiPluginControllerTest {

    private static final String BASE = "/api/v1/plugins/ui";
    private static final Path CONTRACT = Path.of("..", "sangam-rest", "api", "sangam-api.yaml");

    @Test
    void assetMappingCapturesEveryRemainingSegmentDecoded() {
        PathPattern pattern = PathPatternParser.defaultInstance.parse(BASE + UiPluginController.ASSET_MAPPING);

        PathPattern.PathMatchInfo nested = pattern.matchAndExtract(
                PathContainer.parsePath(BASE + "/demo/assets/chunks/app%20main.js"));
        assertNotNull(nested, "a nested asset path must match");
        assertEquals("demo", nested.getUriVariables().get("id"));
        assertEquals("/chunks/app main.js", nested.getUriVariables().get("path"));
        assertEquals("chunks/app main.js", UiPluginController.assetName(nested.getUriVariables().get("path")));

        PathPattern.PathMatchInfo flat = pattern.matchAndExtract(PathContainer.parsePath(BASE + "/demo/assets/index.js"));
        assertNotNull(flat);
        assertEquals("index.js", UiPluginController.assetName(flat.getUriVariables().get("path")));
    }

    @Test
    void assetNameStripsOnlyTheLeadingSlash() {
        assertEquals("a/b/c.css", UiPluginController.assetName("/a/b/c.css"));
        assertEquals("x.js", UiPluginController.assetName("x.js"));
        assertEquals("", UiPluginController.assetName("/"));
        assertEquals("", UiPluginController.assetName(null));
    }

    @Test
    void handlerIsMappedWithTheDeclaredPattern() throws NoSuchMethodException {
        assertEquals(BASE, UiPluginController.class.getAnnotation(RequestMapping.class).value()[0]);
        Method asset = UiPluginController.class.getMethod("asset", String.class, String.class);
        assertEquals(List.of(UiPluginController.ASSET_MAPPING), List.of(asset.getAnnotation(GetMapping.class).value()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void contractDeclaresTheSameMultiSegmentParameter() throws IOException {
        Map<String, Object> doc = read(CONTRACT);
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        String contractPath = BASE.substring("/api/v1".length())
                + UiPluginController.ASSET_MAPPING.replace("{*path}", "{path}");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(contractPath);
        assertNotNull(pathItem, "contract path " + contractPath);
        Map<String, Object> get = (Map<String, Object>) pathItem.get("get");
        assertEquals("getUiPluginAsset", get.get("operationId"));
        Map<String, Object> path = null;
        for (Object o : (List<Object>) get.get("parameters")) {
            Map<String, Object> p = (Map<String, Object>) o;
            if ("path".equals(p.get("in")) && "path".equals(p.get("name"))) {
                path = p;
            }
        }
        assertNotNull(path, "path parameter 'path'");
        assertEquals(Boolean.TRUE, path.get("x-sangam-path-wildcard"));

        // every multi-segment parameter in the contract is the last segment of its template
        List<String> misplaced = new ArrayList<>();
        paths.forEach((template, item) -> ((Map<String, Object>) item).forEach((method, op) -> {
            if (op instanceof Map<?, ?> m && m.get("parameters") instanceof List<?> params) {
                for (Object o : params) {
                    if (o instanceof Map<?, ?> p && Boolean.TRUE.equals(p.get("x-sangam-path-wildcard"))
                            && !template.endsWith("/{" + p.get("name") + "}")) {
                        misplaced.add(method + " " + template);
                    }
                }
            }
        }));
        assertTrue(misplaced.isEmpty(), "multi-segment path parameters not last: " + misplaced);
    }

    private static Map<String, Object> read(Path p) throws IOException {
        LoaderOptions o = new LoaderOptions();
        o.setCodePointLimit(64 * 1024 * 1024);
        try (InputStream in = Files.newInputStream(p)) {
            return new Yaml(new SafeConstructor(o)).load(in);
        }
    }
}
