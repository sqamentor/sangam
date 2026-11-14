import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src/modules')
p = 'fieldgeo.tsx'
FRS = 'FieldRestService.java'
R = [
("""  create: { id: 'publish', label: 'act.publishTemplate', path: '/field/templates', fields: [f.text('key', undefined, true), f.text('name', undefined, true), f.json('sections', 'fld.checklistSections', true), f.json('i18n', 'fld.hindiLabels')], hint: 'mod.field.templateHint' },""",
"""  // M-051: the body is a FieldTemplate(key, version, title {lang: text}, offlineCapable, sections, scoring, geofence, evidence, attributes)
  // (FieldTemplate.java:29; FieldRestService.java:176-178)
  create: { id: 'publish', label: 'act.publishTemplate', path: '/field/templates', fields: [f.text('key', undefined, true), f.json('title', 'fld.title', true), f.bool('offlineCapable', 'fld.offlineCapable'), f.json('sections', 'fld.checklistSections', true)], hint: 'mod.field.templateHint' },"""),
("""  create: { id: 'create', label: 'act.create', path: '/field/assignments', fields: [f.text('templateKey', undefined, true), f.text('targetRef', undefined, true), f.text('assignee', undefined, true), f.dt('dueAt'), f.text('taskId'), f.text('businessKey'), f.json('location')] },""",
"""  // M-051: NewAssignment(templateKey, templateVersion, targetRef, targetLabel, address, lat, lon, assignee, seatId, dueAt, windowStart,
  // windowEnd, taskId, businessKey, attributes) (AssignmentService.java:31; FieldRestService.java:212-214)
  create: { id: 'create', label: 'act.create', path: '/field/assignments', fields: [f.text('templateKey', undefined, true), f.text('targetRef', undefined, true), f.text('assignee', undefined, true), f.dt('dueAt'), f.text('taskId'), f.text('businessKey'), f.text('address'), f.num('lat'), f.num('lon')] },"""),
("""    { id: 'route', label: 'act.myRoute', path: '/field/assignments/route', fields: [f.text('fromLat'), f.text('fromLon')], showResult: true },""",
"""    // M-051: the start point is {lat, lon} (FieldRestService.java:234-241)
    { id: 'route', label: 'act.myRoute', path: '/field/assignments/route', fields: [f.num('lat', 'fld.fromLat'), f.num('lon', 'fld.fromLon')], showResult: true },"""),
("""    { id: 'transition', label: 'act.transition', path: '/field/assignments/{id}/transition', fields: [f.select('action', ['accept', 'start', 'suspend', 'resume', 'cancel'], 'fld.transition', true)], reason: 'body' },
    { id: 'reassign', label: 'act.reassign', path: '/field/assignments/{id}/reassign', fields: [f.text('assignee', undefined, true)], reason: 'body' },
    { id: 'review', label: 'act.review', path: '/field/assignments/{id}/review', fields: [f.select('decision', ['APPROVE', 'RETURN'], undefined, true), f.area('remarks')] },""",
"""    // M-051: transition {status: Assignment.Status, version?, reason} (FieldRestService.java:223-225; Assignment.java:23);
    // reassign {assignee, seatId?, version?} (:217-219); review {accept, reason} (:229-231)
    { id: 'transition', label: 'act.transition', path: '/field/assignments/{id}/transition', fields: [f.select('status', ['PLANNED', 'ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'OVERDUE'], 'fld.transition', true), f.num('version')], reason: 'body' },
    { id: 'reassign', label: 'act.reassign', path: '/field/assignments/{id}/reassign', fields: [f.text('assignee', undefined, true), f.text('seatId'), f.num('version')], reason: 'body' },
    { id: 'review', label: 'act.review', path: '/field/assignments/{id}/review', fields: [f.bool('accept', 'fld.acceptVisit'), f.area('reason')] },"""),
("""  create: { id: 'define', label: 'act.defineSchedule', path: '/field/schedules', fields: [f.text('templateKey', undefined, true), f.select('frequency', ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'], undefined, true), f.json('selectionRule', 'fld.selectionRule', true), f.json('rotation')] },""",
"""  // M-051: ScheduleService.Schedule(id, templateKey, cadenceDays, dueDays, targets, assigneePool, rotationExcludeLast, sampleSize,
  // riskDecisionKey, rotationDecisionKey, active, ...) (ScheduleService.java:35; FieldRestService.java:264-266); Target(ref, label,
  // address, lat, lon, weight, attributes)
  create: { id: 'define', label: 'act.defineSchedule', path: '/field/schedules', fields: [f.text('id'), f.text('templateKey', undefined, true), f.num('cadenceDays', 'fld.cadenceDays', true), f.num('dueDays'), f.json('targets', 'fld.targets', true), f.json('assigneePool', 'fld.assigneePool'), f.num('sampleSize', 'fld.sampleSize'), f.num('rotationExcludeLast', 'fld.rotationExcludeLast'), f.text('riskDecisionKey', 'fld.riskDecisionKey'), f.text('rotationDecisionKey', 'fld.rotation'), f.bool('active')], defaults: () => ({ active: true }) },"""),
("""    { id: 'select', label: 'act.randomSelection', path: '/field/selections', fields: [f.text('population', 'fld.population', true), f.num('count', undefined, true), f.text('seedCommitment')], showResult: true, hint: 'mod.field.selectionHint' },""",
"""    // M-051: {population: [{ref, weight, attributes}], seed (required), sampleSize, riskDecisionKey} (FieldRestService.java:279-281;
    // RandomSelector.Candidate, RandomSelector.java:35)
    { id: 'select', label: 'act.randomSelection', path: '/field/selections', fields: [f.json('population', 'fld.population', true), f.num('sampleSize', 'fld.count', true), f.text('seed', 'fld.seedCommitment', true), f.text('riskDecisionKey', 'fld.riskDecisionKey')], showResult: true, hint: 'mod.field.selectionHint' },"""),
("""  create: { id: 'register', label: 'act.registerDevice', path: '/field/devices', fields: [f.text('model'), f.text('publicKey', undefined, true)] },""",
"""  // M-051: DeviceRegistry.Registration(deviceId, unitId, platform, appVersion, publicKey, keyAlg) (DeviceRegistry.java:55)
  create: { id: 'register', label: 'act.registerDevice', path: '/field/devices', fields: [f.text('deviceId'), f.text('unitId'), f.text('platform', 'fld.model'), f.text('appVersion', 'fld.appVersion'), f.text('publicKey', undefined, true), f.text('keyAlg', 'fld.keyAlg')] },"""),
("""    { id: 'policy', label: 'act.queuePolicy', path: '/field/devices/{id}/policy', fields: [f.num('maxPackets'), f.num('maxAgeHours'), f.bool('requireLocation')] },""",
"""    // M-051: QueuePolicy(maxItems, maxAgeHours, wipeOnRevoke, requireSignature, maxOfflineHours) (QueuePolicy.java:13; FieldRestService.java:310-312)
    { id: 'policy', label: 'act.queuePolicy', path: '/field/devices/{id}/policy', fields: [f.num('maxItems', 'fld.maxPackets'), f.num('maxAgeHours'), f.num('maxOfflineHours', 'fld.maxOfflineHours'), f.bool('requireSignature', 'fld.requireSignature'), f.bool('wipeOnRevoke', 'fld.wipeOnRevoke')] },"""),
("""    { id: 'resolve', label: 'act.resolve', path: '/field/conflicts/{id}/resolve', fields: [f.select('choice', ['KEEP_SERVER', 'KEEP_DEVICE', 'MERGE'], undefined, true), f.json('merged')], reason: 'body' },""",
"""    // M-051: {resolution: ACCEPT_CLIENT | KEEP_SERVER | DISCARD, note} (FieldRestService.java:338-340; ConflictResolver.java:38)
    { id: 'resolve', label: 'act.resolve', path: '/field/conflicts/{id}/resolve', fields: [f.select('resolution', ['ACCEPT_CLIENT', 'KEEP_SERVER', 'DISCARD'], 'fld.choice', true), f.area('note')] },"""),
("""  create: { id: 'save', label: 'act.saveUnit', path: '/field/units', fields: [f.text('id', 'fld.unitId', true), f.text('name', undefined, true), f.text('kind'), f.json('skills'), f.text('baseLocation')] },""",
"""  // M-051: DispatchService.UnitInput(id, kind, label, skills, homeLat, homeLon, orgUnitRef, attributes) (DispatchService.java:53)
  create: { id: 'save', label: 'act.saveUnit', path: '/field/units', fields: [f.text('id', 'fld.unitId', true), f.text('label', 'fld.name', true), f.text('kind'), f.json('skills'), f.num('homeLat', 'fld.homeLat'), f.num('homeLon', 'fld.homeLon'), f.text('orgUnitRef', 'fld.orgUnit')] },"""),
("""f.text('address'), f.area('description'), f.json('requiredSkills')] })""",
"""f.text('address'), f.json('requiredSkills'), f.text('businessKey'), f.num('slaTargetSeconds', 'fld.slaTargetSeconds'), f.bool('autoDispatch', 'fld.autoDispatch'), f.json('attributes')] }) // M-051: JobInput keys (FieldRestService.java:427-433)"""),
("""  create: { id: 'define', label: 'act.defineLayer', path: '/geo/layers', fields: [f.text('layer', undefined, true), f.text('name', undefined, true), f.json('levels', 'fld.levels', true)] },
  actions: [{ id: 'import', label: 'act.importBoundaries', path: '/geo/layers/{layer}/import', fields: [f.json('featureCollection', 'fld.geojson', true), f.text('level'), f.date('effectiveFrom')], noJson: true, hint: 'mod.geo.importHint' }],""",
"""  // M-051: a layer is {code, names {lang: text}, levels [{code, names}], attributes, ...} (BoundaryDataLoader.layer, BoundaryDataLoader.java:104)
  create: { id: 'define', label: 'act.defineLayer', path: '/geo/layers', fields: [f.text('code', 'fld.layer', true), f.json('names', 'fld.names', true), f.json('levels', 'fld.levels', true)] },
  // M-051: a boundary package {features (GeoJSON), codeProperty, levelProperty, parentProperty, orgUnitProperty, nameProperties, layer?}
  // (BoundaryDataLoader.read, BoundaryDataLoader.java:57-66)
  actions: [{ id: 'import', label: 'act.importBoundaries', path: '/geo/layers/{layer}/import', fields: [f.json('features', 'fld.geojson', true), f.text('codeProperty', 'fld.codeProperty'), f.text('levelProperty', 'fld.levelProperty'), f.text('parentProperty', 'fld.parentProperty'), f.json('nameProperties', 'fld.nameProperties')], noJson: true, hint: 'mod.geo.importHint' }],"""),
("""  create: { id: 'put', label: 'act.putFeature', path: '/geo/layers/{layer}/features', fields: [f.text('code', undefined, true), f.text('name', undefined, true), f.text('level', undefined, true), f.text('parentId'), f.json('geometry', 'fld.geometry', true)] },""",
"""  // M-051: {code, level, parentCode, names, properties, geometry, orgUnitRef} (GeoRestService.java:127-135)
  create: { id: 'put', label: 'act.putFeature', path: '/geo/layers/{layer}/features', fields: [f.text('code', undefined, true), f.json('names', 'fld.names', true), f.text('level', undefined, true), f.text('parentCode', 'fld.parentCode'), f.json('geometry', 'fld.geometry', true)] },"""),
("""    { id: 'overlaps', label: 'act.overlaps', path: '/geo/overlaps', fields: [f.text('layer', undefined, true), f.text('level')], showResult: true },""",
"""    // M-051: {layer, geometry} (GeoRestService.java:204-209)
    { id: 'overlaps', label: 'act.overlaps', path: '/geo/overlaps', fields: [f.text('layer', undefined, true), f.json('geometry', 'fld.geometry', true)], showResult: true },"""),
("""  create: { id: 'define', label: 'act.defineFence', path: '/geo/fences', fields: [f.text('name', undefined, true), f.json('geometry', 'fld.geometry'), f.text('featureId'), f.num('bufferMeters'), f.text('objectType')], showResult: true },""",
"""  // M-051: {id (required), kind CIRCLE | POLYGON | FEATURE, geometry, center, radiusM, featureRef, toleranceM, maxAccuracyM, attributes}
  // (GeoRestService.java:216-224; Geofence.java:24)
  create: { id: 'define', label: 'act.defineFence', path: '/geo/fences', fields: [f.text('id', 'fld.fenceId', true), f.select('kind', ['POLYGON', 'CIRCLE', 'FEATURE'], undefined, true), f.json('geometry', 'fld.geometry'), f.json('center', 'fld.center'), f.num('radiusM', 'fld.radiusM'), f.text('featureRef', 'fld.featureId'), f.num('toleranceM', 'fld.bufferMeters'), f.json('attributes')], showResult: true },"""),
("""  create: { id: 'register', label: 'act.registerExternal', path: '/geo/external-layers', fields: [f.text('ref', undefined, true), f.text('name', undefined, true), f.select('kind', ['WFS', 'ARCGIS_REST', 'GEOJSON_URL'], undefined, true), f.text('url', undefined, true, { description: 'admin.egressHint' })] },""",
"""  // M-051: ExternalLayer(ref, protocol WMS | WFS, connectorId, baseUrl, remoteName, serviceVersion, srs, outputFormat, importLayer,
  // attributes, active) (GeoRestService.java:305-310; ExternalLayer.java:19-23: ref, protocol and remoteName required)
  create: { id: 'register', label: 'act.registerExternal', path: '/geo/external-layers', fields: [f.text('ref', undefined, true), f.select('protocol', ['WMS', 'WFS'], 'fld.kind', true), f.text('remoteName', 'fld.name', true), f.text('baseUrl', 'fld.url', true, { description: 'admin.egressHint' }), f.text('importLayer', 'fld.layer'), f.json('attributes')] },"""),
]
for old, new in R:
    sub(p, old, new)
