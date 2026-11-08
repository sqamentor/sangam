# SPDX-License-Identifier: Apache-2.0
# Routes of sangam-field, derived from the sangam-rest controllers in org.sangam.rest.modules.field (WS-REST final integration wave).
# Executed inside gen_openapi.py: schema(), op(), q(), pp() and TAGS come from there.

# The "Field" tag exists already (field visits of the workspace UI); extend its description instead of adding a second one.
TAGS[:] = [(n, 'Field inspection app (S15, EC-16), field work N13 (templates, assignments, schedules, random selection, devices, offline sync, evidence) and dispatch N17 (units, jobs, offers, trips). sangam-field' if n == 'Field' else d) for n, d in TAGS]


op('get', '/field/templates', 'fieldWorkTemplates', 'Field', 'Published checklist templates', 'Published checklist templates.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='field.capture')
op('get', '/field/templates/{key}', 'fieldWorkTemplate', 'Field', 'A template (latest version, or a given version)', 'A template (latest version, or a given version).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('key'), q('version', 'int')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='field.capture')
op('post', '/field/templates', 'fieldWorkPublishTemplate', 'Field', 'Publish a template version', 'Publish a template version.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(201, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.publish-template', perm='field.admin', created=True)
op('post', '/field/templates/{key}/{version}/retire', 'fieldWorkRetireTemplate', 'Field', 'Retire a template version', 'Retire a template version.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('key'), pp('version')], resp=(204, None), cmd='field.retire-template', perm='field.admin')
op('get', '/field/assignments', 'fieldWorkAssignments', 'Field', 'Assignments by assignee, status or target', 'Assignments by assignee, status or target.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[q('assignee', 's'), q('status', 's'), q('targetRef', 's'), q('offset', 'int'), q('limit', 'int')], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='field.capture')
op('get', '/field/assignments/{id}', 'fieldWorkAssignment', 'Field', 'An assignment', 'An assignment.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='field.capture')
op('post', '/field/assignments', 'fieldWorkCreateAssignment', 'Field', 'Create an assignment (optionally linked to a BPMN task and business key)', 'Create an assignment (optionally linked to a BPMN task and business key).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(201, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.create-assignment', perm='field.admin', created=True)
op('post', '/field/assignments/{id}/reassign', 'fieldWorkReassign', 'Field', 'Reassign an assignment', 'Reassign an assignment.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.reassign', perm='field.admin')
op('post', '/field/assignments/{id}/transition', 'fieldWorkTransition', 'Field', 'Transition an assignment (accept, start, cancel, ...)', 'Transition an assignment (accept, start, cancel, ...).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.transition', perm='field.capture')
op('post', '/field/assignments/{id}/review', 'fieldWorkReview', 'Field', 'Review a submitted visit (approve or return)', 'Review a submitted visit (approve or return).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.review', perm='field.review')
op('post', '/field/assignments/route', 'fieldWorkRoute', 'Field', "Route-ordered list of the caller's assignments", "Route-ordered list of the caller's assignments.", ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='field.capture')
op('post', '/field/assignments/sweep-overdue', 'fieldWorkSweepOverdue', 'Field', 'Mark overdue assignments (also run by the leader-elected field scheduler)', 'Mark overdue assignments (also run by the leader-elected field scheduler).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.sweep-overdue', perm='field.admin')
op('get', '/field/assignments/{id}/evidence', 'fieldWorkEvidenceOf', 'Field', 'Evidence of an assignment', 'Evidence of an assignment.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='field.capture')
op('get', '/field/schedules', 'fieldWorkSchedules', 'Field', 'Inspection schedules', 'Inspection schedules.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='field.admin')
op('post', '/field/schedules', 'fieldWorkDefineSchedule', 'Field', 'Define an inspection schedule (frequency, selection rule, rotation)', 'Define an inspection schedule (frequency, selection rule, rotation).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.define-schedule', perm='field.admin')
op('post', '/field/schedules/{id}/run', 'fieldWorkRunSchedule', 'Field', 'Run one schedule now', 'Run one schedule now.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.run-schedule', perm='field.admin')
op('post', '/field/schedules/run-due', 'fieldWorkRunDue', 'Field', 'Run every due schedule (also run by the leader-elected field scheduler)', 'Run every due schedule (also run by the leader-elected field scheduler).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), cmd='field.run-due', perm='field.admin')
op('post', '/field/selections', 'fieldWorkSelect', 'Field', 'Verifiable random selection of inspection targets', 'Verifiable random selection of inspection targets.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.select', perm='field.admin')
op('get', '/field/selections/{id}/verify', 'fieldWorkVerifySelection', 'Field', 'Re-verify a random selection from its seed', 'Re-verify a random selection from its seed.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='field.review')
op('post', '/field/devices', 'fieldWorkRegisterDevice', 'Field', 'Register a field device', 'Register a field device.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.register-device', perm='field.capture')
op('get', '/field/devices', 'fieldWorkDevices', 'Field', "The caller's (or, for admins, the tenant's) devices", "The caller's (or, for admins, the tenant's) devices.", ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='field.capture')
op('post', '/field/devices/{id}/status', 'fieldWorkDeviceStatus', 'Field', 'Set a device status (active, lost, revoked: revoke wipes the local queue)', 'Set a device status (active, lost, revoked: revoke wipes the local queue).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.device-status', perm='field.admin')
op('post', '/field/devices/{id}/policy', 'fieldWorkDevicePolicy', 'Field', "Set a device's offline queue policy", "Set a device's offline queue policy.", ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.device-policy', perm='field.admin')
op('get', '/field/devices/{id}/chain', 'fieldWorkChain', 'Field', "Verify a device's packet hash chain", "Verify a device's packet hash chain.", ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='field.review')
op('post', '/field/sync/{deviceId}/upload', 'fieldWorkUpload', 'Field', 'Upload offline packets from a device (idempotent per packet)', 'Upload offline packets from a device (idempotent per packet).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('deviceId')], body={"required": True, "content": {"application/json": {"schema": typ('list:obj')}}}, resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), cmd='field.upload', perm='field.capture')
op('get', '/field/sync/{deviceId}/pull', 'fieldWorkPull', 'Field', 'Pull server changes for a device since a cursor', 'Pull server changes for a device since a cursor.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('deviceId'), q('cursor', 'long')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='field.capture')
op('get', '/field/conflicts', 'fieldWorkConflicts', 'Field', 'Sync conflicts', 'Sync conflicts.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[q('status', 's')], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='field.review')
op('get', '/field/conflicts/{id}', 'fieldWorkConflict', 'Field', 'A sync conflict', 'A sync conflict.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='field.review')
op('post', '/field/conflicts/{id}/resolve', 'fieldWorkResolveConflict', 'Field', 'Resolve a sync conflict', 'Resolve a sync conflict.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.resolve-conflict', perm='field.review')
op('post', '/field/evidence', 'fieldWorkUploadEvidence', 'Field', 'Upload evidence (multipart: meta JSON + file); integrity checks (EXIF, location, time) run on upload', 'Upload evidence (multipart: meta JSON + file); integrity checks (EXIF, location, time) run on upload.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "properties": {'meta': typ('s'), 'file': {"type": "string", "contentMediaType": "application/octet-stream"}}}}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.upload-evidence', perm='field.capture')
op('get', '/field/evidence/{id}', 'fieldWorkEvidence', 'Field', 'Evidence metadata and integrity report', 'Evidence metadata and integrity report.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='field.capture')
op('get', '/field/evidence/{id}/content', 'fieldWorkEvidenceContent', 'Field', 'Evidence content (streamed)', 'Evidence content (streamed).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/octet-stream": {"schema": {"type": "string", "contentMediaType": "application/octet-stream"}}}), perm='field.capture')
op('post', '/field/evidence/{id}/reverify', 'fieldWorkReverifyEvidence', 'Field', 'Re-run the integrity checks of an evidence item', 'Re-run the integrity checks of an evidence item.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.reverify-evidence', perm='field.review')
op('post', '/field/units', 'fieldWorkUpsertUnit', 'Field', 'Create or update a field unit', 'Create or update a field unit.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.upsert-unit', perm='dispatch.operate')
op('get', '/field/units', 'fieldWorkUnits', 'Field', 'Field units, optionally by status', 'Field units, optionally by status.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[q('status', 's')], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='dispatch.operate')
op('get', '/field/units/{id}', 'fieldWorkUnit', 'Field', 'A field unit', 'A field unit.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='dispatch.operate')
op('post', '/field/units/{id}/status', 'fieldWorkUnitStatus', 'Field', 'Set a unit status', 'Set a unit status.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.unit-status', perm='dispatch.operate')
op('post', '/field/units/{id}/pings', 'fieldWorkPing', 'Field', 'Position ping from a unit (AVL feed, N12)', 'Position ping from a unit (AVL feed, N12).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.ping', perm='dispatch.operate')
op('get', '/field/units/{id}/offers', 'fieldWorkUnitOffers', 'Field', 'Open offers for a unit', 'Open offers for a unit.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='dispatch.operate')
op('get', '/field/units/{id}/eta', 'fieldWorkEta', 'Field', 'ETA of a unit to a job', 'ETA of a unit to a job.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id'), q('jobId', 's', required=True)], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='dispatch.operate')
op('get', '/field/units/{id}/trips', 'fieldWorkTrips', 'Field', 'Trips of a unit', 'Trips of a unit.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='dispatch.operate')
op('post', '/field/jobs', 'fieldWorkCreateJob', 'Field', 'Create a dispatch job', 'Create a dispatch job.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.create-job', perm='dispatch.operate')
op('get', '/field/jobs', 'fieldWorkJobs', 'Field', 'Dispatch jobs, optionally by status', 'Dispatch jobs, optionally by status.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[q('status', 's'), q('limit', 'int')], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='dispatch.operate')
op('get', '/field/jobs/{id}', 'fieldWorkJob', 'Field', 'A dispatch job', 'A dispatch job.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), perm='dispatch.operate')
op('get', '/field/jobs/{id}/candidates', 'fieldWorkCandidates', 'Field', 'Ranked candidate units for a job', 'Ranked candidate units for a job.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='dispatch.operate')
op('post', '/field/jobs/{id}/{action}', 'fieldWorkJobAction', 'Field', 'Job action: offer, assign, arrive, close, cancel, ', 'Job action: offer, assign, arrive, close, cancel, ...', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id'), pp('action')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.job-action', perm='dispatch.operate')
op('post', '/field/offers/{id}/respond', 'fieldWorkRespondOffer', 'Field', 'Accept or decline an offer', 'Accept or decline an offer.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.respond-offer', perm='dispatch.operate')
op('post', '/field/offers/expire', 'fieldWorkExpireOffers', 'Field', 'Expire stale offers (also run by the leader-elected field scheduler)', 'Expire stale offers (also run by the leader-elected field scheduler).', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.expire-offers', perm='dispatch.operate')
op('post', '/field/trips', 'fieldWorkStartTrip', 'Field', 'Start a trip', 'Start a trip.', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.start-trip', perm='dispatch.operate')
op('post', '/field/trips/{id}/{action}', 'fieldWorkTripAction', 'Field', 'Trip action: waypoint, pause, end, ', 'Trip action: waypoint, pause, end, ...', ['N13', 'N17', 'S15', 'EC-16', 'R41'],
   params=[pp('id'), pp('action')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}), cmd='field.trip-action', perm='dispatch.operate')
