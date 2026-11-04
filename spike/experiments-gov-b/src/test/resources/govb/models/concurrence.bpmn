<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="conc_defs">
  <!-- GP-32 / D-REFERRAL: the concurrence process of the CONSULTED department (deployed to that department's tenant).
       The concurring officer returns concurred (boolean) and remarks. -->
  <process id="concurrence" name="Concurrence on referred file" isExecutable="true">
    <startEvent id="conc_start"/>
    <sequenceFlow id="conc_f1" sourceRef="conc_start" targetRef="conc_examine"/>
    <userTask id="conc_examine" name="Examine referred file">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(concurrence-officer)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="conc_f2" sourceRef="conc_examine" targetRef="conc_end"/>
    <endEvent id="conc_end"/>
  </process>
</definitions>
