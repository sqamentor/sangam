import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src/modules')
p = 'registry.tsx'
R = [
("""    { id: 'transition', label: 'act.transition', path: '/registry/objects/{id}/transitions/{name}', fields: [f.text('name', 'fld.transition', true), f.json('attributes')], reason: 'body' },""",
"""    // M-051: the transition reads {reason, until, countersignedBy, expectedVersion} (RegistryController.java:129-133, :238-243); the name is the path
    { id: 'transition', label: 'act.transition', path: '/registry/objects/{id}/transitions/{name}', fields: [f.path(f.text('name', 'fld.transition', true)), f.dt('until', 'fld.until'), f.text('countersignedBy', 'fld.countersignedBy'), f.num('expectedVersion', 'fld.version')], reason: 'body' },"""),
("""    { id: 'allocate', label: 'act.allocate', path: '/registry/number-series/{seriesKey}/allocate', fields: [f.text('scope'), f.text('period'), f.text('reference')], showResult: true },""",
"""    // M-051: allocate {scope, date, attributes, refType, refId} (RegistryController.java:186-191); mark-used {number, refType, refId} (:206-208)
    { id: 'allocate', label: 'act.allocate', path: '/registry/number-series/{seriesKey}/allocate', fields: [f.text('scope'), f.date('date'), f.text('refType'), f.text('refId', 'fld.reference')], showResult: true },"""),
("""    { id: 'used', label: 'act.markUsed', path: '/registry/number-series/{seriesKey}/mark-used', fields: [f.text('number', undefined, true), f.text('reference')] },""",
"""    { id: 'used', label: 'act.markUsed', path: '/registry/number-series/{seriesKey}/mark-used', fields: [f.text('number', undefined, true), f.text('refType'), f.text('refId', 'fld.reference')] },"""),
("""  toolbar: [{ id: 'define', label: 'act.defineRegister', method: 'PUT', path: '/registry/registers/{registerKey}', fields: [f.text('registerKey', undefined, true), f.text('name', undefined, true), f.json('columns'), f.text('scope')] }],
  actions: [{ id: 'append', label: 'act.appendEntry', path: '/registry/registers/{registerKey}/entries', fields: [f.text('scope'), f.text('refType'), f.text('refId'), f.json('values', undefined, true)] }],""",
"""  // M-051: RegisterDefinition {registerKey (path), title, series (GAP_FREE, required), scopeRequired, correctionCountersign, extractTemplate,
  // columns} (RegisterDefinition.java:25-31; RegisterController.java:38-41); append {scope, date, columns, refType, refId} (:55-59)
  toolbar: [{ id: 'define', label: 'act.defineRegister', method: 'PUT', path: '/registry/registers/{registerKey}', fields: [f.path(f.text('registerKey', undefined, true)), f.text('title', 'fld.name', true), f.text('series', 'fld.series', true), f.json('columns'), f.bool('scopeRequired', 'fld.scopeRequired')] }],
  actions: [{ id: 'append', label: 'act.appendEntry', path: '/registry/registers/{registerKey}/entries', fields: [f.text('scope'), f.date('date'), f.text('refType'), f.text('refId'), f.json('columns', 'fld.values', true)] }],"""),
("""    { id: 'correct', label: 'act.correctEntry', path: '/registry/registers/books/{bookId}/entries/{entryNo}/corrections', fields: [f.num('entryNo', undefined, true), f.json('values', undefined, true)], reason: 'body' },""",
"""    // M-051: correction {columns, reason, countersignedBy} (RegisterController.java:91-95); the entry number is the path
    { id: 'correct', label: 'act.correctEntry', path: '/registry/registers/books/{bookId}/entries/{entryNo}/corrections', fields: [f.path(f.num('entryNo', undefined, true)), f.json('columns', 'fld.values', true), f.text('countersignedBy', 'fld.countersignedBy')], reason: 'body' },"""),
("""    { id: 'amend', label: 'act.amend', path: '/registry/instruments/{id}/amend', fields: [f.json('attributes', undefined, true)], reason: 'body' },""",
"""    // M-051: amend {attributes, endorsement (required), expectedVersion} (InstrumentController.java:123-126); the endorsement text is the record
    { id: 'amend', label: 'act.amend', path: '/registry/instruments/{id}/amend', fields: [f.json('attributes', undefined, true), f.area('endorsement', 'fld.endorsement', true), f.num('expectedVersion', 'fld.version')] },"""),
("""    { id: 'endorse', label: 'act.endorse', path: '/registry/instruments/{id}/endorsements', fields: [f.text('kind'), f.area('text', undefined, true)] },""",
"""    // M-051: endorse {type (required), content: object} (InstrumentController.java:137-139)
    { id: 'endorse', label: 'act.endorse', path: '/registry/instruments/{id}/endorsements', fields: [f.text('type', 'fld.kind', true), f.json('content', 'fld.text', true)] },"""),
("""    { id: 'identifier', label: 'act.addIdentifier', path: '/registry/parties/{id}/identifiers', fields: [f.text('idType', undefined, true), f.text('idValue', undefined, true, { sensitive: true })] },
    { id: 'address', label: 'act.addAddress', path: '/registry/parties/{id}/addresses', fields: [f.text('kind'), f.area('line', 'fld.address', true), f.text('lgdCode'), f.text('pincode')] },
    { id: 'relationship', label: 'act.addRelationship', path: '/registry/parties/{id}/relationships', fields: [f.text('type', 'fld.relationshipType', true), f.text('toPartyId', undefined, true)] },""",
"""    // M-051: identifier {type, value (required), issuer, validFrom, validTo, verified} (PartyService.java:446-451); address {type,
    // address: object (required), geometryRef, primary, validFrom, validTo} (:593-602); relationship {toParty, type (required), ...} (PartyController.java:136-139)
    { id: 'identifier', label: 'act.addIdentifier', path: '/registry/parties/{id}/identifiers', fields: [f.text('type', 'fld.idType', true), f.text('value', 'fld.idValue', true, { sensitive: true })] },
    { id: 'address', label: 'act.addAddress', path: '/registry/parties/{id}/addresses', fields: [f.text('type', 'fld.kind'), f.area('address.line', 'fld.address', true), f.text('address.lgdCode', 'fld.lgdCode'), f.text('address.pincode', 'fld.pincode'), f.bool('primary', 'fld.primary')] },
    { id: 'relationship', label: 'act.addRelationship', path: '/registry/parties/{id}/relationships', fields: [f.text('type', 'fld.relationshipType', true), f.text('toParty', 'fld.toPartyId', true)] },"""),
("""    { id: 'match', label: 'act.matchRecord', path: '/registry/parties/match', fields: [f.json('record', 'fld.record', true)], noJson: true, showResult: true },""",
"""    // M-051: the body is the probe itself {kind, name | names, identifiers [{type, value}], ...} (PartyService.findMatches, PartyService.java:743-750)
    { id: 'match', label: 'act.matchRecord', path: '/registry/parties/match', fields: [f.select('kind', ['PERSON', 'ORGANISATION', 'HOUSEHOLD']), f.text('name'), f.json('identifiers')], showResult: true },"""),
("""    { id: 'unmerge', label: 'act.unmerge', path: '/registry/parties/merges/{mergeEventId}/unmerge', fields: [f.text('mergeEventId', undefined, true)], reason: 'body' },""",
"""    { id: 'unmerge', label: 'act.unmerge', path: '/registry/parties/merges/{mergeEventId}/unmerge', fields: [f.path(f.text('mergeEventId', undefined, true))], reason: 'body' },"""),
("""  create: { id: 'register', label: 'act.register', path: '/registry/assets', fields: [f.text('classKey', undefined, true), f.text('tag'), f.text('name', undefined, true), f.text('parentAssetId'), f.text('locationRef'), f.text('custodianRef'), f.money('cost'), f.date('acquiredOn'), f.json('attributes')] },""",
"""  // M-051: asset register keys (AssetService.register, AssetService.java:142-180): classKey and tag required, displayName, acquisitionDate,
  // acquisitionValue with currency, ...
  create: { id: 'register', label: 'act.register', path: '/registry/assets', fields: [f.text('classKey', undefined, true), f.text('tag', undefined, true), f.text('displayName', 'fld.name'), f.text('parentAssetId'), f.text('locationRef'), f.text('custodianRef'), f.money('acquisitionValue', 'fld.cost'), f.text('currency'), f.date('acquisitionDate', 'fld.acquiredOn'), f.json('attributes')] },"""),
("""    { id: 'move', label: 'act.move', path: '/registry/assets/{id}/move', fields: [f.text('toLocationRef'), f.text('toCustodianRef')], reason: 'body' },""",
"""    // M-051: move {toLocation, toCustodian, geometryRef, reason} (AssetController.java:104-106)
    { id: 'move', label: 'act.move', path: '/registry/assets/{id}/move', fields: [f.text('toLocation', 'fld.toLocationRef'), f.text('toCustodian', 'fld.toCustodianRef')], reason: 'body' },"""),
("""    { id: 'reading', label: 'act.recordReading', path: '/registry/assets/{id}/meters/{meterType}/readings', fields: [f.text('meterType', undefined, true), f.money('value', 'fld.reading', true), f.dt('readAt')] },
    { id: 'plan', label: 'act.addPlan', path: '/registry/assets/{id}/plans', fields: [f.text('workType', undefined, true), f.text('interval'), f.json('condition')] },
    { id: 'dispose', label: 'act.dispose', path: '/registry/assets/{id}/dispose', fields: [f.text('method'), f.money('proceeds')], reason: 'body', danger: true },""",
"""    // M-051: reading {reading, readAt, source, note} (AssetController.java:174-177), meter type in the path; plan = PlanSpec {planKey
    // (required), trigger TIME | METER | CONDITION, intervalDays, meterType, meterInterval, leadDays, workType, tasks} (AssetClassDefinition.java:31,
    // :49-62); dispose {date, method, reason, transition} (AssetController.java:138-140)
    { id: 'reading', label: 'act.recordReading', path: '/registry/assets/{id}/meters/{meterType}/readings', fields: [f.path(f.text('meterType', undefined, true)), f.money('reading', 'fld.reading', true), f.dt('readAt'), f.text('note')] },
    { id: 'plan', label: 'act.addPlan', path: '/registry/assets/{id}/plans', fields: [f.text('planKey', 'fld.planKey', true), f.select('trigger', ['TIME', 'METER', 'CONDITION'], 'fld.trigger', true), f.num('intervalDays', 'fld.interval'), f.text('meterType'), f.money('meterInterval', 'fld.meterInterval'), f.num('leadDays', 'fld.leadDays'), f.text('workType'), f.json('tasks', 'fld.tasks')] },
    { id: 'dispose', label: 'act.dispose', path: '/registry/assets/{id}/dispose', fields: [f.text('method'), f.date('date')], reason: 'body', danger: true },"""),
("""  actions: [{ id: 'step', label: 'act.step', path: '/registry/assets/work-orders/{woId}/{step}', fields: [f.select('step', ['assign', 'start', 'hold', 'complete', 'verify', 'close', 'cancel'], 'fld.step', true)], reason: 'body' }],""",
"""  // M-051: the step is the path (AssetController.java:270-275), so it is not sent in the body
  actions: [{ id: 'step', label: 'act.step', path: '/registry/assets/work-orders/{woId}/{step}', fields: [f.path(f.select('step', ['assign', 'start', 'hold', 'resume', 'complete', 'verify', 'close', 'cancel'], 'fld.step', true))], reason: 'body' }],"""),
("""  create: { id: 'register', label: 'act.register', path: '/registry/custody/items', fields: [f.text('typeKey', undefined, true), f.text('label'), f.text('caseRef'), f.text('custodian', undefined, true), f.area('description')] },""",
"""  // M-051: CustodyRequests.Register (typeKey and label required, custodianRef, locationRef, sealNo, ...) (CustodyRequests.java:24-31)
  create: { id: 'register', label: 'act.register', path: '/registry/custody/items', fields: [f.text('typeKey', undefined, true), f.text('label', undefined, true), f.text('caseRef'), f.text('custodianRef', 'fld.custodian', true), f.text('locationRef'), f.text('sealNo'), f.area('description')] },"""),
("""    { id: 'transfer', label: 'act.initiateTransfer', path: '/registry/custody/items/{id}/transfers', fields: [f.text('toCustodian', undefined, true), f.text('purpose')], reason: 'body' },""",
"""    // M-051: Transfer(toCustodian (required), toLocation, reason, fromSignatureRef) (CustodyRequests.java:39-42)
    { id: 'transfer', label: 'act.initiateTransfer', path: '/registry/custody/items/{id}/transfers', fields: [f.text('toCustodian', undefined, true), f.text('toLocation', 'fld.location')], reason: 'body' },"""),
("""    { id: 'breakSeal', label: 'act.breakSeal', path: '/registry/custody/items/{id}/break-seal', fields: [f.text('sealNo')], reason: 'body', danger: true },
    { id: 'inspect', label: 'act.inspect', path: '/registry/custody/items/{id}/inspect', fields: [f.area('findings')] },
    { id: 'location', label: 'act.setLocation', path: '/registry/custody/items/{id}/location', fields: [f.text('location', undefined, true)] },""",
"""    // M-051: break-seal {reason, witnessRef}; inspect {conditionText, witnessRef, details}; location {locationRef, reason} (CustodyController.java:144-160)
    { id: 'breakSeal', label: 'act.breakSeal', path: '/registry/custody/items/{id}/break-seal', fields: [f.text('witnessRef', 'fld.witnessRef')], reason: 'body', danger: true },
    { id: 'inspect', label: 'act.inspect', path: '/registry/custody/items/{id}/inspect', fields: [f.area('conditionText', 'fld.findings'), f.text('witnessRef', 'fld.witnessRef'), f.json('details')] },
    { id: 'location', label: 'act.setLocation', path: '/registry/custody/items/{id}/location', fields: [f.text('locationRef', 'fld.location', true)], reason: 'body' },"""),
("""    { id: 'dispose', label: 'act.orderDisposal', path: '/registry/custody/items/{id}/disposal-orders', fields: [f.text('method', undefined, true), f.text('orderRef', undefined, true)], reason: 'body', danger: true },""",
"""    // M-051: Disposal(method, authorityRef (both required), details) (CustodyRequests.java:75-77)
    { id: 'dispose', label: 'act.orderDisposal', path: '/registry/custody/items/{id}/disposal-orders', fields: [f.text('method', undefined, true), f.text('authorityRef', 'fld.orderRef', true), f.json('details')], reason: 'body', danger: true },"""),
("""    { id: 'accept', label: 'act.accept', path: '/registry/custody/transfers/{transferId}/accept', fields: [f.area('condition', 'fld.conditionOnReceipt')] },""",
"""    // M-051: Accept(toSignatureRef, conditionText, sealIntact, observedSealNo, locationRef, witnessRef) (CustodyRequests.java:47-50)
    { id: 'accept', label: 'act.accept', path: '/registry/custody/transfers/{transferId}/accept', fields: [f.area('conditionText', 'fld.conditionOnReceipt'), f.bool('sealIntact', 'fld.sealIntact'), f.text('observedSealNo', 'fld.sealNo'), f.text('locationRef'), f.text('witnessRef', 'fld.witnessRef')] },"""),
]
for old, new in R:
    sub(p, old, new)
