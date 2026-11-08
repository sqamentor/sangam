// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.ports.adhoc.AdHocSubProcessService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * E11 BPMN gap set (spike E11GapSetTest, CF-13 / R34) on the real server. Each gap model is deployed and driven with
 * the Stage B runtime probe; a case passes only when it deploys and behaves as specified.
 *
 * <p>Sangam replacement: the ad-hoc sub-process, rejected by plain Operaton 2.2.0 in Stage B, is supported by the
 * sangam-flowable-ports port (XI.10 consequence "ad-hoc sub-process: an engine patch", ADR-D-16 plugin); its runtime
 * calls are {@link AdHocSubProcessService}, the counterpart of Flowable's {@code executeActivityInAdhocSubProcess}.
 */
class E11GapSetIT extends EngineScenarioSupport {

    @Autowired
    AdHocSubProcessService adHoc;

    private String start(String processKey) {
        return adapter.startProcess(null, processKey, key(processKey), Map.of());
    }

    private void gap(String model, Body probe) {
        scenario("E11-" + model, ctx -> {
            String deployError = null;
            try {
                deployCorpus("e11-" + model, model + ".bpmn");
            } catch (RuntimeException ex) {
                deployError = ex.toString();
            }
            ctx.detail("deployError", deployError);
            ctx.require("deploys", deployError == null);
            probe.run(ctx);
        });
    }

    @Test
    void linkEvents() {
        gap("gap-link-events", ctx -> {
            String pid = start("gap-link-events");
            ctx.check("link catch reached probe; active=" + active(pid), tasks(pid, "gap_link_probe").size() == 1);
        });
    }

    @Test
    void signalEnd() {
        gap("gap-signal-end", ctx -> {
            long before = allInstances("gap-signal-end-catcher").size();
            String pid = start("gap-signal-end");
            long after = allInstances("gap-signal-end-catcher").size();
            ctx.check("thrower ended", ended(pid));
            ctx.check("signal end event started exactly one catcher (got " + (after - before) + ")", after - before == 1);
            ctx.check("catcher reached its probe", !tasks(null, "gap_sigEnd_probe").isEmpty());
        });
    }

    @Test
    void messageEnd() {
        gap("gap-message-end", ctx -> {
            String pid = start("gap-message-end");
            ctx.check("message end without implementation ends the instance; active=" + active(pid), ended(pid));
        });
    }

    @Test
    void messageThrow() {
        gap("gap-message-throw", ctx -> {
            String pid = start("gap-message-throw");
            ctx.check("message throw passes through to probe; active=" + active(pid),
                    tasks(pid, "gap_msgThrow_probe").size() == 1);
        });
    }

    @Test
    void conditionalStart() {
        gap("gap-conditional-start", ctx -> {
            List<ProcessInstance> none = engine.getRuntimeService().createConditionEvaluation()
                    .setVariables(Map.of("feePaid", false)).evaluateStartConditions();
            List<ProcessInstance> one = engine.getRuntimeService().createConditionEvaluation()
                    .setVariables(Map.of("feePaid", true)).evaluateStartConditions();
            ctx.check("feePaid=false starts nothing (got " + none.size() + ")", none.isEmpty());
            ctx.check("feePaid=true starts exactly one (got " + one.size() + ")", one.size() == 1);
            ctx.check("started instance waits at probe",
                    one.size() == 1 && tasks(one.get(0).getId(), "gap_condStart_probe").size() == 1);
        });
    }

    @Test
    void compensationEventSubprocess() {
        gap("gap-compensation-event-subprocess", ctx -> {
            String pid = start("gap-compensation-event-subprocess");
            complete(ctx, pid, "gap_comp_decide", null, Map.of());
            ctx.require("gap_comp_undo appeared; active=" + active(pid), tasks(pid, "gap_comp_undo").size() == 1);
            complete(ctx, pid, "gap_comp_undo", null, Map.of());
            ctx.check("instance ended after undo; active=" + active(pid), ended(pid));
        });
    }

    @Test
    void adHocSubprocess() {
        gap("gap-adhoc-subprocess", ctx -> {
            String pid = start("gap-adhoc-subprocess");
            List<AdHocSubProcessService.EnabledActivity> enabled = adHoc.getEnabledActivities(pid);
            List<String> ids = enabled.stream().map(AdHocSubProcessService.EnabledActivity::activityId).toList();
            ctx.detail("enabledActivities", ids);
            ctx.require("both inner tasks enabled", ids.containsAll(List.of("gap_adhoc_taskA", "gap_adhoc_taskB")));
            String scope = enabled.get(0).adHocActivityInstanceId();
            adHoc.executeActivity(pid, scope, "gap_adhoc_taskA");
            adHoc.executeActivity(pid, scope, "gap_adhoc_taskB");
            ctx.check("both inner tasks started", tasks(pid, "gap_adhoc_taskA").size() == 1
                    && tasks(pid, "gap_adhoc_taskB").size() == 1);
            complete(ctx, pid, "gap_adhoc_taskA", null, Map.of("adhocDone", true));
            ctx.check("completion condition completed the ad-hoc sub-process: probe reached",
                    tasks(pid, "gap_adhoc_probe").size() == 1);
            ctx.check("task B cancelled (cancelRemainingInstances)", tasks(pid, "gap_adhoc_taskB").isEmpty());
        });
    }

    @Test
    void ioMapping() {
        gap("gap-io-mapping", ctx -> {
            String pid = adapter.startProcess(null, "gap-io-mapping", key("gap-io"), Map.of("amount", 1234L));
            ExternalWork w = null;
            for (ExternalWork x : adapter.fetchAndLock("io-worker", "io-mapping", 10, Duration.ofMinutes(5))) {
                if (pid.equals(x.processInstanceId()) && w == null) {
                    w = x;
                } else {
                    adapter.unlockExternal(x.id(), "io-worker", x.lockVersion());
                }
            }
            ctx.require("io-mapping work fetched", w != null);
            ctx.detail("workerSees", new TreeSet<>(w.variables().keySet()));
            ctx.check("worker sees feeBase=1234", w.variables().get("feeBase") instanceof Number n && n.longValue() == 1234L);
            adapter.completeExternal(w.id(), "io-worker", w.lockVersion(), Map.of("workerResult", "R-42", "other", "x"));
            if (active(pid).contains("gap_io_mappedTask")) {
                adapter.executeDueJobs();
                ctx.workaround("executeDueJobs() after completeExternal (async continuation)");
            }
            Map<String, Object> v = adapter.getInstance(pid).variables();
            ctx.check("output mapping ioResult=R-42 (got " + v.get("ioResult") + ")", "R-42".equals(v.get("ioResult")));
            ctx.check("probe reached", tasks(pid, "gap_io_probe").size() == 1);
        });
    }
}
