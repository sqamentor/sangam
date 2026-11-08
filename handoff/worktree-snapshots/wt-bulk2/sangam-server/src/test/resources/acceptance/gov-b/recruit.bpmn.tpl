<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e17b" id="rec_defs">
  <!-- D-RECRUIT: notification -> applications with fee callback -> scrutiny -> exam -> result with reservation (DMN
       COLLECT pools + product merit list) -> DV with waitlist promotion on decline -> appointment.
       recruitNotice: one per advertisement; recruitApplication: one per candidate (business key = application no);
       recruitPromote: one per vacated seat (signal start). -->
  <message id="rec_msgFee" name="feePaid"/>
  <message id="rec_msgScore" name="examScore"/>
  <message id="rec_msgResult" name="resultDeclared"/>
  <message id="rec_msgPromoted" name="waitlistPromoted"/>
  <signal id="rec_sigVacated" name="recruitSeatVacated"/>

  <process id="recruitNotice" name="Recruitment notification" isExecutable="true">
    <startEvent id="rn_start"/>
    <sequenceFlow id="rn_f1" sourceRef="rn_start" targetRef="rn_approve"/>
    <userTask id="rn_approve" name="Approve and publish notification">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(rec-board)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="rn_f2" sourceRef="rn_approve" targetRef="rn_close"/>
    <intermediateCatchEvent id="rn_close" name="Application window closes">
      <timerEventDefinition id="rn_t"><timeDate xsi:type="tFormalExpression">${applicationsCloseAt}</timeDate></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="rn_f3" sourceRef="rn_close" targetRef="rn_exam"/>
    <userTask id="rn_exam" name="Conduct examination and upload scores">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(rec-exam)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="rn_f4" sourceRef="rn_exam" targetRef="rn_result"/>
    <userTask id="rn_result" name="Approve merit list and declare result">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(rec-board)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="rn_f5" sourceRef="rn_result" targetRef="rn_end"/>
    <endEvent id="rn_end"/>
  </process>

  <process id="recruitApplication" name="Recruitment application" isExecutable="true">
    <startEvent id="ra_start" name="Application submitted"/>
    <sequenceFlow id="ra_f1" sourceRef="ra_start" targetRef="ra_gwFee"/>
    <eventBasedGateway id="ra_gwFee"/>
    <sequenceFlow id="ra_f2" sourceRef="ra_gwFee" targetRef="ra_fee"/>
    <sequenceFlow id="ra_f3" sourceRef="ra_gwFee" targetRef="ra_feeLapse"/>
    <intermediateCatchEvent id="ra_fee" name="Fee callback from payment gateway">
      <messageEventDefinition id="ra_med1" messageRef="rec_msgFee"/>
    </intermediateCatchEvent>
    <intermediateCatchEvent id="ra_feeLapse" name="7 days without fee">
      <timerEventDefinition id="ra_t1"><timeDuration xsi:type="tFormalExpression">P7D</timeDuration></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="ra_f4" sourceRef="ra_feeLapse" targetRef="ra_endLapsed"/>
    <sequenceFlow id="ra_f5" sourceRef="ra_fee" targetRef="ra_scrutiny"/>
    <userTask id="ra_scrutiny" name="Scrutiny of application">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(rec-scrutiny)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="ra_f6" sourceRef="ra_scrutiny" targetRef="ra_gwEligible"/>
    <exclusiveGateway id="ra_gwEligible" default="ra_f8"/>
    <sequenceFlow id="ra_f7" sourceRef="ra_gwEligible" targetRef="ra_score">
      <conditionExpression xsi:type="tFormalExpression">${eligible == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="ra_f8" sourceRef="ra_gwEligible" targetRef="ra_endRejected"/>
    <intermediateCatchEvent id="ra_score" name="Exam score">
      <messageEventDefinition id="ra_med2" messageRef="rec_msgScore"/>
    </intermediateCatchEvent>
    <sequenceFlow id="ra_f9" sourceRef="ra_score" targetRef="ra_pools"/>
    {{DMNCOLLECT ra_pools|Reservation pools|reservationPools}}
    <sequenceFlow id="ra_f10" sourceRef="ra_pools" targetRef="ra_result"/>
    <intermediateCatchEvent id="ra_result" name="Result declared">
      <messageEventDefinition id="ra_med3" messageRef="rec_msgResult"/>
    </intermediateCatchEvent>
    <sequenceFlow id="ra_f11" sourceRef="ra_result" targetRef="ra_gwOutcome"/>
    <exclusiveGateway id="ra_gwOutcome" default="ra_f14"/>
    <sequenceFlow id="ra_f12" sourceRef="ra_gwOutcome" targetRef="ra_dv">
      <conditionExpression xsi:type="tFormalExpression">${outcome == 'SELECTED'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="ra_f13" sourceRef="ra_gwOutcome" targetRef="ra_gwWait">
      <conditionExpression xsi:type="tFormalExpression">${outcome == 'WAITLIST'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="ra_f14" sourceRef="ra_gwOutcome" targetRef="ra_endNotSelected"/>
    <eventBasedGateway id="ra_gwWait"/>
    <sequenceFlow id="ra_f15" sourceRef="ra_gwWait" targetRef="ra_promoted"/>
    <sequenceFlow id="ra_f16" sourceRef="ra_gwWait" targetRef="ra_waitExpired"/>
    <intermediateCatchEvent id="ra_promoted" name="Promoted from waitlist">
      <messageEventDefinition id="ra_med4" messageRef="rec_msgPromoted"/>
    </intermediateCatchEvent>
    <intermediateCatchEvent id="ra_waitExpired" name="Waitlist validity (1 year)">
      <timerEventDefinition id="ra_t2"><timeDuration xsi:type="tFormalExpression">P365D</timeDuration></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="ra_f17" sourceRef="ra_promoted" targetRef="ra_dv"/>
    <sequenceFlow id="ra_f18" sourceRef="ra_waitExpired" targetRef="ra_endWaitExpired"/>
    <userTask id="ra_dv" name="Document verification">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(rec-dv)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="ra_f19" sourceRef="ra_dv" targetRef="ra_gwDv"/>
    <exclusiveGateway id="ra_gwDv" default="ra_f21"/>
    <sequenceFlow id="ra_f20" sourceRef="ra_gwDv" targetRef="ra_offer">
      <conditionExpression xsi:type="tFormalExpression">${dvOk == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="ra_f21" sourceRef="ra_gwDv" targetRef="ra_vacated"/>
    <userTask id="ra_offer" name="Candidate: accept offer of appointment"/>
    <sequenceFlow id="ra_f22" sourceRef="ra_offer" targetRef="ra_gwAccept"/>
    <exclusiveGateway id="ra_gwAccept" default="ra_f24"/>
    <sequenceFlow id="ra_f23" sourceRef="ra_gwAccept" targetRef="ra_appoint">
      <conditionExpression xsi:type="tFormalExpression">${accepted == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="ra_f24" sourceRef="ra_gwAccept" targetRef="ra_vacated"/>
    <intermediateThrowEvent id="ra_vacated" name="Seat vacated">
      <signalEventDefinition id="ra_sed" signalRef="rec_sigVacated"/>
    </intermediateThrowEvent>
    <sequenceFlow id="ra_f25" sourceRef="ra_vacated" targetRef="ra_endDeclined"/>
    <userTask id="ra_appoint" name="Issue appointment order">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(rec-appointing)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="ra_f26" sourceRef="ra_appoint" targetRef="ra_endAppointed"/>
    <endEvent id="ra_endLapsed"/>
    <endEvent id="ra_endRejected"/>
    <endEvent id="ra_endNotSelected"/>
    <endEvent id="ra_endWaitExpired"/>
    <endEvent id="ra_endDeclined"/>
    <endEvent id="ra_endAppointed"/>
  </process>

  <process id="recruitPromote" name="Waitlist promotion" isExecutable="true">
    <startEvent id="rp_start" name="Seat vacated">
      <signalEventDefinition id="rp_sed" signalRef="rec_sigVacated"/>
    </startEvent>
    <sequenceFlow id="rp_f1" sourceRef="rp_start" targetRef="rp_promote"/>
    <serviceTask id="rp_promote" name="Promote next waitlisted candidate" {{EXT recruit-promote}}/>
    <sequenceFlow id="rp_f2" sourceRef="rp_promote" targetRef="rp_end"/>
    <endEvent id="rp_end"/>
  </process>
</definitions>
