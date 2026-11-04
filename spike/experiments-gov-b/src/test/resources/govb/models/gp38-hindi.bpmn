<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp38_defs">
  <!-- GP-38: Hindi (Devanagari) in names, candidate group, gateway literal and variables. -->
  <process id="gp38Bilingual" name="द्विभाषी आवेदन (Bilingual application)" isExecutable="true">
    <startEvent id="gp38_start" name="आवेदन प्राप्त"/>
    <sequenceFlow id="gp38_f1" sourceRef="gp38_start" targetRef="gp38_check"/>
    <userTask id="gp38_check" name="आवेदन की जाँच">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(${section})</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp38_f2" sourceRef="gp38_check" targetRef="gp38_gw"/>
    <exclusiveGateway id="gp38_gw" default="gp38_f4"/>
    <sequenceFlow id="gp38_f3" name="स्वीकृत" sourceRef="gp38_gw" targetRef="gp38_endApproved">
      <conditionExpression xsi:type="tFormalExpression">${status == 'स्वीकृत'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp38_f4" name="अस्वीकृत" sourceRef="gp38_gw" targetRef="gp38_endRejected"/>
    <endEvent id="gp38_endApproved" name="स्वीकृत"/>
    <endEvent id="gp38_endRejected" name="अस्वीकृत"/>
  </process>
</definitions>
