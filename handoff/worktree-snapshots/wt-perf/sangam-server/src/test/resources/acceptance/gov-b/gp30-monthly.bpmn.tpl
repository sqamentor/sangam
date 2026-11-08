<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp30_defs">
  <!-- GP-30: monthly pension bill generation. Two forms of the same cycle: ISO-8601 repeating interval (standard
       BPMN timeCycle) and a cron expression (accepted by both engines in timeCycle). -->
  <process id="gp30PensionIso" name="Monthly pension bill (ISO cycle)" isExecutable="true">
    <startEvent id="gp30i_start" name="1st of every month 06:00 IST">
      <timerEventDefinition id="gp30i_t"><timeCycle xsi:type="tFormalExpression">R12/2026-12-01T06:00:00+05:30/P1M</timeCycle></timerEventDefinition>
    </startEvent>
    <sequenceFlow id="gp30i_f1" sourceRef="gp30i_start" targetRef="gp30i_prepare"/>
    <userTask id="gp30i_prepare" name="Prepare monthly pension bill"/>
    <sequenceFlow id="gp30i_f2" sourceRef="gp30i_prepare" targetRef="gp30i_end"/>
    <endEvent id="gp30i_end"/>
  </process>
  <process id="gp30PensionCron" name="Monthly pension bill (cron)" isExecutable="true">
    <startEvent id="gp30c_start" name="cron: 06:00 on day 1">
      <timerEventDefinition id="gp30c_t"><timeCycle xsi:type="tFormalExpression">0 0 6 1 * ?</timeCycle></timerEventDefinition>
    </startEvent>
    <sequenceFlow id="gp30c_f1" sourceRef="gp30c_start" targetRef="gp30c_prepare"/>
    <userTask id="gp30c_prepare" name="Prepare monthly salary bill"/>
    <sequenceFlow id="gp30c_f2" sourceRef="gp30c_prepare" targetRef="gp30c_end"/>
    <endEvent id="gp30c_end"/>
  </process>
</definitions>
