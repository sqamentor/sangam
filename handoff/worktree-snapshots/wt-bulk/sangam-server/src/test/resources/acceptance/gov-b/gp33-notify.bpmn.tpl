<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp33_defs">
  <!-- GP-33: a citizen service that sends a DLT-registered SMS at each step (receipt, decision). Each send is an
       external task on topic 'sms'; the worker picks the DLT template by activity id. -->
  <process id="gp33CitizenService" name="Citizen service with SMS" isExecutable="true">
    <startEvent id="gp33_start"/>
    <sequenceFlow id="gp33_f1" sourceRef="gp33_start" targetRef="gp33_smsReceived"/>
    <serviceTask id="gp33_smsReceived" name="SMS: application received" {{EXT sms}}/>
    <sequenceFlow id="gp33_f2" sourceRef="gp33_smsReceived" targetRef="gp33_scrutiny"/>
    <userTask id="gp33_scrutiny" name="Scrutiny">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(service-clerk)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp33_f3" sourceRef="gp33_scrutiny" targetRef="gp33_smsDecision"/>
    <serviceTask id="gp33_smsDecision" name="SMS: decision" {{EXT sms}}/>
    <sequenceFlow id="gp33_f4" sourceRef="gp33_smsDecision" targetRef="gp33_end"/>
    <endEvent id="gp33_end"/>
  </process>
</definitions>
