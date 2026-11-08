<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="leave_defs">
  <!-- GP-29 / GP-36 / GP-37 / D-LEAVE: balance check, then the leaveRouting DMN; AUTO ends approved at once,
       otherwise the sanctioning seat named by the rule (candidate group = route) decides. -->
  <process id="leaveApplication" name="Leave application" isExecutable="true">
    <startEvent id="lv_start"/>
    <sequenceFlow id="lv_f1" sourceRef="lv_start" targetRef="lv_gwBalance"/>
    <exclusiveGateway id="lv_gwBalance" default="lv_f3"/>
    <sequenceFlow id="lv_f2" sourceRef="lv_gwBalance" targetRef="lv_endNoBalance">
      <conditionExpression xsi:type="tFormalExpression">${days &gt; balance}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="lv_f3" sourceRef="lv_gwBalance" targetRef="lv_route"/>
    {{DMN lv_route|Route by delegation|leaveRouting|route}}
    <sequenceFlow id="lv_f4" sourceRef="lv_route" targetRef="lv_gwRoute"/>
    <exclusiveGateway id="lv_gwRoute" default="lv_f6"/>
    <sequenceFlow id="lv_f5" sourceRef="lv_gwRoute" targetRef="lv_endAuto">
      <conditionExpression xsi:type="tFormalExpression">${route == 'AUTO'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="lv_f6" sourceRef="lv_gwRoute" targetRef="lv_sanction"/>
    <userTask id="lv_sanction" name="Sanction leave">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(${route})</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="lv_f7" sourceRef="lv_sanction" targetRef="lv_gwDecision"/>
    <exclusiveGateway id="lv_gwDecision" default="lv_f9"/>
    <sequenceFlow id="lv_f8" sourceRef="lv_gwDecision" targetRef="lv_endApproved">
      <conditionExpression xsi:type="tFormalExpression">${sanctioned == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="lv_f9" sourceRef="lv_gwDecision" targetRef="lv_endRejected"/>
    <endEvent id="lv_endNoBalance"/>
    <endEvent id="lv_endAuto"/>
    <endEvent id="lv_endApproved"/>
    <endEvent id="lv_endRejected"/>
  </process>
</definitions>
