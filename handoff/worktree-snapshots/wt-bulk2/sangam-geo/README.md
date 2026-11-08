# sangam-geo (GENERIC-FIELD-GEO workstream, module M7)

This module holds geometry and spatial rules for any country and any sector (**N13**). Base package: `org.sangam.geo`.

**THIN IN** (CAPABILITY-COVERAGE.md N13):
- geometry as master data;
- jurisdiction hierarchies;
- point-in-polygon routing;
- nearest-N;
- geofences;
- overlap checks.

**OUT:**
- map rendering;
- cadastral editing;
- imagery.

The OUT items stay in an external GIS, reached through the WMS/WFS connectors.

Domain knowledge is data only (SANGAM.md X.0e). Layer codes, level names, hierarchies, boundary polygons, org-unit links and fence definitions are all loaded as data. Nothing in the code names a country, a department or a sector.

## Design

- **Portable storage (A-15, N13 "Database neutrality").**
  - Geometry is stored as GeoJSON text.
  - Bounding boxes and centroids are BIGINT degrees×10⁷ ("E7", about 1 cm). Each feature also has a centroid geohash.
  - Each feature has at most 32 covering geohash cells (`GEO_FEATURE_CELL`). A point lookup is one indexed `IN` over the 12 prefixes of the point's geohash, so it works identically on PostgreSQL, Oracle, SQL Server, MySQL and H2.
- **Exact predicate in Java.** The index only narrows the candidates. `GeoMath` decides:
  - haversine distance;
  - ray-cast point-in-polygon, with holes and boundary-inclusive;
  - edge distance in a local projection;
  - polygon/line intersection.

  Every database therefore gives the same answer.
- **Optional native index.** On PostgreSQL with the PostGIS extension installed, `PostgisSpatialIndex` adds a `geom` column, a GiST index, `ST_Covers` and KNN `<->`. It is selected by `GeoService.create(..., "auto"|"portable"|"postgis", ...)`. The portable cell rows are always written, so PostGIS can be dropped without re-indexing. The other dialects use the portable index.
- **No JTS.** All geometry is plain Java, so no EPL/EDL dependency is introduced and there is no licence to clear. Heavy topology (buffers, unions, cadastral editing) is out of scope and goes to an external GIS worker.

## Requirements covered, file by file

| File | Covers |
|---|---|
| `model/GeoPoint.java`, `BBox.java` | WGS-84 position with range validation; bounding box, radius box, union and expand |
| `model/Geometry.java` | N13 `Geometry` typed value: Point, MultiPoint, LineString, Polygon (with holes), MultiPolygon; circle-to-polygon |
| `model/GeoMath.java` | Haversine, bearing, destination, point-in-polygon (boundary-inclusive), signed edge distance, area, centroid, intersects (N13 "distance and nearest-N", "overlap check") |
| `model/Wkt.java`, `GeoJson.java`, `Geometries.java` | WKT/EWKT (SRID 4326 only, Z/M ignored) and GeoJSON RFC 7946 (geometry, Feature, FeatureCollection) read/write. Format sniffing; `{wkt}`, `{lat,lon}` and string inputs |
| `model/Geohash.java` | Geohash encode/decode, adaptive cover, prefixes (the portable spatial key) |
| `boundary/JurisdictionLayer.java`, `BoundaryLevel.java` | N13 `AdminBoundary` / `JurisdictionLayer`: layers and ordered levels as data. Kinds include ADMIN, ZONE, PROTECTED, FENCE and REFERENCE. Org-unit linkage (C04 "office by location") |
| `boundary/GeoFeature.java` | Versioned feature with `validFrom`/`validTo` (C05-style versioning), parent link, names (i18n), properties, bbox, centroid, geohash, org-unit ref; GeoJSON view |
| `boundary/BoundaryDataLoader.java` | Boundary data package for any hierarchy. The property names for code, level, parent, name and org unit are configurable, so a GIS export loads unchanged. Parents come first |
| `GeoSchema.java` | DDL for `GEO_LAYER`, `GEO_FEATURE`, `GEO_FEATURE_CELL`, `GEO_FENCE`, `GEO_OBJECT_GEOMETRY`, `GEO_EXTERNAL_LAYER` and their indexes, in core dialect tokens (A-15). Every key is tenant-first |
| `store/GeoStore.java` | Portable JDBC for every table. `GEO_OBJECT_GEOMETRY` = "Geometry as a typed attribute of any registry object" |
| `index/SpatialIndex.java`, `PortableGridIndex.java`, `PostgisSpatialIndex.java`, `SpatialIndexes.java` | Spatial index where the DB supports one (PostGIS, optional), with a portable fallback for the others |
| `GeoService.java` | Layers, versioned import (created/versioned/unchanged with warnings, e.g. child centroid outside parent), retire, lineage. `resolve` (deepest level or a given level) and `path`: point-in-polygon jurisdiction resolution for routing. Nearest-N features and objects (with tags), overlaps, geofences (circle, polygon, feature reference), external layers. Audit on the core `AuditChain` (`geo:<layer>` roots). Feature cache |
| `geofence/Geofence.java`, `GeofenceChecker.java`, `GeofenceResult.java` | N05 "GPS against the geofence". The check is accuracy-aware: INSIDE, OUTSIDE or INDETERMINATE (`NEAR_EDGE`, `ACCURACY_TOO_LOW`, `NO_FIX`), plus tolerance and signed distance |
| `rules/Nearest.java` | Pure nearest-N with required tags (N17 "candidate units by distance and skill", R82) |
| `feel/GeoFeelFunctionProvider.java`, `GeoEnginePlugin.java`, `GeoTenant.java` | DMN/FEEL functions, registered through a `ProcessEnginePlugin` (ADR-D-16, no engine change): `jurisdictionAt`, `jurisdictionAtLevel`, `jurisdictionPath`, `jurisdictionOrgUnit`, `distanceKm`, `pointInPolygon`, `withinFence`, `withinRadius`, `nearestFeature`, `geohash`. The tenant comes from the executing instance or the authenticated tenant |
| `bpmn/GeoDelegates.java` | BPMN delegates `Jurisdiction`, `Fence`, `NearestN`, configured by input-mapped variables. BPMN errors `NO_JURISDICTION`, `GEOFENCE_OUTSIDE` and `GEOFENCE_INDETERMINATE` drive manual triage |
| `connector/WfsConnector.java`, `WmsConnector.java`, `GisHttp.java` | External GIS (N13 `Layer`) through the core connector SPI (R71). The calls are WFS GetFeature/GetCapabilities and WMS GetMap URL/GetFeatureInfo/GetCapabilities. Egress allow-list (air-gapped by default, §1.9), XXE-safe XML (R27), A-2 outcome mapping. All calls are reads, so capability class (a) and every failure is `noEffect` |
| `connector/GisSimulatorConnector.java` | SIMULATOR profile (§1.9) for WFS/WMS without network |
| `connector/ExternalLayer.java`, `ExternalLayerGateway.java` | Registered external layers; query through the tenant's connector; WFS import into a local layer (versioned, audited) |
| `rest/GeoRestPort.java`, `GeoRestService.java`, `GeoCaller.java` | Framework-neutral REST port (routes listed on the interface). The caller cannot choose `baseUrl`, so egress goes only to the registered URL |
| `web/GeoController.java`, `GeoCallerResolver.java` | Spring MVC at `/api/v1/geo`; RFC 9457 problem+json |

## Third-party dependencies

None new. The module uses jackson-databind (Apache-2.0), plus the following optional or provided dependencies:
- operaton-engine and operaton-engine-feel-scala (Apache-2.0), optional;
- spring-webmvc (Apache-2.0), optional;
- jakarta.servlet-api (EPL-2.0 / GPL-2.0 with Classpath Exception), provided.

All of these are already on the Sangam dependency line. JTS (EPL-2.0/EDL-1.0) is **not** used. PostGIS (GPL-2.0) is a database server extension used over SQL only: nothing is linked or shipped.

## Integration notes

1. **Liquibase (WS-ENGINE).** Turn `GeoSchema.ddl(dialect)` into `sangam-persistence` changesets for the four dialects. The PostGIS column and GiST index are created at runtime by `PostgisSpatialIndex.ensure` only when the extension exists. They can be moved into a PostgreSQL-only changeset guarded by an `extname='postgis'` precondition.
2. **Wiring (sangam-server):**
   - `GeoService.create(Transactions.of(dataSource or engine), dialect, "auto", new AuditChain(dialect), clock)`;
   - `new GeoEnginePlugin(new GeoFeelFunctionProvider(geoService, defaultTenant))` added to the engine plugins;
   - delegate beans `geoJurisdiction` = `new GeoDelegates.Jurisdiction(geo, defaultTenant)`, plus `geoFence` and `geoNearest`;
   - `new GeoRestService(geo, new ExternalLayerGateway(geo, connectorRegistry::resolve), "geo-admin")`;
   - `GeoController(port, request -> new GeoCaller(ctx.tenantId(), ctx.principalId(), ctx.groups()))` from sangam-rest's `RequestContext`.
3. **Connectors (WS-INTEGRATION).**
   - Register `WfsConnector` / `WmsConnector` instances per tenant in `ConnectorRegistry`, with `GisHttp.Config(baseUrl, allowedHosts, headers, timeout, maxBytes)`.
   - For SIMULATOR profiles, use `GisSimulatorConnector`.
   - The `connectorId` of an `ExternalLayer` names the registry entry.
4. **Field module.** `sangam-field` binds its `GeoPort` to `GeoServicePort(GeoService)`. FEATURE fences resolve against a stored fence id, a feature id, or `layer/code`.
5. **Org units (WS-SECURITY).** `GeoFeature.orgUnitRef` plus `jurisdictionOrgUnit(lat, lon, layer)` gives "office by location". The org-unit ids are those of the security org model.
6. **UI.** Map display stays in the client. Get a WMS URL from `POST /external-layers/{ref}/query {"operation":"getMapUrl", "bbox":[...]}` and GeoJSON from `/features/{id}`.

## Known gaps

- Antimeridian-crossing geometries must be split before loading.
- No native index for Oracle (SDO), SQL Server (geography) or MySQL (SPATIAL) yet. They use the portable index, which is correct but not native-speed.
- Overlap is a boolean intersect. Intersection area, buffers and unions are external-GIS work, as the N13 boundary says.
- Nearest-N is by straight-line distance, not road distance. Road routing is OUT.
