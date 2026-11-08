<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp38i_defs">
  <!-- GP-38 sub-check: a Devanagari VARIABLE NAME used as a JUEL identifier in a gateway condition. -->
  <process id="gp38HindiIdentifier" name="Hindi identifier" isExecutable="true">
    <startEvent id="gp38i_start"/>
    <sequenceFlow id="gp38i_f1" sourceRef="gp38i_start" targetRef="gp38i_gw"/>
    <exclusiveGateway id="gp38i_gw" default="gp38i_f3"/>
    <sequenceFlow id="gp38i_f2" sourceRef="gp38i_gw" targetRef="gp38i_yes">
      <conditionExpression xsi:type="tFormalExpression">${स्थिति == 'स्वीकृत'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp38i_f3" sourceRef="gp38i_gw" targetRef="gp38i_no"/>
    <userTask id="gp38i_yes" name="हाँ"/>
    <userTask id="gp38i_no" name="नहीं"/>
    <sequenceFlow id="gp38i_f4" sourceRef="gp38i_yes" targetRef="gp38i_end"/>
    <sequenceFlow id="gp38i_f5" sourceRef="gp38i_no" targetRef="gp38i_end"/>
    <endEvent id="gp38i_end"/>
  </process>
</definitions>
