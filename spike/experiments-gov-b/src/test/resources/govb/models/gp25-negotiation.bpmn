<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp25_defs">
  <!-- GP-25: L1 negotiation loop with counter-offers; a failed negotiation goes back to the earlier stage (retender,
       NIT again), at most twice, then the procurement is dropped. The counters are set by the deciding officer's
       completion variables (no script task: no script engine is bundled, LIC-1). -->
  <process id="gp25Negotiation" name="L1 negotiation" isExecutable="true">
    <startEvent id="gp25_start"/>
    <sequenceFlow id="gp25_f1" sourceRef="gp25_start" targetRef="gp25_publishNit"/>
    <userTask id="gp25_publishNit" name="Publish NIT"/>
    <sequenceFlow id="gp25_f2" sourceRef="gp25_publishNit" targetRef="gp25_evaluate"/>
    <userTask id="gp25_evaluate" name="Evaluate bids, find L1"/>
    <sequenceFlow id="gp25_f3" sourceRef="gp25_evaluate" targetRef="gp25_negotiate"/>
    <userTask id="gp25_negotiate" name="Negotiate with L1"/>
    <sequenceFlow id="gp25_f4" sourceRef="gp25_negotiate" targetRef="gp25_gw"/>
    <exclusiveGateway id="gp25_gw"/>
    <sequenceFlow id="gp25_f5" sourceRef="gp25_gw" targetRef="gp25_loa">
      <conditionExpression xsi:type="tFormalExpression">${negotiation == 'AGREED'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp25_f6" sourceRef="gp25_gw" targetRef="gp25_negotiate">
      <conditionExpression xsi:type="tFormalExpression">${negotiation == 'COUNTER'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp25_f7" sourceRef="gp25_gw" targetRef="gp25_publishNit">
      <conditionExpression xsi:type="tFormalExpression">${negotiation == 'FAILED' &amp;&amp; retenderCount &lt; 2}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp25_f8" sourceRef="gp25_gw" targetRef="gp25_dropped">
      <conditionExpression xsi:type="tFormalExpression">${negotiation == 'FAILED' &amp;&amp; retenderCount &gt;= 2}</conditionExpression>
    </sequenceFlow>
    <userTask id="gp25_loa" name="Issue letter of acceptance"/>
    <sequenceFlow id="gp25_f9" sourceRef="gp25_loa" targetRef="gp25_endAwarded"/>
    <endEvent id="gp25_endAwarded"/>
    <endEvent id="gp25_dropped"/>
  </process>
</definitions>
