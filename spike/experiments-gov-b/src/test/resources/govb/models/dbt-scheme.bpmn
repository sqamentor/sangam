<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e17b" id="dbt_defs">
  <!-- D-DBT-SCHEME (IX.4 P3): application -> verification -> sanction -> DBT payment through PFMS (file exchange,
       simulator). The payment worker reports paymentStatus SUCCESS / FAILED / UNKNOWN. UNKNOWN (no status file:
       lost response) goes to the reconciliation worker, never to a blind retry (VI.3); FAILED (bank rejected the
       account) goes back to the beneficiary's account correction and pays again with a new operationId. -->
  <process id="dbtScheme" name="DBT scheme benefit" isExecutable="true">
    <startEvent id="dbt_start" name="Application"/>
    <sequenceFlow id="dbt_f1" sourceRef="dbt_start" targetRef="dbt_verify"/>
    <userTask id="dbt_verify" name="Verify application and documents">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(scheme-verifier)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="dbt_f2" sourceRef="dbt_verify" targetRef="dbt_gwEligible"/>
    <exclusiveGateway id="dbt_gwEligible" default="dbt_f4"/>
    <sequenceFlow id="dbt_f3" sourceRef="dbt_gwEligible" targetRef="dbt_sanction">
      <conditionExpression xsi:type="tFormalExpression">${eligible == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="dbt_f4" sourceRef="dbt_gwEligible" targetRef="dbt_endIneligible"/>
    <userTask id="dbt_sanction" name="Sanction benefit">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(sanctioning-authority)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="dbt_f5" sourceRef="dbt_sanction" targetRef="dbt_pay"/>
    <serviceTask id="dbt_pay" name="DBT payment (PFMS)" {{EXT dbt-payment}}/>
    <sequenceFlow id="dbt_f6" sourceRef="dbt_pay" targetRef="dbt_gwStatus"/>
    <exclusiveGateway id="dbt_gwStatus"/>
    <sequenceFlow id="dbt_f7" sourceRef="dbt_gwStatus" targetRef="dbt_endPaid">
      <conditionExpression xsi:type="tFormalExpression">${paymentStatus == 'SUCCESS'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="dbt_f8" sourceRef="dbt_gwStatus" targetRef="dbt_correct">
      <conditionExpression xsi:type="tFormalExpression">${paymentStatus == 'FAILED'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="dbt_f9" sourceRef="dbt_gwStatus" targetRef="dbt_reconcile">
      <conditionExpression xsi:type="tFormalExpression">${paymentStatus == 'UNKNOWN'}</conditionExpression>
    </sequenceFlow>
    <serviceTask id="dbt_reconcile" name="Reconcile payment outcome" {{EXT dbt-reconcile}}/>
    <sequenceFlow id="dbt_f10" sourceRef="dbt_reconcile" targetRef="dbt_gwStatus"/>
    <userTask id="dbt_correct" name="Beneficiary: correct bank account">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(scheme-verifier)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="dbt_f11" sourceRef="dbt_correct" targetRef="dbt_pay"/>
    <endEvent id="dbt_endPaid"/>
    <endEvent id="dbt_endIneligible"/>
  </process>
</definitions>
