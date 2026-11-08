<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e17b" id="grv_defs">
  <!-- D-GRIEVANCE (CPGRAMS-style). Standard CMMN 1.1 only, the same file on both engines:
       - Examine runs at once; Dispose becomes available when Examine completes.
       - Ad-hoc actions with manual activation: seek field report (repeatable), forward to a subordinate office,
         personal hearing.
       - Escalation: a BLOCKING process task runs grievanceSla (BPMN timer 21 days -> senior officer's review). It is
         exited when Dispose completes, which ends the SLA process. (CMMN timerEventListener is not used: Operaton
         2.2.0 has no event listener support, see E16-b GP-21 sub-check.)
       - Milestone Disposed; the case plan auto-completes once nothing is active. -->
  <case id="grievanceCase" name="Public grievance">
    <casePlanModel id="grv_plan" name="Grievance" autoComplete="true">
      <planItem id="PI_examine" definitionRef="grv_examine"/>
      <planItem id="PI_seekReport" definitionRef="grv_seekReport">
        <itemControl id="grv_ic1">
          <repetitionRule id="grv_rep1"><condition id="grv_c1">${true}</condition></repetitionRule>
          <manualActivationRule id="grv_mar1"><condition id="grv_c2">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="PI_forward" definitionRef="grv_forward">
        <itemControl id="grv_ic2">
          <manualActivationRule id="grv_mar2"><condition id="grv_c3">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="PI_hearing" definitionRef="grv_hearing">
        <itemControl id="grv_ic3">
          <manualActivationRule id="grv_mar3"><condition id="grv_c4">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="PI_dispose" definitionRef="grv_dispose">
        <itemControl id="grv_ic4">
          <requiredRule id="grv_req"><condition id="grv_c5">${true}</condition></requiredRule>
        </itemControl>
        <entryCriterion id="grv_ec_dispose" sentryRef="grv_s_examined"/>
      </planItem>
      <planItem id="PI_sla" definitionRef="grv_sla">
        <exitCriterion id="grv_xc_sla" sentryRef="grv_s_disposed"/>
      </planItem>
      <planItem id="PI_disposed" definitionRef="grv_disposedMs">
        <entryCriterion id="grv_ec_ms" sentryRef="grv_s_disposed"/>
      </planItem>

      <sentry id="grv_s_examined" name="Examined">
        <planItemOnPart id="grv_op1" sourceRef="PI_examine"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <sentry id="grv_s_disposed" name="Disposed">
        <planItemOnPart id="grv_op2" sourceRef="PI_dispose"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>

      <humanTask id="grv_examine" name="Examine grievance"/>
      <humanTask id="grv_seekReport" name="Seek report from field office"/>
      <humanTask id="grv_forward" name="Forward to subordinate office"/>
      <humanTask id="grv_hearing" name="Personal hearing of complainant"/>
      <humanTask id="grv_dispose" name="Dispose grievance (final reply)"/>
      <processTask id="grv_sla" name="SLA watch" processRef="grievanceSla" isBlocking="true"/>
      <milestone id="grv_disposedMs" name="Disposed"/>
    </casePlanModel>
  </case>
</definitions>
