<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp22_defs">
  <!-- GP-22: an order is decided and the instance closes; an appeal / reopen later starts a LINKED new instance
       (gp22Appeal) that reads the closed instance from history. -->
  <process id="gp22Decision" name="Order on application" isExecutable="true">
    <startEvent id="gp22_start"/>
    <sequenceFlow id="gp22_f1" sourceRef="gp22_start" targetRef="gp22_decide"/>
    <userTask id="gp22_decide" name="Decide application">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(dealing-officer)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp22_f2" sourceRef="gp22_decide" targetRef="gp22_end"/>
    <endEvent id="gp22_end"/>
  </process>
  <process id="gp22Appeal" name="Appeal / reopen" isExecutable="true">
    <startEvent id="gp22a_start"/>
    <sequenceFlow id="gp22a_f1" sourceRef="gp22a_start" targetRef="gp22a_hear"/>
    <userTask id="gp22a_hear" name="Hear appeal">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(appellate-authority)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp22a_f2" sourceRef="gp22a_hear" targetRef="gp22a_gw"/>
    <exclusiveGateway id="gp22a_gw" default="gp22a_f4"/>
    <sequenceFlow id="gp22a_f3" sourceRef="gp22a_gw" targetRef="gp22a_redecide">
      <conditionExpression xsi:type="tFormalExpression">${appealOutcome == 'REMAND'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp22a_f4" sourceRef="gp22a_gw" targetRef="gp22a_end"/>
    <userTask id="gp22a_redecide" name="Re-decide on remand">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(dealing-officer)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp22a_f5" sourceRef="gp22a_redecide" targetRef="gp22a_end"/>
    <endEvent id="gp22a_end"/>
  </process>
</definitions>
