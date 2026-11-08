<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="rti_defs">
  <!-- GP-35 / D-RTI (RTI Act 2005): s.7(1) reply within 30 days of receipt, or 48 hours where the information concerns
       the life or liberty of a person; no reply in time = deemed refusal (s.7(2)), flagged to the PIO's superior and
       the First Appellate Authority. s.6(3) transfer to another public authority (that authority's clock starts on
       its receipt: the product starts the request there). s.19(1) first appeal within 30 days of the decision or the
       deemed refusal; the FAA decides within 30 days, extendable to 45 with reasons. -->
  <message id="rti_msgAppeal" name="firstAppealFiled"/>
  <process id="rtiRequest" name="RTI request" isExecutable="true">
    <startEvent id="rti_start" name="Request received"/>
    <sequenceFlow id="rti_f1" sourceRef="rti_start" targetRef="rti_pio"/>
    <userTask id="rti_pio" name="PIO: supply / reject / transfer">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(pio)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <boundaryEvent id="rti_deadline" name="s.7(1) time limit" attachedToRef="rti_pio" cancelActivity="false">
      <timerEventDefinition id="rti_t1"><timeDuration xsi:type="tFormalExpression">${lifeLiberty ? 'PT48H' : 'P30D'}</timeDuration></timerEventDefinition>
    </boundaryEvent>
    <sequenceFlow id="rti_f2" sourceRef="rti_deadline" targetRef="rti_deemed"/>
    <userTask id="rti_deemed" name="Deemed refusal: note and escalate">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(pio-superior)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="rti_f3" sourceRef="rti_deemed" targetRef="rti_endDeemed"/>
    <endEvent id="rti_endDeemed"/>
    <sequenceFlow id="rti_f4" sourceRef="rti_pio" targetRef="rti_gwDecision"/>
    <exclusiveGateway id="rti_gwDecision" default="rti_f6"/>
    <sequenceFlow id="rti_f5" sourceRef="rti_gwDecision" targetRef="rti_endTransferred">
      <conditionExpression xsi:type="tFormalExpression">${pioDecision == 'TRANSFER'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="rti_f6" sourceRef="rti_gwDecision" targetRef="rti_gwAppeal"/>
    <eventBasedGateway id="rti_gwAppeal"/>
    <sequenceFlow id="rti_f7" sourceRef="rti_gwAppeal" targetRef="rti_appealFiled"/>
    <sequenceFlow id="rti_f8" sourceRef="rti_gwAppeal" targetRef="rti_appealWindow"/>
    <intermediateCatchEvent id="rti_appealFiled" name="First appeal filed">
      <messageEventDefinition id="rti_med" messageRef="rti_msgAppeal"/>
    </intermediateCatchEvent>
    <intermediateCatchEvent id="rti_appealWindow" name="30-day appeal window">
      <timerEventDefinition id="rti_t2"><timeDuration xsi:type="tFormalExpression">P30D</timeDuration></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="rti_f9" sourceRef="rti_appealFiled" targetRef="rti_endAppealed"/>
    <sequenceFlow id="rti_f10" sourceRef="rti_appealWindow" targetRef="rti_endClosed"/>
    <endEvent id="rti_endTransferred"/>
    <endEvent id="rti_endAppealed"/>
    <endEvent id="rti_endClosed"/>
  </process>
  <process id="rtiFirstAppeal" name="RTI first appeal" isExecutable="true">
    <startEvent id="rfa_start"/>
    <sequenceFlow id="rfa_f1" sourceRef="rfa_start" targetRef="rfa_decide"/>
    <userTask id="rfa_decide" name="FAA: decide first appeal">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(faa)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <boundaryEvent id="rfa_due" name="30 days" attachedToRef="rfa_decide" cancelActivity="false">
      <timerEventDefinition id="rfa_t1"><timeDuration xsi:type="tFormalExpression">P30D</timeDuration></timerEventDefinition>
    </boundaryEvent>
    <sequenceFlow id="rfa_f2" sourceRef="rfa_due" targetRef="rfa_recordReasons"/>
    <userTask id="rfa_recordReasons" name="FAA: record reasons for extension (max 45 days)">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(faa)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="rfa_f3" sourceRef="rfa_recordReasons" targetRef="rfa_endReasons"/>
    <endEvent id="rfa_endReasons"/>
    <sequenceFlow id="rfa_f4" sourceRef="rfa_decide" targetRef="rfa_end"/>
    <endEvent id="rfa_end"/>
  </process>
</definitions>
