<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://sangam.gov.in/e16b" id="gp27_defs">
  <!-- GP-27: bulk document generation. One instance per candidate reaches an external task (admit card); a pool of
       document workers fetches them in batches. -->
  <process id="gp27AdmitCard" name="Admit card" isExecutable="true">
    <startEvent id="gp27_start"/>
    <sequenceFlow id="gp27_f1" sourceRef="gp27_start" targetRef="gp27_generate"/>
    <serviceTask id="gp27_generate" name="Generate admit card" {{EXT admit-card}}/>
    <sequenceFlow id="gp27_f2" sourceRef="gp27_generate" targetRef="gp27_end"/>
    <endEvent id="gp27_end"/>
  </process>
</definitions>
