<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp34_defs">
  <!-- GP-34: the sanctioning officer approves an order; the approval carries the SHA-256 of the canonical payload
       (approvedHash). The eSign worker re-computes the hash from the instance's current variables, signs only when it
       still matches, and otherwise raises BPMN error PAYLOAD_CHANGED, which sends the order back for re-approval. -->
  <error id="gp34_errChanged" errorCode="PAYLOAD_CHANGED"/>
  <process id="gp34SignedOrder" name="Signed sanction order" isExecutable="true">
    <startEvent id="gp34_start"/>
    <sequenceFlow id="gp34_f1" sourceRef="gp34_start" targetRef="gp34_approve"/>
    <userTask id="gp34_approve" name="Approve sanction order">
      <potentialOwner><resourceAssignmentExpression><formalExpression>group(sanctioning-authority)</formalExpression></resourceAssignmentExpression></potentialOwner>
    </userTask>
    <sequenceFlow id="gp34_f2" sourceRef="gp34_approve" targetRef="gp34_sign"/>
    <serviceTask id="gp34_sign" name="eSign the approved order" {{EXT esign}}/>
    <boundaryEvent id="gp34_changed" attachedToRef="gp34_sign">
      <errorEventDefinition id="gp34_eed" errorRef="gp34_errChanged"/>
    </boundaryEvent>
    <sequenceFlow id="gp34_f3" sourceRef="gp34_changed" targetRef="gp34_approve"/>
    <sequenceFlow id="gp34_f4" sourceRef="gp34_sign" targetRef="gp34_dispatch"/>
    <userTask id="gp34_dispatch" name="Dispatch signed order"/>
    <sequenceFlow id="gp34_f5" sourceRef="gp34_dispatch" targetRef="gp34_end"/>
    <endEvent id="gp34_end"/>
  </process>
</definitions>
