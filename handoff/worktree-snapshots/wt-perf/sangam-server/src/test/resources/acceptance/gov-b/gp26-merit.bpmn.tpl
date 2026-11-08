<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp26_defs">
  <!-- GP-26: the engine evaluates the COLLECT table per candidate inside the process; the merit list itself (sort by
       score inside each pool, fill vertical then horizontal seats) is product code over the collected pools. -->
  <process id="gp26Merit" name="Candidate pools" isExecutable="true">
    <startEvent id="gp26_start"/>
    <sequenceFlow id="gp26_f1" sourceRef="gp26_start" targetRef="gp26_pools"/>
    {{DMNCOLLECT gp26_pools|Reservation pools|reservationPools}}
    <sequenceFlow id="gp26_f2" sourceRef="gp26_pools" targetRef="gp26_wait"/>
    <userTask id="gp26_wait" name="Await merit list"/>
    <sequenceFlow id="gp26_f3" sourceRef="gp26_wait" targetRef="gp26_end"/>
    <endEvent id="gp26_end"/>
  </process>
</definitions>
