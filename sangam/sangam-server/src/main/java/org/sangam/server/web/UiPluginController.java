// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.List;
import java.util.Map;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.server.plugins.PluginManager;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/plugins/ui}: what the UI shell loads. The manifest list needs a signed-in user; assets are public, same-origin, because a browser module import cannot carry a token. Assets come from the verified bundle
 * in memory, looked up by exact name, so no request path reaches the file system. Enable and disable are the admin API's.
 */
@RestController
@RequestMapping("/api/v1/plugins/ui")
public class UiPluginController {

    private final PluginManager plugins;

    public UiPluginController(PluginManager plugins) {
        this.plugins = plugins;
    }

    @GetMapping
    public List<Map<String, Object>> list(RequestContext ctx) {
        return plugins.uiBundles().stream().map(PluginManager.UiBundle::manifest).toList();
    }

    /**
     * M-013: the asset route. {@code {*path}} captures the rest of the path, slashes included, decoded segment by segment
     * (the shell sends each segment percent-encoded, {@code host.tsx}). The variable is named like the contract's
     * multi-segment path parameter {@code path} ({@code x-sangam-path-wildcard}, sangam-api.yaml
     * {@code getUiPluginAsset}). Before, the handler cut the raw, still percent-encoded request URI, so an asset whose
     * name needs encoding was never found.
     */
    public static final String ASSET_MAPPING = "/{id}/assets/{*path}";

    @GetMapping(ASSET_MAPPING)
    public ResponseEntity<byte[]> asset(@PathVariable String id, @PathVariable("path") String captured) {
        String path = assetName(captured);
        PluginManager.UiBundle ui = plugins.uiBundle(id).orElseThrow(() -> ApiProblem.notFound("UI plugin", id));
        byte[] body = ui.assets().get(path);
        if (body == null) {
            throw ApiProblem.notFound("UI plugin asset", path);
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(PluginManager.contentType(path)))
                .cacheControl(CacheControl.noCache()).eTag(ui.version())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'")
                .body(body);
    }

    /** The bundle key of a captured {@code {*path}}: {@code "/chunks/a.js"} is {@code "chunks/a.js"}. */
    static String assetName(String captured) {
        if (captured == null) {
            return "";
        }
        return captured.startsWith("/") ? captured.substring(1) : captured;
    }
}
