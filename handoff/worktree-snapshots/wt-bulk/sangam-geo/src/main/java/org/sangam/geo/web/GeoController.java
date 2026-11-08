// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.web;

import jakarta.servlet.http.HttpServletRequest;
import org.sangam.geo.GeoException;
import org.sangam.geo.rest.GeoRestPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spring MVC adapter of {@link GeoRestPort} at {@code /api/v1/geo} (routes listed on the port). Errors are RFC 9457
 * {@code application/problem+json} with {@code code}. Registered as a bean by the integrator with a
 * {@link GeoCallerResolver}; endpoint authorization (VI.4 policy) is applied by sangam-security in front of it.
 */
@RestController
@RequestMapping("/api/v1/geo")
public class GeoController {

    private final GeoRestPort port;
    private final GeoCallerResolver callers;

    public GeoController(GeoRestPort port, GeoCallerResolver callers) {
        this.port = port;
        this.callers = callers;
    }

    @GetMapping("/layers")
    public List<Map<String, Object>> layers(HttpServletRequest r) {
        return port.listLayers(callers.resolve(r));
    }

    @PostMapping("/layers")
    public ResponseEntity<Map<String, Object>> defineLayer(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        Map<String, Object> l = port.defineLayer(callers.resolve(r), body);
        return ResponseEntity.created(URI.create("/api/v1/geo/layers/" + l.get("code"))).body(l);
    }

    @GetMapping("/layers/{layer}")
    public Map<String, Object> layer(HttpServletRequest r, @PathVariable("layer") String layer) {
        return port.getLayer(callers.resolve(r), layer);
    }

    @PostMapping("/layers/{layer}/import")
    public Map<String, Object> importBoundaries(HttpServletRequest r, @PathVariable("layer") String layer,
                                                @RequestBody Map<String, Object> body) {
        return port.importBoundaries(callers.resolve(r), layer, body);
    }

    @GetMapping("/layers/{layer}/features")
    public List<Map<String, Object>> features(HttpServletRequest r, @PathVariable("layer") String layer,
                                              @RequestParam(value = "level", required = false) String level,
                                              @RequestParam(value = "parentId", required = false) String parentId,
                                              @RequestParam(value = "history", defaultValue = "false") boolean history,
                                              @RequestParam(value = "page", defaultValue = "0") int page,
                                              @RequestParam(value = "size", defaultValue = "50") int size) {
        return port.listFeatures(callers.resolve(r), layer, level, parentId, history, page, size);
    }

    @PostMapping("/layers/{layer}/features")
    public ResponseEntity<Map<String, Object>> putFeature(HttpServletRequest r, @PathVariable("layer") String layer,
                                                          @RequestBody Map<String, Object> body) {
        Map<String, Object> f = port.putFeature(callers.resolve(r), layer, body);
        return ResponseEntity.created(URI.create("/api/v1/geo/features/" + f.get("id"))).body(f);
    }

    @GetMapping("/features/{id}")
    public Map<String, Object> feature(HttpServletRequest r, @PathVariable("id") String id,
                                       @RequestParam(value = "geometry", defaultValue = "true") boolean geometry) {
        return port.getFeature(callers.resolve(r), id, geometry);
    }

    @GetMapping("/features/{id}/lineage")
    public List<Map<String, Object>> lineage(HttpServletRequest r, @PathVariable("id") String id) {
        return port.lineage(callers.resolve(r), id);
    }

    @DeleteMapping("/features/{id}")
    public ResponseEntity<Void> retire(HttpServletRequest r, @PathVariable("id") String id) {
        port.retireFeature(callers.resolve(r), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resolve")
    public Map<String, Object> resolve(HttpServletRequest r, @RequestParam("layer") String layer,
                                       @RequestParam(value = "level", required = false) String level,
                                       @RequestParam("lat") double lat, @RequestParam("lon") double lon) {
        return port.resolve(callers.resolve(r), layer, level, lat, lon);
    }

    @GetMapping("/nearest")
    public List<Map<String, Object>> nearest(HttpServletRequest r, @RequestParam(value = "layer", required = false) String layer,
                                             @RequestParam(value = "objectType", required = false) String objectType,
                                             @RequestParam("lat") double lat, @RequestParam("lon") double lon,
                                             @RequestParam(value = "n", defaultValue = "5") int n,
                                             @RequestParam(value = "maxKm", defaultValue = "0") double maxKm,
                                             @RequestParam(value = "tags", required = false) String tags) {
        return port.nearest(callers.resolve(r), layer, objectType, lat, lon, n, maxKm, tags);
    }

    @PostMapping("/overlaps")
    public List<Map<String, Object>> overlaps(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.overlaps(callers.resolve(r), body);
    }

    @PostMapping("/fences")
    public ResponseEntity<Map<String, Object>> defineFence(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        Map<String, Object> f = port.defineFence(callers.resolve(r), body);
        return ResponseEntity.created(URI.create("/api/v1/geo/fences/" + f.get("id"))).body(f);
    }

    @GetMapping("/fences/{id}")
    public Map<String, Object> fence(HttpServletRequest r, @PathVariable("id") String id) {
        return port.getFence(callers.resolve(r), id);
    }

    @DeleteMapping("/fences/{id}")
    public ResponseEntity<Void> deactivateFence(HttpServletRequest r, @PathVariable("id") String id) {
        port.deactivateFence(callers.resolve(r), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/fences/{id}/check")
    public Map<String, Object> checkFence(HttpServletRequest r, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return port.checkFence(callers.resolve(r), id, body);
    }

    @PutMapping("/objects/{type}/{id}/geometry")
    public Map<String, Object> putObjectGeometry(HttpServletRequest r, @PathVariable("type") String type, @PathVariable("id") String id,
                                                 @RequestBody Map<String, Object> body) {
        return port.putObjectGeometry(callers.resolve(r), type, id, body);
    }

    @GetMapping("/objects/{type}/{id}/geometry")
    public Map<String, Object> objectGeometry(HttpServletRequest r, @PathVariable("type") String type, @PathVariable("id") String id,
                                              @RequestParam(value = "attribute", required = false) String attribute) {
        return port.getObjectGeometry(callers.resolve(r), type, id, attribute);
    }

    @GetMapping("/external-layers")
    public List<Map<String, Object>> externalLayers(HttpServletRequest r) {
        return port.listExternalLayers(callers.resolve(r));
    }

    @PostMapping("/external-layers")
    public Map<String, Object> registerExternal(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.registerExternalLayer(callers.resolve(r), body);
    }

    @PostMapping("/external-layers/{ref}/query")
    public Map<String, Object> queryExternal(HttpServletRequest r, @PathVariable("ref") String ref,
                                             @RequestBody(required = false) Map<String, Object> body) {
        return port.queryExternalLayer(callers.resolve(r), ref, body);
    }

    @PostMapping("/external-layers/{ref}/import")
    public Map<String, Object> importExternal(HttpServletRequest r, @PathVariable("ref") String ref,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return port.importExternalLayer(callers.resolve(r), ref, body);
    }

    @ExceptionHandler(GeoException.class)
    public ResponseEntity<ProblemDetail> problem(GeoException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        p.setType(URI.create("urn:sangam:problem:geo:" + e.code().toLowerCase(Locale.ROOT).replace('_', '-')));
        p.setTitle(e.code());
        p.setProperty("code", e.code());
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        return problem(new GeoException("INVALID_REQUEST", e.getMessage()));
    }
}
