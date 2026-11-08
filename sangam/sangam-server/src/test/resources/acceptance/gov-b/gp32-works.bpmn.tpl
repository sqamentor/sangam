<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp32_defs">
  <!-- GP-32: the Works department (tenant dept-works) refers a proposal for concurrence through a call activity and
       continues with the answer. The concurrence process is deployed in the caller's tenant: a call activity may not
       start a process of another tenant (SL-13; cross-tenant referral is a message to the other tenant, J-P2-E09). -->
  <process id="gp32WorksProposal" name="Works proposal" isExecutable="true">
    <startEvent id="gp32_start"/>
    <sequenceFlow id="gp32_f1" sourceRef="gp32_start" targetRef="gp32_prepare"/>
    <userTask id="gp32_prepare" name="Prepare proposal">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(works-ee)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp32_f2" sourceRef="gp32_prepare" targetRef="gp32_referFinance"/>
    <callActivity id="gp32_referFinance" name="Refer to Finance" {{CALLED concurrence}}>
      {{CALLIO in=fileNo>fileNo,amount>amount;out=concurred>finConcurred,remarks>finRemarks}}
    </callActivity>
    <sequenceFlow id="gp32_f3" sourceRef="gp32_referFinance" targetRef="gp32_gw"/>
    <exclusiveGateway id="gp32_gw" default="gp32_f5"/>
    <sequenceFlow id="gp32_f4" sourceRef="gp32_gw" targetRef="gp32_sanction">
      <conditionExpression xsi:type="tFormalExpression">${finConcurred == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp32_f5" sourceRef="gp32_gw" targetRef="gp32_prepare"/>
    <userTask id="gp32_sanction" name="Issue administrative approval">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(works-se)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp32_f6" sourceRef="gp32_sanction" targetRef="gp32_end"/>
    <endEvent id="gp32_end"/>
  </process>
</definitions>
