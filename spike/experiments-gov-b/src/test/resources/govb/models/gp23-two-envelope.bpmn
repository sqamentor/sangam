<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp23_defs">
  <!-- GP-23: two-envelope bid opening. Technical envelope opens only at techOpenAt, then every committee member
       signs (parallel multi-instance, completion = all); financial envelope opens only at finOpenAt, again with all
       members. Members are a ';'-separated string (no collection variable: see GP-38 on Java collections), picked by
       loopCounter. Standard BPMN only. -->
  <process id="gp23TwoEnvelope" name="Two-envelope opening" isExecutable="true">
    <startEvent id="gp23_start"/>
    <sequenceFlow id="gp23_f1" sourceRef="gp23_start" targetRef="gp23_waitTech"/>
    <intermediateCatchEvent id="gp23_waitTech" name="Technical opening date">
      <timerEventDefinition id="gp23_t1"><timeDate xsi:type="tFormalExpression">${techOpenAt}</timeDate></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="gp23_f2" sourceRef="gp23_waitTech" targetRef="gp23_techEval"/>
    <userTask id="gp23_techEval" name="Technical evaluation (member)">
      <potentialOwner><resourceAssignmentExpression><formalExpression>user(${committee.split(';')[loopCounter]})</formalExpression></resourceAssignmentExpression></potentialOwner>
      <multiInstanceLoopCharacteristics isSequential="false">
        <loopCardinality xsi:type="tFormalExpression">${committeeSize}</loopCardinality>
      </multiInstanceLoopCharacteristics>
    </userTask>
    <sequenceFlow id="gp23_f3" sourceRef="gp23_techEval" targetRef="gp23_techResult"/>
    <userTask id="gp23_techResult" name="Record technical result (chair)">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(tender-committee-chair)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp23_f4" sourceRef="gp23_techResult" targetRef="gp23_gw"/>
    <exclusiveGateway id="gp23_gw" default="gp23_f6"/>
    <sequenceFlow id="gp23_f5" sourceRef="gp23_gw" targetRef="gp23_waitFin">
      <conditionExpression xsi:type="tFormalExpression">${techQualified == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp23_f6" sourceRef="gp23_gw" targetRef="gp23_endRejected"/>
    <intermediateCatchEvent id="gp23_waitFin" name="Financial opening date">
      <timerEventDefinition id="gp23_t2"><timeDate xsi:type="tFormalExpression">${finOpenAt}</timeDate></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="gp23_f7" sourceRef="gp23_waitFin" targetRef="gp23_finEval"/>
    <userTask id="gp23_finEval" name="Financial bid opening (member)">
      <potentialOwner><resourceAssignmentExpression><formalExpression>user(${committee.split(';')[loopCounter]})</formalExpression></resourceAssignmentExpression></potentialOwner>
      <multiInstanceLoopCharacteristics isSequential="false">
        <loopCardinality xsi:type="tFormalExpression">${committeeSize}</loopCardinality>
      </multiInstanceLoopCharacteristics>
    </userTask>
    <sequenceFlow id="gp23_f8" sourceRef="gp23_finEval" targetRef="gp23_endOpened"/>
    <endEvent id="gp23_endOpened"/>
    <endEvent id="gp23_endRejected"/>
  </process>
</definitions>
