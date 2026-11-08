// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.rest;

import java.util.List;
import java.util.Map;

/**
 * Framework-neutral REST port of the geo module, served at {@code /api/v1/geo} by {@code web.GeoController} (or by a
 * sangam-rest controller the integrator writes over this port). JSON in/out; geometry accepted as GeoJSON object,
 * {@code {"wkt": "..."}} or {@code {"lat":..,"lon":..}}; returned as GeoJSON.
 *
 * <pre>
 * GET    /layers                                  listLayers
 * POST   /layers                                  defineLayer      {code, kind, names, levels[{code,names}], orgUnitLinked, indexPrecision}
 * GET    /layers/{layer}                          getLayer
 * POST   /layers/{layer}/import                   importBoundaries (boundary package, see BoundaryDataLoader)
 * GET    /layers/{layer}/features                 listFeatures     ?level&amp;parentId&amp;history&amp;page&amp;size
 * POST   /layers/{layer}/features                 putFeature       {level, code, parentCode, names, properties, geometry, orgUnitRef}
 * GET    /features/{id}                           getFeature       ?geometry=true
 * GET    /features/{id}/lineage                   lineage
 * DELETE /features/{id}                           retireFeature
 * GET    /resolve                                 resolve          ?layer&amp;lat&amp;lon[&amp;level]   (jurisdictionAt + path)
 * GET    /nearest                                 nearest          ?layer|objectType&amp;lat&amp;lon&amp;n&amp;maxKm&amp;tags
 * POST   /overlaps                                overlaps         {layer, geometry}
 * POST   /fences                                  defineFence      {id, kind, geometry|center+radiusM|featureRef, toleranceM, maxAccuracyM}
 * GET    /fences/{id}                             getFence
 * DELETE /fences/{id}                             deactivateFence
 * POST   /fences/{id}/check                       checkFence       {lat, lon, accuracyM}
 * PUT    /objects/{type}/{id}/geometry            putObjectGeometry {attribute, geometry, tags}
 * GET    /objects/{type}/{id}/geometry            getObjectGeometry ?attribute
 * GET    /external-layers                         listExternalLayers
 * POST   /external-layers                         registerExternalLayer {ref, protocol, connectorId, baseUrl, remoteName, ...}
 * POST   /external-layers/{ref}/query             queryExternalLayer {operation, ...connector input}
 * POST   /external-layers/{ref}/import            importExternalLayer {bbox, count}
 * </pre>
 */
public interface GeoRestPort {

    List<Map<String, Object>> listLayers(GeoCaller caller);

    Map<String, Object> defineLayer(GeoCaller caller, Map<String, Object> body);

    Map<String, Object> getLayer(GeoCaller caller, String layer);

    Map<String, Object> importBoundaries(GeoCaller caller, String layer, Map<String, Object> body);

    List<Map<String, Object>> listFeatures(GeoCaller caller, String layer, String level, String parentId, boolean history, int page,
                                           int size);

    Map<String, Object> putFeature(GeoCaller caller, String layer, Map<String, Object> body);

    Map<String, Object> getFeature(GeoCaller caller, String featureId, boolean withGeometry);

    List<Map<String, Object>> lineage(GeoCaller caller, String featureId);

    void retireFeature(GeoCaller caller, String featureId);

    Map<String, Object> resolve(GeoCaller caller, String layer, String level, double lat, double lon);

    List<Map<String, Object>> nearest(GeoCaller caller, String layer, String objectType, double lat, double lon, int n, double maxKm,
                                      String tags);

    List<Map<String, Object>> overlaps(GeoCaller caller, Map<String, Object> body);

    Map<String, Object> defineFence(GeoCaller caller, Map<String, Object> body);

    Map<String, Object> getFence(GeoCaller caller, String fenceId);

    void deactivateFence(GeoCaller caller, String fenceId);

    Map<String, Object> checkFence(GeoCaller caller, String fenceId, Map<String, Object> body);

    Map<String, Object> putObjectGeometry(GeoCaller caller, String objectType, String objectId, Map<String, Object> body);

    Map<String, Object> getObjectGeometry(GeoCaller caller, String objectType, String objectId, String attribute);

    List<Map<String, Object>> listExternalLayers(GeoCaller caller);

    Map<String, Object> registerExternalLayer(GeoCaller caller, Map<String, Object> body);

    Map<String, Object> queryExternalLayer(GeoCaller caller, String ref, Map<String, Object> body);

    Map<String, Object> importExternalLayer(GeoCaller caller, String ref, Map<String, Object> body);
}
