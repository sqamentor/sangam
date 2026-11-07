// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

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

    @GetMapping("/{id}/assets/**")
    public ResponseEntity<byte[]> asset(@PathVariable String id, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String marker = "/" + id + "/assets/";
        int at = uri.indexOf(marker);
        String path = at < 0 ? "" : uri.substring(at + marker.length());
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
}
