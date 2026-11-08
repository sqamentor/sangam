<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp24_defs">
  <!-- GP-24: result declaration is not possible before resultDate (ISO-8601 with +05:30 offset, a process variable).
       The task does not exist before the date, so it cannot be claimed or completed early. -->
  <process id="gp24TimeLock" name="Result declaration" isExecutable="true">
    <startEvent id="gp24_start"/>
    <sequenceFlow id="gp24_f1" sourceRef="gp24_start" targetRef="gp24_wait"/>
    <intermediateCatchEvent id="gp24_wait" name="Not before result date">
      <timerEventDefinition id="gp24_t"><timeDate xsi:type="tFormalExpression">${resultDate}</timeDate></timerEventDefinition>
    </intermediateCatchEvent>
    <sequenceFlow id="gp24_f2" sourceRef="gp24_wait" targetRef="gp24_declare"/>
    <userTask id="gp24_declare" name="Declare result">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(exam-controller)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp24_f3" sourceRef="gp24_declare" targetRef="gp24_end"/>
    <endEvent id="gp24_end"/>
  </process>
</definitions>
