# SPDX-License-Identifier: Apache-2.0
# sangam-geo: the module's GeoController under /api/v1/geo (geo README §2), registered by sangam-rest's
# GeoRestConfiguration. Operations are derived from the controller source (_v2_spring.py).
TAGS.append(("Geo", "Jurisdiction layers and boundaries, geofences, nearest and overlap queries, object geometry and "
                    "external WFS/WMS layers (sangam-geo)"))
v2_spring_routes("sangam-geo/src/main/java/org/sangam/geo/web", "Geo", "geo", "geo", "geo.read", "geo.write",
                 ["R19", "R41"], no_cmd=("overlaps", "checkFence", "queryExternal"))
# M-051: the Map-backed request bodies above are typed from the keys the geo services read (_v2_bodykeys.py). These are
# the uses of a whole map that read no key; each nested map named here stays an open object in the contract.
bk_opaque_target({
    "GeoJson.features": "a GeoJSON FeatureCollection or Feature (RFC 7946), parsed by GeoJson.features",
})
bk_opaque({
    ("GeoRestService.defineFence", "new Geofence"): "free-form fence attributes, stored as given",
    ("GeoRestService.putFeature", "new BoundaryDataLoader.FeatureInput"): "names by language and free-form feature "
                                                                          "properties, stored as given",
    ("ExternalLayerGateway.query", "uuidV5"): "the whole input is hashed into the connector operation id",
    ("ExternalLayerGateway.query", "new ConnectorRequest"): "forwarded as the input of the external layer's connector "
                                                            "(WFS/WMS parameters such as bbox, count, styles); the "
                                                            "connector is chosen per layer, so its parameters are not "
                                                            "fixed here",
})
