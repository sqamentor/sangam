<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e17b" id="ref_defs">
  <!-- D-REFERRAL: the administrative department (tenant dept-works) refers a file at the same time to Finance
       (tenant dept-fin) and Law (tenant dept-law). Each department runs its own 'concurrence' process in its own
       tenant (own officers, own inbox). If either does not concur, the file is revised and referred again. -->
  <process id="interDeptReferral" name="Inter-department referral" isExecutable="true">
    <startEvent id="ref_start"/>
    <sequenceFlow id="ref_f1" sourceRef="ref_start" targetRef="ref_prepare"/>
    <userTask id="ref_prepare" name="Prepare file for referral">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(works-us)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="ref_f2" sourceRef="ref_prepare" targetRef="ref_fork"/>
    <parallelGateway id="ref_fork"/>
    <sequenceFlow id="ref_f3" sourceRef="ref_fork" targetRef="ref_finance"/>
    <sequenceFlow id="ref_f4" sourceRef="ref_fork" targetRef="ref_law"/>
    <callActivity id="ref_finance" name="Finance concurrence" {{CALLED concurrence|dept-fin|finConcurrenceDefId}}>
      {{CALLIO in=fileNo>fileNo,subject>subject,amount>amount;out=concurred>finConcurred,remarks>finRemarks}}
    </callActivity>
    <callActivity id="ref_law" name="Law vetting" {{CALLED concurrence|dept-law|lawConcurrenceDefId}}>
      {{CALLIO in=fileNo>fileNo,subject>subject;out=concurred>lawConcurred,remarks>lawRemarks}}
    </callActivity>
    <sequenceFlow id="ref_f5" sourceRef="ref_finance" targetRef="ref_join"/>
    <sequenceFlow id="ref_f6" sourceRef="ref_law" targetRef="ref_join"/>
    <parallelGateway id="ref_join"/>
    <sequenceFlow id="ref_f7" sourceRef="ref_join" targetRef="ref_gw"/>
    <exclusiveGateway id="ref_gw" default="ref_f9"/>
    <sequenceFlow id="ref_f8" sourceRef="ref_gw" targetRef="ref_approve">
      <conditionExpression xsi:type="tFormalExpression">${finConcurred == true &amp;&amp; lawConcurred == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="ref_f9" sourceRef="ref_gw" targetRef="ref_prepare"/>
    <userTask id="ref_approve" name="Approval of competent authority">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(works-secretary)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="ref_f10" sourceRef="ref_approve" targetRef="ref_end"/>
    <endEvent id="ref_end"/>
  </process>
</definitions>
