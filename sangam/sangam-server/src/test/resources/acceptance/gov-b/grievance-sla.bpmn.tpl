<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e17b" id="grvsla_defs">
  <!-- D-GRIEVANCE escalation: started by the case's process task; after 21 days the grievance is escalated to the
       senior officer. Ended from the case (exit criterion) when the grievance is disposed. -->
  <process id="grievanceSla" name="Grievance SLA watch" isExecutable="true">
    <startEvent id="gs_start"/>
    <sequenceFlow id="gs_f1" sourceRef="gs_start" targetRef="gs_wait"/>
    <intermediateCatchEvent id="gs_wait" name="21 days">
      <timerEventDefinition id="gs_t"><timeDuration xsi:type="tFormalExpression">P21D</timeDuration></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="gs_f2" sourceRef="gs_wait" targetRef="gs_escalated"/>
    <userTask id="gs_escalated" name="Escalated: senior officer review">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(grv-senior)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gs_f3" sourceRef="gs_escalated" targetRef="gs_end"/>
    <endEvent id="gs_end"/>
  </process>
</definitions>
