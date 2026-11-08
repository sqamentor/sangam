<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp31_defs">
  <!-- GP-31: every e-challan waits for the bank credit (message, correlated by challan number = business key) or,
       after T+3 days, goes to a treasury officer as unmatched. A daily reconciliation instance hands the bank
       scroll file to a file worker (external task), which matches and correlates. -->
  <message id="gp31_msgCredited" name="challanCredited"/>
  <process id="gp31Challan" name="e-Challan" isExecutable="true">
    <startEvent id="gp31_start"/>
    <sequenceFlow id="gp31_f1" sourceRef="gp31_start" targetRef="gp31_gw"/>
    <eventBasedGateway id="gp31_gw"/>
    <sequenceFlow id="gp31_f2" sourceRef="gp31_gw" targetRef="gp31_credited"/>
    <sequenceFlow id="gp31_f3" sourceRef="gp31_gw" targetRef="gp31_timeout"/>
    <intermediateCatchEvent id="gp31_credited" name="Bank credit">
      <messageEventDefinition id="gp31_med" messageRef="gp31_msgCredited"/>
    </intermediateCatchEvent>
    <intermediateCatchEvent id="gp31_timeout" name="T+3 days">
      <timerEventDefinition id="gp31_t"><timeDuration xsi:type="tFormalExpression">P3D</timeDuration></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="gp31_f4" sourceRef="gp31_credited" targetRef="gp31_endCredited"/>
    <sequenceFlow id="gp31_f5" sourceRef="gp31_timeout" targetRef="gp31_investigate"/>
    <userTask id="gp31_investigate" name="Investigate unmatched challan">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(treasury-officer)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp31_f6" sourceRef="gp31_investigate" targetRef="gp31_endUnmatched"/>
    <endEvent id="gp31_endCredited"/>
    <endEvent id="gp31_endUnmatched"/>
  </process>
  <process id="gp31Recon" name="Daily bank scroll reconciliation" isExecutable="true">
    <startEvent id="gp31r_start"/>
    <sequenceFlow id="gp31r_f1" sourceRef="gp31r_start" targetRef="gp31r_match"/>
    <serviceTask id="gp31r_match" name="Match bank scroll" {{EXT bank-scroll}}/>
    <sequenceFlow id="gp31r_f2" sourceRef="gp31r_match" targetRef="gp31r_gw"/>
    <exclusiveGateway id="gp31r_gw" default="gp31r_f4"/>
    <sequenceFlow id="gp31r_f3" sourceRef="gp31r_gw" targetRef="gp31r_exceptions">
      <conditionExpression xsi:type="tFormalExpression">${unknownCredits &gt; 0}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp31r_f4" sourceRef="gp31r_gw" targetRef="gp31r_end"/>
    <userTask id="gp31r_exceptions" name="Review credits without challan">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(treasury-officer)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp31r_f5" sourceRef="gp31r_exceptions" targetRef="gp31r_end"/>
    <endEvent id="gp31r_end"/>
  </process>
</definitions>
