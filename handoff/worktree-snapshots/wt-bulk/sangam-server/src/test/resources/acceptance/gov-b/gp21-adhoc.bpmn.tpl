<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp21b_defs">
  <!-- GP-21 (BPMN form): ad-hoc sub-process. Standard BPMN 2.0.2 adHocSubProcess. -->
  <process id="gp21AdHoc" name="Court case (ad-hoc)" isExecutable="true">
    <startEvent id="gp21b_start"/>
    <sequenceFlow id="gp21b_f1" sourceRef="gp21b_start" targetRef="gp21b_adhoc"/>
    <adHocSubProcess id="gp21b_adhoc" name="Court case actions" ordering="Parallel" cancelRemainingInstances="true">
      <userTask id="gp21b_counterAffidavit" name="File counter affidavit"/>
      <userTask id="gp21b_seekLegalOpinion" name="Seek legal opinion"/>
      <completionCondition xsi:type="tFormalExpression">${execution.getVariable('caseDone') == true}</completionCondition>
    </adHocSubProcess>
    <sequenceFlow id="gp21b_f2" sourceRef="gp21b_adhoc" targetRef="gp21b_end"/>
    <endEvent id="gp21b_end"/>
  </process>
</definitions>
