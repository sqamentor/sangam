# SPDX-License-Identifier: Apache-2.0
# sangam-geo: the module's GeoController under /api/v1/geo (geo README §2), registered by sangam-rest's
# GeoRestConfiguration. Operations are derived from the controller source (_v2_spring.py).
TAGS.append(("Geo", "Jurisdiction layers and boundaries, geofences, nearest and overlap queries, object geometry and "
                    "external WFS/WMS layers (sangam-geo)"))
v2_spring_routes("sangam-geo/src/main/java/org/sangam/geo/web", "Geo", "geo", "geo", "geo.read", "geo.write",
                 ["R19", "R41"], no_cmd=("overlaps", "checkFence", "queryExternal"))
