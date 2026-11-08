<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp28_defs">
  <!-- GP-28: a selected candidate who declines the offer throws the signal seatVacated. The signal START event of
       gp28Promote creates one new promotion instance per vacancy (no vacancy is lost while another is being handled);
       its worker picks the next waitlisted candidate and correlates the message 'promoted' to that candidate. -->
  <signal id="gp28_sigVacated" name="seatVacated"/>
  <message id="gp28_msgPromoted" name="promoted"/>
  <process id="gp28Candidate" name="DV candidate" isExecutable="true">
    <startEvent id="gp28_start"/>
    <sequenceFlow id="gp28_f1" sourceRef="gp28_start" targetRef="gp28_gwStatus"/>
    <exclusiveGateway id="gp28_gwStatus" default="gp28_f3"/>
    <sequenceFlow id="gp28_f2" sourceRef="gp28_gwStatus" targetRef="gp28_offer">
      <conditionExpression xsi:type="tFormalExpression">${status == 'SELECTED'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp28_f3" sourceRef="gp28_gwStatus" targetRef="gp28_waitPromoted"/>
    <intermediateCatchEvent id="gp28_waitPromoted" name="Promoted from waitlist">
      <messageEventDefinition id="gp28_med" messageRef="gp28_msgPromoted"/>
    </intermediateCatchEvent>
    <sequenceFlow id="gp28_f4" sourceRef="gp28_waitPromoted" targetRef="gp28_offer"/>
    <userTask id="gp28_offer" name="Accept offer"/>
    <sequenceFlow id="gp28_f5" sourceRef="gp28_offer" targetRef="gp28_gwAccept"/>
    <exclusiveGateway id="gp28_gwAccept" default="gp28_f7"/>
    <sequenceFlow id="gp28_f6" sourceRef="gp28_gwAccept" targetRef="gp28_endJoined">
      <conditionExpression xsi:type="tFormalExpression">${accepted == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="gp28_f7" sourceRef="gp28_gwAccept" targetRef="gp28_throwVacated"/>
    <intermediateThrowEvent id="gp28_throwVacated" name="Seat vacated">
      <signalEventDefinition id="gp28_sed" signalRef="gp28_sigVacated"/>
    </intermediateThrowEvent>
    <sequenceFlow id="gp28_f8" sourceRef="gp28_throwVacated" targetRef="gp28_endDeclined"/>
    <endEvent id="gp28_endJoined"/>
    <endEvent id="gp28_endDeclined"/>
  </process>
  <process id="gp28Promote" name="Waitlist promotion" isExecutable="true">
    <startEvent id="gp28p_start" name="Seat vacated">
      <signalEventDefinition id="gp28p_sed" signalRef="gp28_sigVacated"/>
    </startEvent>
    <sequenceFlow id="gp28p_f1" sourceRef="gp28p_start" targetRef="gp28p_promote"/>
    <serviceTask id="gp28p_promote" name="Promote next waitlisted candidate" {{EXT waitlist-promote}}/>
    <sequenceFlow id="gp28p_f2" sourceRef="gp28p_promote" targetRef="gp28p_end"/>
    <endEvent id="gp28p_end"/>
  </process>
</definitions>
