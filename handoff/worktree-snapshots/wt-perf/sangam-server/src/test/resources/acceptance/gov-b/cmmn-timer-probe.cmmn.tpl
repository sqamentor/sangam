<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="ctp_defs">
  <!-- Probe: standard CMMN 1.1 timerEventListener (escalation inside a case without a BPMN helper). -->
  <case id="cmmnTimerProbe" name="Case timer probe">
    <casePlanModel id="ctp_plan" name="Probe">
      <planItem id="PI_work" definitionRef="ctp_work"/>
      <planItem id="PI_timer" definitionRef="ctp_timer"/>
      <planItem id="PI_escalate" definitionRef="ctp_escalate">
        <entryCriterion id="ctp_ec" sentryRef="ctp_s"/>
      </planItem>
      <sentry id="ctp_s">
        <planItemOnPart id="ctp_op" sourceRef="PI_timer"><standardEvent>occur</standardEvent></planItemOnPart>
      </sentry>
      <humanTask id="ctp_work" name="Work"/>
      <timerEventListener id="ctp_timer" name="21 days">
        <timerExpression id="ctp_te">P21D</timerExpression>
      </timerEventListener>
      <humanTask id="ctp_escalate" name="Escalated"/>
    </casePlanModel>
  </case>
</definitions>
