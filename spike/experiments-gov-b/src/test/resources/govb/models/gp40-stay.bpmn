<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp40_defs">
  <!-- GP-40: a show-cause proceeding with a 30-day SLA timer and a message for the reply. A court stay suspends the
       whole instance; the stay is vacated later and the SLA deadline is moved by the stay period. -->
  <message id="gp40_msgReply" name="replyReceived"/>
  <process id="gp40Proceeding" name="Show-cause proceeding" isExecutable="true">
    <startEvent id="gp40_start"/>
    <sequenceFlow id="gp40_f1" sourceRef="gp40_start" targetRef="gp40_fork"/>
    <parallelGateway id="gp40_fork"/>
    <sequenceFlow id="gp40_f2" sourceRef="gp40_fork" targetRef="gp40_hearing"/>
    <sequenceFlow id="gp40_f3" sourceRef="gp40_fork" targetRef="gp40_reply"/>
    <userTask id="gp40_hearing" name="Conduct hearing">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(adjudicating-officer)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <boundaryEvent id="gp40_sla" attachedToRef="gp40_hearing" cancelActivity="false">
      <timerEventDefinition id="gp40_t"><timeDuration xsi:type="tFormalExpression">P30D</timeDuration></timerEventDefinition>
    </boundaryEvent>
    <sequenceFlow id="gp40_f4" sourceRef="gp40_sla" targetRef="gp40_breach"/>
    <userTask id="gp40_breach" name="SLA breach: report"/>
    <sequenceFlow id="gp40_f5" sourceRef="gp40_breach" targetRef="gp40_breachEnd"/>
    <endEvent id="gp40_breachEnd"/>
    <intermediateCatchEvent id="gp40_reply" name="Reply received">
      <messageEventDefinition id="gp40_med" messageRef="gp40_msgReply"/>
    </intermediateCatchEvent>
    <sequenceFlow id="gp40_f6" sourceRef="gp40_hearing" targetRef="gp40_join"/>
    <sequenceFlow id="gp40_f7" sourceRef="gp40_reply" targetRef="gp40_join"/>
    <parallelGateway id="gp40_join"/>
    <sequenceFlow id="gp40_f8" sourceRef="gp40_join" targetRef="gp40_end"/>
    <endEvent id="gp40_end"/>
  </process>
</definitions>
