# SPDX-License-Identifier: Apache-2.0
# Routes of sangam-programme, derived from the sangam-rest controllers in org.sangam.rest.modules.programme (WS-REST final integration wave).
# Executed inside gen_openapi.py: schema(), op(), q(), pp() and TAGS come from there.

TAGS.append(('Programmes', 'Programme monitoring: programmes, targets, milestones, periodic returns, roll-up, progress, variance alerts, dashboards (N15). sangam-programme'))

schema('PgPublished', 'Published (Java IndicatorRegistry.Published).',
       'version:int contentHash:s changed:bool')
schema('PgGeneratePeriodsRequest', 'GeneratePeriodsRequest (Java ProgrammeController.GeneratePeriodsRequest).',
       'frequency:s fiscalStartYear:int dueOffsetDays:int')
schema('PgPeriod', 'Period (Java PeriodService.Period).',
       'id:s frequency:s startsOn:date endsOn:date label:s fiscalYear:s seqInYear:int dueOn:date state:s stateReason:s changedBy:s changedAt:dt')
schema('PgPeriodStateRequest', 'PeriodStateRequest (Java ProgrammeController.PeriodStateRequest).',
       'state:s reason:s')
schema('PgProgrammeInput', 'ProgrammeInput (Java ProgrammeService.ProgrammeInput).',
       'parentId:s kind:s code:s name:s ownerOrg:s startsOn:date endsOn:date budget:s currency:s indicators:list:s attributes:obj')
schema('PgProgramme', 'Programme (Java ProgrammeService.Programme).',
       'id:s parentId:s kind:s code:s name:s ownerOrg:s startsOn:date endsOn:date status:s budget:s currency:s indicators:list:s attributes:obj rowVersion:int createdBy:s createdAt:dt updatedAt:dt')
schema('PgUpdateProgrammeRequest', 'UpdateProgrammeRequest (Java ProgrammeController.UpdateProgrammeRequest).',
       'expectedVersion:int programme:PgProgrammeInput')
schema('PgStatusRequest', 'StatusRequest (Java ProgrammeController.StatusRequest).',
       'status:s')
schema('PgNode', 'Node (Java ProgrammeService.Node).',
       'programme:PgProgramme children:list:PgNode')
schema('PgSetTargetRequest', 'SetTargetRequest (Java ProgrammeController.SetTargetRequest).',
       'indicatorKey:s periodId:s orgUnit:s value:s')
schema('PgTarget', 'Target (Java TargetService.Target).',
       'id:s programmeId:s indicatorKey:s periodId:s orgUnit:s value:s parentTargetId:s state:s version:int proposedBy:s proposedAt:dt approvedBy:s approvedAt:dt reason:s rowVersion:int createdAt:dt')
schema('PgReasonBody', 'ReasonBody (Java ProgrammeController.ReasonBody).',
       'reason:s')
schema('PgReviseTargetRequest', 'ReviseTargetRequest (Java ProgrammeController.ReviseTargetRequest).',
       'value:s reason:s')
schema('PgAllocationRequest', 'AllocationRequest (Java ProgrammeRestPort.AllocationRequest).',
       'explicit:obj weights:obj allowOverAllocation:bool')
schema('PgMilestoneRequest', 'MilestoneRequest (Java ProgrammeController.MilestoneRequest).',
       'orgUnit:s name:s seq:int plannedStart:date plannedEnd:date weight:s dependsOn:list:s')
schema('PgMilestone', 'Milestone (Java MilestoneService.Milestone).',
       'id:s programmeId:s orgUnit:s name:s seq:int plannedStart:date plannedEnd:date actualStart:date actualEnd:date weight:s percentComplete:s status:s dependsOn:list:any evidence:list:any delayDays:int rowVersion:int updatedBy:s updatedAt:dt')
schema('PgMilestoneServiceProgress', 'Progress (Java MilestoneService.Progress).',
       'milestoneId:s expectedVersion:int percentComplete:s actualStart:date actualEnd:date addEvidence:list:any drop:bool reason:s')
schema('PgCampaignRequest', 'CampaignRequest (Java ProgrammeController.CampaignRequest).',
       'returnKey:s periodId:s rootNode:s programmeId:s')
schema('PgCampaignResult', 'CampaignResult (Java ReturnService.CampaignResult).',
       'returnKey:s periodId:s offices:int created:int processesStarted:int')
schema('PgReturnRow', 'ReturnRow (Java ReturnService.ReturnRow).',
       'id:s returnKey:s returnVersion:int periodId:s orgUnit:s programmeId:s state:s approvalLevel:int dueOn:date remindersSent:int lastReminderAt:dt submittedBy:s submittedAt:dt validatedAt:dt acceptedBy:s acceptedAt:dt returnedBy:s returnedReason:s frozenAt:dt reopenedReason:s late:bool processInstanceId:s contentHash:s rowVersion:int createdAt:dt updatedAt:dt')
schema('PgReturnDetail', 'ReturnDetail (Java ProgrammeRestPort.ReturnDetail).',
       'returnRow:PgReturnRow values:list:PgValue findings:list:PgStoredFinding events:list:PgEvent')
schema('PgValue', 'Value (Java ReturnService.Value).',
       'indicatorKey:s numValue:s textValue:s remark:s updatedBy:s updatedAt:dt')
schema('PgStoredFinding', 'StoredFinding (Java ReturnService.StoredFinding).',
       'seq:int indicatorKey:s severity:s code:s message:s acknowledgedBy:s acknowledgedAt:dt')
schema('PgEvent', 'Event (Java ReturnService.Event).',
       'seq:int fromState:s toState:s approvalLevel:int actor:s reason:s at:dt')
schema('PgReturnValuesRequest', 'ReturnValuesRequest (Java ProgrammeController.ReturnValuesRequest).',
       'expectedVersion:int values:obj remarks:smap')
schema('PgVersionBody', 'VersionBody (Java ProgrammeController.VersionBody).',
       'expectedVersion:int')
schema('PgOutcome', 'Outcome (Java ReturnService.Outcome).',
       'returnRow:PgReturnRow findings:list:PgFinding advanced:bool')
schema('PgFinding', 'Finding (Java ValidationEngine.Finding).',
       'indicatorKey:s severity:s code:s message:s')
schema('PgAchievementRequest', 'AchievementRequest (Java ProgrammeController.AchievementRequest).',
       'indicatorKey:s periodId:s orgUnit:s value:s')
schema('PgAchievement', 'Achievement (Java RollupService.Achievement).',
       'programmeId:s indicatorKey:s periodId:s orgUnit:s periodValue:s cumulativeValue:s weightValue:s source:s sourceRef:s contributors:int computedAt:dt')
schema('PgRollupRequest', 'RollupRequest (Java ProgrammeController.RollupRequest).',
       'periodId:s rootNode:s')
schema('PgRollupResult', 'RollupResult (Java RollupService.RollupResult).',
       'nodes:int rows:int')
schema('PgFinancialRequest', 'FinancialRequest (Java ProgrammeRestPort.FinancialRequest).',
       'programmeId:s periodId:s orgUnit:s head:s allocated:s released:s committed:s expended:s currency:s')
schema('PgFinancial', 'Financial (Java ProgressService.Financial).',
       'programmeId:s periodId:s orgUnit:s head:s allocated:s released:s committed:s expended:s currency:s source:s updatedBy:s updatedAt:dt')
schema('PgImportRequest', 'ImportRequest (Java ProgrammeController.ImportRequest).',
       'periodId:s orgUnit:s')
schema('PgImportResult', 'ImportResult (Java ProgrammeController.ImportResult).',
       'imported:int')
schema('PgProgressServiceProgress', 'Progress (Java ProgressService.Progress).',
       'programmeId:s periodId:s orgUnit:s physicalBasis:s physicalPct:s plannedPhysicalPct:s financialPct:s releasedPct:s utilisationOfReleasedPct:s gapPct:s allocated:s released:s expended:s')
schema('PgVarianceRequest', 'VarianceRequest (Java ProgrammeController.VarianceRequest).',
       'periodId:s asOf:date')
schema('PgAlert', 'Alert (Java VarianceService.Alert).',
       'id:s kind:s programmeId:s indicatorKey:s periodId:s orgUnit:s subjectRef:s severity:s message:s measure:s threshold:s state:s createdAt:dt acknowledgedBy:s acknowledgedAt:dt')
schema('PgRefreshResult', 'RefreshResult (Java ProgrammeController.RefreshResult).',
       'snapshots:int')
schema('PgSnapshot', 'Snapshot (Java DashboardFeed.Snapshot).',
       'programmeId:s periodId:s orgUnit:s physicalPct:s financialPct:s gapPct:s returnsDue:int returnsAccepted:int returnsOverdue:int openAlerts:int payload:obj contentHash:s computedAt:dt')

op('put', '/programme-config', 'programmePublishConfig', 'Programmes', 'Publish the programme configuration (indicators, returns, kinds)', 'Publish the programme configuration (indicators, returns, kinds).', ['N15', 'VI.4', 'R41', 'R42'],
   body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, 'PgPublished'), cmd='programme.publish-config', perm='programme.admin')
op('get', '/programme-config/history', 'programmeConfigHistory', 'Programmes', 'Published configuration versions', 'Published configuration versions.', ['N15', 'VI.4', 'R41', 'R42'],
   resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm='programme.read')
op('post', '/programme-periods/generate', 'programmeGeneratePeriods', 'Programmes', 'Generate the reporting periods of a fiscal year', 'Generate the reporting periods of a fiscal year.', ['N15', 'VI.4', 'R41', 'R42'],
   body='PgGeneratePeriodsRequest', resp=(200, 'array:PgPeriod'), cmd='programme.generate-periods', perm='programme.admin')
op('get', '/programme-periods', 'programmeListPeriods', 'Programmes', 'Reporting periods', 'Reporting periods.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[q('frequency', 's'), q('fiscalYear', 's')], resp=(200, 'array:PgPeriod'), perm='programme.read')
op('post', '/programme-periods/{id}/state', 'programmeChangePeriodState', 'Programmes', 'Open, close or lock a reporting period', 'Open, close or lock a reporting period.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgPeriodStateRequest', resp=(200, 'PgPeriod'), cmd='programme.change-period-state', perm='programme.admin')
op('post', '/programmes', 'programmeCreateProgramme', 'Programmes', 'Create a programme (or a component under a parent)', 'Create a programme (or a component under a parent).', ['N15', 'VI.4', 'R41', 'R42'],
   body='PgProgrammeInput', resp=(201, 'PgProgramme'), cmd='programme.create-programme', perm='programme.write', created=True)
op('get', '/programmes', 'programmeListProgrammes', 'Programmes', 'Programmes visible to the caller', 'Programmes visible to the caller.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[q('kind', 's'), q('parentId', 's'), q('status', 's')], resp=(200, 'array:PgProgramme'), perm='programme.read', list_=True)
op('get', '/programmes/{id}', 'programmeGetProgramme', 'Programmes', 'A programme', 'A programme.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgProgramme'), perm='programme.read')
op('patch', '/programmes/{id}', 'programmeUpdateProgramme', 'Programmes', 'Update a programme (optimistic version)', 'Update a programme (optimistic version).', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgUpdateProgrammeRequest', resp=(200, 'PgProgramme'), cmd='programme.update-programme', perm='programme.write')
op('post', '/programmes/{id}/status', 'programmeChangeStatus', 'Programmes', 'Change the programme status', 'Change the programme status.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgStatusRequest', resp=(200, 'PgProgramme'), cmd='programme.change-status', perm='programme.approve')
op('get', '/programmes/{id}/tree', 'programmeTree', 'Programmes', 'The programme tree (components)', 'The programme tree (components).', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgNode'), perm='programme.read')
op('put', '/programmes/{id}/targets', 'programmeSetTarget', 'Programmes', 'Set a target for an indicator, period and org unit', 'Set a target for an indicator, period and org unit.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgSetTargetRequest', resp=(200, 'PgTarget'), cmd='programme.set-target', perm='programme.write')
op('get', '/programmes/{id}/targets', 'programmeListTargets', 'Programmes', 'Targets of a programme', 'Targets of a programme.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('periodId', 's'), q('orgUnit', 's'), q('approvedOnly', 'bool')], resp=(200, 'array:PgTarget'), perm='programme.read')
op('post', '/programme-targets/{id}/propose', 'programmeProposeTarget', 'Programmes', 'Propose a target for approval', 'Propose a target for approval.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgTarget'), cmd='programme.propose-target', perm='programme.write')
op('post', '/programme-targets/{id}/approve', 'programmeApproveTarget', 'Programmes', 'Approve a target', 'Approve a target.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgTarget'), cmd='programme.approve-target', perm='programme.approve')
op('post', '/programme-targets/{id}/reject', 'programmeRejectTarget', 'Programmes', 'Reject a target with a reason', 'Reject a target with a reason.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgReasonBody', resp=(200, 'PgTarget'), cmd='programme.reject-target', perm='programme.approve')
op('post', '/programme-targets/{id}/revise', 'programmeReviseTarget', 'Programmes', 'Revise an approved target with a reason', 'Revise an approved target with a reason.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgReviseTargetRequest', resp=(200, 'PgTarget'), cmd='programme.revise-target', perm='programme.write')
op('post', '/programme-targets/{id}/allocate', 'programmeAllocateTarget', 'Programmes', 'Allocate a target to child org units (explicit amounts or weights)', 'Allocate a target to child org units (explicit amounts or weights).', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgAllocationRequest', resp=(200, 'array:PgTarget'), cmd='programme.allocate-target', perm='programme.write')
op('post', '/programmes/{id}/milestones', 'programmePlanMilestone', 'Programmes', 'Plan a milestone', 'Plan a milestone.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgMilestoneRequest', resp=(200, 'PgMilestone'), cmd='programme.plan-milestone', perm='programme.write')
op('patch', '/programme-milestones/{id}', 'programmeUpdateMilestone', 'Programmes', "Record milestone progress (optimistic version; the body's milestoneId must equal the path id)", "Record milestone progress (optimistic version; the body's milestoneId must equal the path id).", ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgMilestoneServiceProgress', resp=(200, 'PgMilestone'), cmd='programme.update-milestone', perm='programme.write')
op('get', '/programmes/{id}/milestones', 'programmeListMilestones', 'Programmes', 'Milestones of a programme', 'Milestones of a programme.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('orgUnit', 's')], resp=(200, 'array:PgMilestone'), perm='programme.read')
op('post', '/programme-returns/campaigns', 'programmeStartCampaign', 'Programmes', 'Start a return campaign: one return (and process) per office under the root node', 'Start a return campaign: one return (and process) per office under the root node.', ['N15', 'VI.4', 'R41', 'R42'],
   body='PgCampaignRequest', resp=(200, 'PgCampaignResult'), cmd='programme.start-campaign', perm='programme.write')
op('get', '/programme-returns', 'programmeListReturns', 'Programmes', 'Returns visible to the caller', 'Returns visible to the caller.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[q('returnKey', 's'), q('periodId', 's'), q('orgUnit', 's'), q('state', 's')], resp=(200, 'array:PgReturnRow'), perm='programme.read', list_=True)
op('get', '/programme-returns/defaulters', 'programmeDefaulters', 'Programmes', 'Offices that have not submitted a return', 'Offices that have not submitted a return.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[q('returnKey', 's', required=True), q('periodId', 's', required=True), q('asOf', 'date'), q('limit', 'int')], resp=(200, 'array:PgReturnRow'), perm='programme.read')
op('get', '/programme-returns/{id}', 'programmeGetReturn', 'Programmes', 'A return with values, findings and events', 'A return with values, findings and events.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgReturnDetail'), perm='programme.read')
op('put', '/programme-returns/{id}/values', 'programmeSaveReturnDraft', 'Programmes', 'Save the return draft values (optimistic version)', 'Save the return draft values (optimistic version).', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgReturnValuesRequest', resp=(200, 'PgReturnRow'), cmd='programme.save-return-draft', perm='programme.write')
op('post', '/programme-returns/{id}/submit', 'programmeSubmitReturn', 'Programmes', 'Submit the return', 'Submit the return.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgVersionBody', resp=(200, 'PgOutcome'), cmd='programme.submit-return', perm='programme.write')
op('post', '/programme-returns/{id}/validate', 'programmeValidateReturn', 'Programmes', 'Validate the return (rules and DMN)', 'Validate the return (rules and DMN).', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgOutcome'), perm='programme.approve')
op('post', '/programme-returns/{id}/accept', 'programmeAcceptReturn', 'Programmes', 'Accept the return', 'Accept the return.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgReturnRow'), cmd='programme.accept-return', perm='programme.approve')
op('post', '/programme-returns/{id}/send-back', 'programmeSendBackReturn', 'Programmes', 'Send the return back with a reason', 'Send the return back with a reason.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgReasonBody', resp=(200, 'PgReturnRow'), cmd='programme.send-back-return', perm='programme.approve')
op('post', '/programme-returns/{id}/freeze', 'programmeFreezeReturn', 'Programmes', 'Freeze the return', 'Freeze the return.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgReturnRow'), cmd='programme.freeze-return', perm='programme.approve')
op('post', '/programme-returns/{id}/reopen', 'programmeReopenReturn', 'Programmes', 'Reopen a frozen return with a reason', 'Reopen a frozen return with a reason.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgReasonBody', resp=(200, 'PgReturnRow'), cmd='programme.reopen-return', perm='programme.admin')
op('post', '/programme-returns/{id}/findings/{seq}/ack', 'programmeAcknowledgeFinding', 'Programmes', 'Acknowledge a validation finding', 'Acknowledge a validation finding.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('seq')], resp=(204, None), cmd='programme.acknowledge-finding', perm='programme.approve')
op('post', '/programmes/{id}/achievements', 'programmeRecordAchievement', 'Programmes', 'Record a manual achievement', 'Record a manual achievement.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgAchievementRequest', resp=(200, 'PgAchievement'), cmd='programme.record-achievement', perm='programme.write')
op('post', '/programmes/{id}/rollup', 'programmeRollup', 'Programmes', 'Roll achievements up the hierarchy', 'Roll achievements up the hierarchy.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgRollupRequest', resp=(200, 'PgRollupResult'), cmd='programme.rollup', perm='programme.write')
op('get', '/programmes/{id}/achievements', 'programmeAchievements', 'Programmes', 'Achievements of a programme', 'Achievements of a programme.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('periodId', 's'), q('orgUnit', 's')], resp=(200, 'array:PgAchievement'), perm='programme.read')
op('get', '/programmes/{id}/achievements/children', 'programmeAchievementChildren', 'Programmes', 'Achievements of the child org units', 'Achievements of the child org units.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('indicatorKey', 's', required=True), q('periodId', 's', required=True), q('orgUnit', 's', required=True)], resp=(200, 'array:PgAchievement'), perm='programme.read')
op('get', '/programmes/{id}/achievements/series', 'programmeSeries', 'Programmes', 'Achievement series over a fiscal year', 'Achievement series over a fiscal year.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('indicatorKey', 's', required=True), q('orgUnit', 's', required=True), q('fiscalYear', 's', required=True)], resp=(200, 'array:PgAchievement'), perm='programme.read')
op('put', '/programmes/{id}/financial', 'programmeRecordFinancial', 'Programmes', 'Record financial progress for a head', 'Record financial progress for a head.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgFinancialRequest', resp=(200, 'PgFinancial'), cmd='programme.record-financial', perm='programme.write')
op('post', '/programmes/{id}/financial/import', 'programmeImportFinancial', 'Programmes', 'Import financial actuals from the ledger', 'Import financial actuals from the ledger.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgImportRequest', resp=(200, 'PgImportResult'), cmd='programme.import-financial', perm='programme.write')
op('get', '/programmes/{id}/progress', 'programmeProgress', 'Programmes', 'Physical and financial progress', 'Physical and financial progress.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('periodId', 's', required=True), q('orgUnit', 's', required=True)], resp=(200, 'PgProgressServiceProgress'), perm='programme.read')
op('get', '/programmes/{id}/progress/children', 'programmeChildProgress', 'Programmes', 'Progress of the child org units', 'Progress of the child org units.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('periodId', 's', required=True), q('orgUnit', 's', required=True)], resp=(200, 'array:PgProgressServiceProgress'), perm='programme.read')
op('post', '/programmes/{id}/variance-check', 'programmeCheckVariance', 'Programmes', 'Run the variance check (behind-target and delay alerts)', 'Run the variance check (behind-target and delay alerts).', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgVarianceRequest', resp=(200, 'array:PgAlert'), cmd='programme.check-variance', perm='programme.write')
op('get', '/programme-alerts', 'programmeOpenAlerts', 'Programmes', 'Open variance alerts', 'Open variance alerts.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[q('programmeId', 's'), q('kind', 's'), q('limit', 'int')], resp=(200, 'array:PgAlert'), perm='programme.read')
op('post', '/programme-alerts/{id}/ack', 'programmeAcknowledgeAlert', 'Programmes', 'Acknowledge an alert', 'Acknowledge an alert.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'PgAlert'), cmd='programme.acknowledge-alert', perm='programme.write')
op('post', '/programmes/{id}/dashboard/refresh', 'programmeRefreshDashboard', 'Programmes', 'Refresh the dashboard snapshots (C14 feed)', 'Refresh the dashboard snapshots (C14 feed).', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='PgRollupRequest', resp=(200, 'PgRefreshResult'), cmd='programme.refresh-dashboard', perm='programme.write')
op('get', '/programmes/{id}/dashboard', 'programmeDashboard', 'Programmes', 'The dashboard snapshot of an org unit', 'The dashboard snapshot of an org unit.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('periodId', 's', required=True), q('orgUnit', 's', required=True)], resp=(200, 'PgSnapshot'), perm='programme.read')
op('get', '/programmes/{id}/dashboard/children', 'programmeDashboardChildren', 'Programmes', 'Dashboard snapshots of the child org units', 'Dashboard snapshots of the child org units.', ['N15', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('periodId', 's', required=True), q('orgUnit', 's', required=True)], resp=(200, 'array:PgSnapshot'), perm='programme.read')
