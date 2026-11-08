// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.Job;

/** FW-18: FWT-055, runtime part (no runtime table growth over 36 timer cycles; Operaton has no ACT_RU_ACTINST). */
class FwtTimerCyclesTest {

    // FWT-055
    @Test
    void fwt055_thirtySixTimerCyclesKeepRuntimeTablesFlat() {
        try (FwtEngine f = new FwtEngine()) {
            f.deploy("cyc.bpmn", FwtEngine.defs("""
                  <process id="cyc" isExecutable="true">
                    <startEvent id="s"/><sequenceFlow id="f0" sourceRef="s" targetRef="w"/><userTask id="w"/>
                    <boundaryEvent id="tb" attachedToRef="w" cancelActivity="false">
                      <timerEventDefinition><timeCycle>R36/PT1M</timeCycle></timerEventDefinition></boundaryEvent>
                    <sequenceFlow id="f1" sourceRef="tb" targetRef="tick"/><serviceTask id="tick" operaton:expression="${true}"/>
                    <sequenceFlow id="f2" sourceRef="tick" targetRef="te"/><endEvent id="te"/>
                    <sequenceFlow id="f3" sourceRef="w" targetRef="e"/><endEvent id="e"/>
                  </process>"""));
            String pid = f.engine.getRuntimeService().startProcessInstanceByKey("cyc").getId();
            long exec0 = f.count("SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = ?", pid);
            long open0 = f.engine.getRuntimeService().getActivityInstance(pid).getChildActivityInstances().length;
            for (int i = 0; i < 36; i++) {
                Job j = f.engine.getManagementService().createJobQuery().processInstanceId(pid).timers().singleResult();
                f.engine.getManagementService().executeJob(j.getId());
                assertEquals(exec0, f.count("SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = ?", pid), "cycle " + i);
                assertEquals(open0, f.engine.getRuntimeService().getActivityInstance(pid).getChildActivityInstances().length,
                        "open activities flat, cycle " + i);
                assertTrue(f.count("SELECT COUNT(*) FROM ACT_RU_JOB WHERE PROCESS_INSTANCE_ID_ = ?", pid) <= 1, "jobs flat, cycle " + i);
            }
            assertEquals(36, f.engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pid).activityId("tick").count());
        }
    }
}
