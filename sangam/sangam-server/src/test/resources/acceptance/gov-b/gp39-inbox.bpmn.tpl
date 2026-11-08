<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp39_defs">
  <!-- GP-39: one dak/file task per instance, offered to a seat (candidate group from a variable). -->
  <process id="gp39Dak" name="Dak item" isExecutable="true">
    <startEvent id="gp39_start"/>
    <sequenceFlow id="gp39_f1" sourceRef="gp39_start" targetRef="gp39_act"/>
    <userTask id="gp39_act" name="Act on dak">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(${seat})</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp39_f2" sourceRef="gp39_act" targetRef="gp39_end"/>
    <endEvent id="gp39_end"/>
  </process>
</definitions>
