import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src')
p = 'modules/resources.tsx'
R = []
def r(old, new):
    R.append((old, new))

# types: Map body read by ResourceType.fromMap (ResourceType.java:112-139)
r("""create: { id: 'define', label: 'act.defineType', path: '/resources/types', fields: [f.text('typeId', undefined, true), f.text('name', undefined, true), f.bool('bookable'), f.bool('approvalRequired'), f.json('rules')] },""",
  """// M-051: Map body read by ResourceType.fromMap (ResourceType.java:112-139; bookingMode SLOT | ALLOTMENT | BOTH | NONE, :58-66).
  // Further keys (refundTiers, attributes, decisions, limits) go in "More fields".
  create: { id: 'define', label: 'act.defineType', path: '/resources/types', fields: [f.text('typeId', undefined, true), f.text('name', undefined, true), f.text('kind'), f.select('bookingMode', ['SLOT', 'ALLOTMENT', 'BOTH', 'NONE'], 'fld.bookingMode'), f.num('slotMinutes', 'fld.slotMinutes'), f.num('defaultCapacity', 'fld.defaultCapacity'), f.bool('requiresApproval', 'fld.requiresApproval'), f.text('approvalProcessKey', 'fld.approvalProcessKey'), f.text('currency')] },""")
# calendars: AvailabilityCalendar.fromMap (AvailabilityCalendar.java:58-79)
r("""{ id: 'calendar', label: 'act.saveCalendar', method: 'PUT', path: '/resources/calendars/{calendarId}', fields: [f.text('calendarId', undefined, true), f.json('workingHours', undefined, true), f.json('holidays')], hint: 'mod.resources.calendarHint' },""",
  """// M-051: {name, zone, businessCalendarId, closedOnHolidays, hours: {MONDAY: ["09:00-17:00"]}} (AvailabilityCalendar.java:58-79)
    { id: 'calendar', label: 'act.saveCalendar', method: 'PUT', path: '/resources/calendars/{calendarId}', fields: [f.path(f.text('calendarId', undefined, true)), f.text('name'), f.text('zone', 'fld.zone'), f.json('hours', 'fld.openingHours', true), f.text('businessCalendarId', 'fld.businessCalendarId'), f.bool('closedOnHolidays', 'fld.closedOnHolidays')], hint: 'mod.resources.calendarHint' },""")
# slot templates: CalendarService.SlotTemplate (CalendarService.java:51-54; ResourcesController.java:198-202)
r("""{ id: 'slots', label: 'act.saveSlotTemplate', method: 'PUT', path: '/resources/slot-templates', fields: [f.text('resourceId', undefined, true), f.text('slotLength', undefined, true), f.json('days')], hint: 'mod.resources.slotHint' },""",
  """// M-051: SlotTemplate(templateId, resourceId, weekdays, from, to, slotMinutes, unitsPerSlot, validFrom, validTo, attributes) (CalendarService.java:51-54)
    { id: 'slots', label: 'act.saveSlotTemplate', method: 'PUT', path: '/resources/slot-templates', fields: [f.text('templateId', 'fld.templateId'), f.text('resourceId', undefined, true), f.json('weekdays', 'fld.weekdays', true), f.text('from', 'fld.fromTime', true), f.text('to', 'fld.toTime', true), f.num('slotMinutes', 'fld.slotMinutes', true), f.num('unitsPerSlot', 'fld.unitsPerSlot'), f.date('validFrom'), f.date('validTo')], hint: 'mod.resources.slotHint' },""")
# search: AvailableSearchRequest(filter Resource.Query, from, to, quantity) (ResourcesController.java:66; Resource.java:40)
r("""{ id: 'search', label: 'act.searchAvailable', path: '/resources/search-available', fields: [f.text('typeId'), f.dt('from', undefined, true), f.dt('to', undefined, true), f.num('capacity')], showResult: true },""",
  """// M-051: AvailableSearchRequest(filter, from, to, quantity) (ResourcesController.java:66); filter is Resource.Query (Resource.java:40)
    { id: 'search', label: 'act.searchAvailable', path: '/resources/search-available', fields: [f.text('filter.typeId', 'fld.typeId'), f.num('filter.minCapacity', 'fld.minCapacity'), f.dt('from', undefined, true), f.dt('to', undefined, true), f.num('quantity', 'fld.quantity')], showResult: true },""")
# move: MoveRequest(newParentId) (ResourcesController.java:63)
r("""{ id: 'move', label: 'act.move', path: '/resources/items/{resourceId}/move', fields: [f.text('parentId'), f.text('locationId')], reason: 'body' },""",
  """// M-051: MoveRequest(newParentId) (ResourcesController.java:63, :162)
    { id: 'move', label: 'act.move', path: '/resources/items/{resourceId}/move', fields: [f.text('newParentId', 'fld.newParentId', true)], reason: 'body' },""")
# quote / hold: BookingModel.HoldRequest (BookingModel.java:60-63)
r("""{ id: 'quote', label: 'act.quote', path: '/resources/bookings/quote', fields: [f.text('resourceId', undefined, true), f.dt('from', undefined, true), f.dt('to', undefined, true)], showResult: true },""",
  """// M-051: HoldRequest(resourceId, startsAt, endsAt, quantity, requesterId, beneficiaryId, partyRef, purpose, ...) (BookingModel.java:60-63)
    { id: 'quote', label: 'act.quote', path: '/resources/bookings/quote', fields: [f.text('resourceId', undefined, true), f.dt('startsAt', 'fld.startsAt', true), f.dt('endsAt', undefined, true), f.num('quantity', 'fld.quantity')], showResult: true },""")
r("""{ id: 'hold', label: 'act.hold', path: '/resources/bookings/hold', fields: [f.text('resourceId', undefined, true), f.dt('from', undefined, true), f.dt('to', undefined, true), f.text('purpose'), f.num('attendees')], showResult: true },""",
  """{ id: 'hold', label: 'act.hold', path: '/resources/bookings/hold', fields: [f.text('resourceId', undefined, true), f.dt('startsAt', 'fld.startsAt', true), f.dt('endsAt', undefined, true), f.num('quantity', 'fld.quantity'), f.text('purpose'), f.text('requesterId'), f.text('beneficiaryId', 'fld.beneficiaryId')], showResult: true },""")
# series: SeriesRequest(first, recurrence) (ResourcesController.java:69; Recurrence, BookingModel.java:74-77)
r("""{ id: 'series', label: 'act.holdSeries', path: '/resources/bookings/hold-series', fields: [f.text('resourceId', undefined, true), f.dt('from', undefined, true), f.dt('to', undefined, true), f.text('rrule', undefined, true), f.text('purpose')], showResult: true },""",
  """// M-051: SeriesRequest(first: HoldRequest, recurrence: Recurrence(frequency DAILY|WEEKLY|MONTHLY, interval, byDays, count, until, allOrNothing))
    // (ResourcesController.java:69; BookingModel.java:74-77)
    { id: 'series', label: 'act.holdSeries', path: '/resources/bookings/hold-series', fields: [f.text('first.resourceId', 'fld.resourceId', true), f.dt('first.startsAt', 'fld.startsAt', true), f.dt('first.endsAt', 'fld.endsAt', true), f.text('first.purpose', 'fld.purpose'), f.select('recurrence.frequency', ['DAILY', 'WEEKLY', 'MONTHLY'], 'fld.frequency', true), f.num('recurrence.interval', 'fld.interval', true), f.json('recurrence.byDays', 'fld.byDays'), f.num('recurrence.count', 'fld.count'), f.date('recurrence.until', 'fld.until'), f.bool('recurrence.allOrNothing', 'fld.allOrNothing')], showResult: true },""")
# approval: ApprovalRequest(boolean approved, reason) (ResourcesController.java:72, :286)
r("""{ id: 'approval', label: 'act.decideApproval', path: '/resources/bookings/{bookingId}/approval', fields: [f.select('decision', ['APPROVE', 'REJECT'], undefined, true)], reason: 'body' },""",
  """// M-051: ApprovalRequest(approved, reason) (ResourcesController.java:72, :286)
    { id: 'approval', label: 'act.decideApproval', path: '/resources/bookings/{bookingId}/approval', fields: [f.bool('approved', 'fld.approved')], reason: 'body' },""")
# waitlist: WaitlistRequest (BookingModel.java:112-115)
r("""create: { id: 'enqueue', label: 'act.enqueue', path: '/resources/waitlist', fields: [f.text('scopeKey', undefined, true), f.text('applicantId', undefined, true), f.num('priority')] },""",
  """// M-051: WaitlistRequest(typeId, resourceId, requesterId, beneficiaryId, category, priorityLevel, seniorityAt, wantedFrom, wantedTo, quantity,
  // attributes) (BookingModel.java:112-115; ResourcesController.java:328)
  create: { id: 'enqueue', label: 'act.enqueue', path: '/resources/waitlist', fields: [f.text('typeId'), f.text('resourceId'), f.text('requesterId'), f.text('beneficiaryId', 'fld.beneficiaryId'), f.text('category'), f.num('priorityLevel', 'fld.priorityLevel'), f.dt('seniorityAt', 'fld.seniorityAt'), f.dt('wantedFrom', 'fld.wantedFrom'), f.dt('wantedTo', 'fld.wantedTo'), f.num('quantity', 'fld.quantity')] },""")
# allotments: AllotRequest (BookingModel.java:133-136)
r("""create: { id: 'allot', label: 'act.allot', path: '/resources/allotments', fields: [f.text('resourceId', undefined, true), f.text('holderId', undefined, true), f.date('from', undefined, true), f.date('to')] },""",
  """// M-051: AllotRequest(resourceId, unitNo, holderId, beneficiaryId, validFrom, validTo, orderRef, waitlistId, entitlementClass, attributes)
  // (BookingModel.java:133-136; instants)
  create: { id: 'allot', label: 'act.allot', path: '/resources/allotments', fields: [f.text('resourceId', undefined, true), f.num('unitNo', 'fld.unitNo'), f.text('holderId', undefined, true), f.text('beneficiaryId', 'fld.beneficiaryId'), f.dt('validFrom', 'fld.validFrom', true), f.dt('validTo', 'fld.validTo'), f.text('orderRef'), f.text('waitlistId', 'fld.waitlistId')] },""")
# draws: DrawRequest(scopeKey, seed), DrawVerifyRequest(entryIds, seed) (ResourcesController.java:84, :87)
r("""{ id: 'draw', label: 'act.draw', path: '/resources/draws', fields: [f.text('scopeKey', undefined, true), f.num('count', undefined, true), f.text('seedCommitment')], showResult: true, hint: 'mod.resources.drawHint' },""",
  """// M-051: DrawRequest(scopeKey, seed) and DrawVerifyRequest(entryIds, seed) (ResourcesController.java:84, :87)
    { id: 'draw', label: 'act.draw', path: '/resources/draws', fields: [f.text('scopeKey', undefined, true), f.text('seed', undefined, true)], showResult: true, hint: 'mod.resources.drawHint' },""")
r("""{ id: 'verifyDraw', label: 'act.verifyDraw', path: '/resources/draws/verify', fields: [f.text('drawId', undefined, true), f.text('seed', undefined, true)], showResult: true },""",
  """{ id: 'verifyDraw', label: 'act.verifyDraw', path: '/resources/draws/verify', fields: [f.json('entryIds', 'fld.entryIds', true), f.text('seed', undefined, true)], showResult: true },""")
# notice / extend (ResourcesController.java:93, :99)
r("""{ id: 'notice', label: 'act.noticeToVacate', path: '/resources/allotments/{allotmentId}/notice-to-vacate', fields: [f.date('vacateBy', undefined, true)], reason: 'body' },""",
  """// M-051: NoticeRequest(dueAt, reason, authorityRef) (ResourcesController.java:93)
    { id: 'notice', label: 'act.noticeToVacate', path: '/resources/allotments/{allotmentId}/notice-to-vacate', fields: [f.dt('dueAt', undefined, true), f.text('authorityRef', 'fld.authorityRef')], reason: 'body' },""")
r("""{ id: 'extend', label: 'act.extend', path: '/resources/allotments/{allotmentId}/extend', fields: [f.date('to', undefined, true)], reason: 'body' },""",
  """// M-051: ExtendRequest(newValidTo, long version, reason) (ResourcesController.java:99, :426-431)
    { id: 'extend', label: 'act.extend', path: '/resources/allotments/{allotmentId}/extend', fields: [f.dt('newValidTo', 'fld.newValidTo', true), f.num('version', undefined, true)], defaults: (r) => ({ version: r?.version as number }), reason: 'body' },""")
# queues (QueueRestPort.java:52-61)
r("""toolbar: [{ id: 'define', label: 'act.defineServicePoint', method: 'PUT', path: '/queues/service-points/{servicePointId}', fields: [f.text('servicePointId', undefined, true), f.text('name', undefined, true), f.json('services'), f.num('slaMinutes')] }],""",
  """// M-051: ServicePointRequest(name, resourceId, calendarId, tokenPrefix, slaMinutes, priorityDecision, routingDecision, config) (QueueRestPort.java:52-54)
  toolbar: [{ id: 'define', label: 'act.defineServicePoint', method: 'PUT', path: '/queues/service-points/{servicePointId}', fields: [f.path(f.text('servicePointId', undefined, true)), f.text('name', undefined, true), f.text('resourceId'), f.text('calendarId'), f.text('tokenPrefix', 'fld.tokenPrefix'), f.num('slaMinutes'), f.text('priorityDecision', 'fld.priorityDecision'), f.text('routingDecision', 'fld.routingDecision'), f.json('config', 'fld.queueConfig')] }],""")
r("""{ id: 'counter', label: 'act.defineCounter', method: 'PUT', path: '/queues/service-points/{servicePointId}/counters/{counterId}', fields: [f.text('counterId', undefined, true), f.text('name', undefined, true), f.json('services')] },""",
  """// M-051: CounterRequest(name, skills) (QueueRestPort.java:56)
    { id: 'counter', label: 'act.defineCounter', method: 'PUT', path: '/queues/service-points/{servicePointId}/counters/{counterId}', fields: [f.path(f.text('counterId', undefined, true)), f.text('name', undefined, true), f.json('skills')] },""")
r("""{ id: 'token', label: 'act.issueToken', path: '/queues/service-points/{servicePointId}/tokens', fields: [f.text('service'), f.select('category', ['GENERAL', 'SENIOR', 'DIVYANG', 'APPOINTMENT'])], showResult: true },""",
  """// M-051: IssueTokenRequest(channel, bookingId, partyId, priorityClass, skill, routingKey, attributes) (QueueRestPort.java:59-60); channel is
    // QueueModel.Channel (QueueModel.java:28); priority classes are the service point's configuration (QueueService.java:352-361)
    { id: 'token', label: 'act.issueToken', path: '/queues/service-points/{servicePointId}/tokens', fields: [f.select('channel', ['WALK_IN', 'KIOSK', 'SMS', 'QR', 'APPOINTMENT', 'TRANSFER', 'COUNTER']), f.text('priorityClass', 'fld.priorityClass'), f.text('skill', 'fld.skill'), f.text('routingKey', 'fld.routingKey'), f.text('partyId'), f.text('bookingId', 'fld.bookingId')], showResult: true },""")
# rosters (RosterRestPort.java:72-102)
r("""create: { id: 'save', label: 'act.saveShift', method: 'PUT', path: '/rosters/shifts', fields: [f.text('shiftId', undefined, true), f.text('name', undefined, true), f.text('start', 'fld.startTime', true), f.text('end', 'fld.endTime', true), f.num('minStaff')] },""",
  """// M-051: ShiftRequest(shiftId, code, name, fromTime, durationMinutes, night, skills, zoneId, attributes) (RosterRestPort.java:72-73)
  create: { id: 'save', label: 'act.saveShift', method: 'PUT', path: '/rosters/shifts', fields: [f.text('shiftId', undefined, true), f.text('code'), f.text('name', undefined, true), f.text('fromTime', 'fld.startTime', true), f.num('durationMinutes', 'fld.durationMinutes', true), f.bool('night', 'fld.nightShift'), f.json('skills'), f.text('zoneId', 'fld.zone')] },""")
r("""create: { id: 'save', label: 'act.saveRotation', method: 'PUT', path: '/rosters/rotations', fields: [f.text('rotationId', undefined, true), f.text('name', undefined, true), f.json('pattern', undefined, true)] },""",
  """// M-051: RotationRequest(rotationId, name, cycle) (RosterRestPort.java:76)
  create: { id: 'save', label: 'act.saveRotation', method: 'PUT', path: '/rosters/rotations', fields: [f.text('rotationId', undefined, true), f.text('name', undefined, true), f.json('cycle', undefined, true)] },""")
r("""create: { id: 'create', label: 'act.create', path: '/rosters/periods', fields: [f.text('unitId', undefined, true), f.date('from', undefined, true), f.date('to', undefined, true), f.text('rotationId')] },""",
  """// M-051: PeriodRequest(unitId, name, firstDay, lastDay, constraints) (RosterRestPort.java:79-80)
  create: { id: 'create', label: 'act.create', path: '/rosters/periods', fields: [f.text('unitId', undefined, true), f.text('name'), f.date('firstDay', 'fld.firstDay', true), f.date('lastDay', 'fld.lastDay', true), f.json('constraints')] },""")
r("""{ id: 'member', label: 'act.setMember', method: 'PUT', path: '/rosters/periods/{periodId}/members/{personId}', fields: [f.text('personId', undefined, true), f.json('skills'), f.num('maxShifts')] },""",
  """// M-051: MemberRequest(seatId, skills, rotationId, rotationOffset, attributes) (RosterRestPort.java:83-84); personId is the path
    { id: 'member', label: 'act.setMember', method: 'PUT', path: '/rosters/periods/{periodId}/members/{personId}', fields: [f.path(f.text('personId', undefined, true)), f.text('seatId'), f.json('skills'), f.text('rotationId'), f.num('rotationOffset', 'fld.rotationOffset')] },""")
r("""{ id: 'demand', label: 'act.addDemand', path: '/rosters/periods/{periodId}/demand', fields: [f.date('date', undefined, true), f.text('shiftId', undefined, true), f.num('count', undefined, true)] },""",
  """// M-051: DemandRequest(shiftId, skill, weekdays, dutyDay, requiredCount, dutySeatId, locationId) (RosterRestPort.java:87-88)
    { id: 'demand', label: 'act.addDemand', path: '/rosters/periods/{periodId}/demand', fields: [f.text('shiftId', undefined, true), f.date('dutyDay', 'fld.dutyDay'), f.json('weekdays', 'fld.weekdays'), f.num('requiredCount', 'fld.requiredCount', true), f.text('skill', 'fld.skill'), f.text('dutySeatId', 'fld.dutySeatId'), f.text('locationId')] },""")
r("""{ id: 'constraints', label: 'act.constraints', method: 'PUT', path: '/rosters/periods/{periodId}/constraints', fields: [f.num('version', undefined, true, ), f.json('constraints', undefined, true)] },""",
  """// M-051: ?version plus the constraints map itself (RosterController.java:116-118), keys read by RosterConstraints.from (RosterModel.java:140-155)
    { id: 'constraints', label: 'act.constraints', method: 'PUT', path: '/rosters/periods/{periodId}/constraints', fields: [f.q(f.num('version', undefined, true)), f.num('minRestMinutes', 'fld.minRestMinutes'), f.num('maxMinutesPerDay', 'fld.maxMinutesPerDay'), f.num('maxMinutesPerWeek', 'fld.maxMinutesPerWeek'), f.num('weeklyOffDays', 'fld.weeklyOffDays'), f.num('maxConsecutiveDays', 'fld.maxConsecutiveDays'), f.num('maxConsecutiveNights', 'fld.maxConsecutiveNights'), f.bool('requireSkill', 'fld.requireSkill'), f.text('constraintsDecision', 'fld.constraintsDecision'), f.text('approvalProcessKey', 'fld.approvalProcessKey'), f.text('swapApprovalProcessKey', 'fld.swapApprovalProcessKey')] },""")
r("""create: { id: 'request', label: 'act.requestSwap', path: '/rosters/swaps', fields: [f.text('assignmentId', undefined, true), f.text('toPersonId', undefined, true)], reason: 'body' },""",
  """// M-051: SwapCreateRequest(assignmentId, counterpartId, counterpartAssignmentId, reason) (RosterRestPort.java:101)
  create: { id: 'request', label: 'act.requestSwap', path: '/rosters/swaps', fields: [f.text('assignmentId', undefined, true), f.text('counterpartId', 'fld.counterpartId', true), f.text('counterpartAssignmentId', 'fld.counterpartAssignmentId')], reason: 'body' },""")
r("""{ id: 'decide', label: 'act.decide', path: '/rosters/swaps/{swapId}/decision', fields: [f.select('decision', ['APPROVE', 'REJECT'], undefined, true)], reason: 'body' },""",
  """// M-051: DecisionRequest(version, approved, reason) (RosterRestPort.java:98)
    { id: 'decide', label: 'act.decide', path: '/rosters/swaps/{swapId}/decision', fields: [f.bool('approved', 'fld.approved'), f.num('version')], defaults: (r) => ({ version: r?.version as number }), reason: 'body' },""")

for old, new in R:
    sub(p, old, new)
print('done', len(R))
