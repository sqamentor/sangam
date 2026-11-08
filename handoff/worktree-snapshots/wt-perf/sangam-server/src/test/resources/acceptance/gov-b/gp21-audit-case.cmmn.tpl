<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp21_defs">
  <!-- GP-21: ad-hoc case handling (audit para). Standard CMMN 1.1 only: Examine runs at once; two ad-hoc actions with
       a manual activation rule (one repeatable) can be started at any time; the REQUIRED Close task becomes active
       after Examine, and the case plan auto-completes when Close is done and nothing else is active. -->
  <case id="gp21AuditPara" name="Audit para">
    <casePlanModel id="gp21_plan" name="Audit para" autoComplete="true">
      <planItem id="PI_examine" definitionRef="gp21_examine"/>
      <planItem id="PI_seekReport" definitionRef="gp21_seekReport">
        <itemControl id="gp21_ic1">
          <repetitionRule id="gp21_rep1"><condition id="gp21_c1">${true}</condition></repetitionRule>
          <manualActivationRule id="gp21_mar1"><condition id="gp21_c2">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="PI_hearing" definitionRef="gp21_hearing">
        <itemControl id="gp21_ic2">
          <manualActivationRule id="gp21_mar2"><condition id="gp21_c3">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="PI_close" definitionRef="gp21_close">
        <itemControl id="gp21_ic3">
          <requiredRule id="gp21_req"><condition id="gp21_c4">${true}</condition></requiredRule>
        </itemControl>
        <entryCriterion id="gp21_ec_close" sentryRef="gp21_s_examined"/>
      </planItem>
      <sentry id="gp21_s_examined">
        <planItemOnPart id="gp21_op1" sourceRef="PI_examine"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <humanTask id="gp21_examine" name="Examine audit para"/>
      <humanTask id="gp21_seekReport" name="Seek report from field office"/>
      <humanTask id="gp21_hearing" name="Personal hearing"/>
      <humanTask id="gp21_close" name="Settle / drop the para (closes the case)"/>
    </casePlanModel>
  </case>
</definitions>
