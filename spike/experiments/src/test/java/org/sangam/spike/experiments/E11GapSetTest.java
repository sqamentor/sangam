package org.sangam.spike.experiments;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.experiments.support.ExperimentBase;
import org.sangam.spike.experiments.support.Results;

/**
 * E11 BPMN gap reproduction (SANGAM.md XI.4, CF-13 / R34): every {@code gap-*.bpmn} model is deployed through the
 * adapter and driven with a minimal runtime assertion. When the standard deployment is rejected and the engine has a
 * switch to deploy without BPMN validation (Flowable), the same assertion is re-run on that deployment as a diagnostic
 * ("what does the runtime do with the element?"); that diagnostic never turns a rejection into "works".
 *
 * <p>The classification (works / needs extension / needs engine patch / unsupported) is derived from the measured
 * outcome plus the rule written next to each element below; the JSON keeps both.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E11GapSetTest extends ExperimentBase {

    @FunctionalInterface
    interface Probe {
        /** Starts/drives the model; returns null on correct behaviour, else the observed deviation. */
        String run(Ctx ctx) throws Exception;
    }

    record Gap(String model, String element, Probe probe, String ifRejected, String ifWrong) {
    }

    private int n;

    @BeforeAll
    void setUp() {
        open("E11", "BPMN gap reproduction (GAP set)");
        clock(Instant.parse("2026-11-02T04:30:00Z"));
    }

    private String start(String key) {
        return adapter.startProcess(null, key, key + "-" + (++n), Map.of());
    }

    private List<Gap> gaps() {
        String e = hooks.engine();
        List<Gap> g = new ArrayList<>();

        g.add(new Gap("gap-link-events", "link throw -> link catch (LinkB)", ctx -> {
            String pid = start("gap-link-events");
            ctx.detail("activeAfterStart", active(pid));
            return tasks(pid, "gap_link_probe").size() == 1 ? null : "probe not reached; active=" + active(pid);
        }, e.equals("flowable")
                ? "needs extension: the 8.0.0 BPMN model has no LinkEventDefinition (converter drops it); a custom"
                + " BpmnXMLConverter/parse handler or a pre-deploy model rewrite (link pair -> sequence flow) is needed"
                : "needs extension",
                "needs extension"));

        g.add(new Gap("gap-signal-end", "signal end event (gapSignal) starts catcher process", ctx -> {
            long before = hooks.allInstances(adapter, "gap-signal-end-catcher").size();
            String pid = start("gap-signal-end");
            boolean thrower = ended(pid);
            long after = hooks.allInstances(adapter, "gap-signal-end-catcher").size();
            long probes = tasks(null, "gap_sigEnd_probe").size();
            ctx.detail("throwerEnded", thrower);
            ctx.detail("catcherInstancesCreated", after - before);
            // diagnostic: does the signal START event work at all? (broadcast through the API)
            long b2 = hooks.allInstances(adapter, "gap-signal-end-catcher").size();
            hooks.broadcastSignal(adapter, "gapSignal");
            ctx.detail("catcherInstancesCreatedByApiBroadcast", hooks.allInstances(adapter, "gap-signal-end-catcher").size() - b2);
            if (thrower && after - before == 1 && probes >= 1) {
                return null;
            }
            return "thrower ended=" + thrower + ", catcher instances created by the end event=" + (after - before);
        }, "needs extension", e.equals("flowable")
                ? "needs extension: signal end behaves as a none end; an end-event execution listener calling"
                + " RuntimeService.signalEventReceived (public API) restores the semantics"
                : "needs extension"));

        g.add(new Gap("gap-message-end", "message end event (no implementation)", ctx -> {
            String pid = start("gap-message-end");
            ctx.detail("ended", ended(pid));
            ctx.detail("note", e.equals("operaton")
                    ? "no implementation -> no-op; sending needs operaton:class/expression/delegateExpression/type=external"
                    + " on the event (same extension attributes as a service task)"
                    : "none-end behaviour; nothing sent; no implementation attribute on message end; hook = execution listener");
            return ended(pid) ? null : "instance did not end; active=" + active(pid);
        }, "needs extension", "needs extension"));

        g.add(new Gap("gap-message-throw", "message intermediate throw (no implementation)", ctx -> {
            String pid = start("gap-message-throw");
            ctx.detail("activeAfterStart", active(pid));
            ctx.detail("note", e.equals("operaton")
                    ? "no implementation -> pass-through no-op; sending needs operaton:class/expression/type=external"
                    : "Flowable validator rejects message throw events; sending would need a service task or listener");
            return tasks(pid, "gap_msgThrow_probe").size() == 1 ? null : "probe not reached; active=" + active(pid);
        }, e.equals("flowable")
                ? "needs extension: model as a send/service task, or deploy without validation if the runtime passes through"
                : "needs extension", "needs extension"));

        g.add(new Gap("gap-conditional-start", "process-level conditional start (feePaid == true)", ctx -> {
            List<String> none = hooks.evaluateConditionalStart(adapter, Map.of("feePaid", false));
            ctx.detail("startedWithFalse", none.size());
            List<String> one = hooks.evaluateConditionalStart(adapter, Map.of("feePaid", true));
            ctx.detail("startedWithTrue", one.size());
            long probes = one.isEmpty() ? 0 : tasks(one.get(0), "gap_condStart_probe").size();
            return none.isEmpty() && one.size() == 1 && probes == 1 ? null
                    : "false started " + none.size() + ", true started " + one.size() + ", probe " + probes;
        }, e.equals("flowable")
                ? "needs extension: no conditional START support or API; product layer can evaluate the condition and"
                + " call startProcess (or use a variable-listener/event-registry start) - engine semantics absent"
                : "needs extension",
                "needs extension"));

        g.add(new Gap("gap-compensation-event-subprocess", "compensation event sub-process inside sub-process", ctx -> {
            String pid = start("gap-compensation-event-subprocess");
            complete(ctx, pid, "gap_comp_decide", null, Map.of());
            boolean undo = tasks(pid, "gap_comp_undo").size() == 1;
            ctx.detail("undoTaskAppeared", undo);
            if (!undo) {
                return "gap_comp_undo did not appear; active=" + active(pid) + ", ended=" + ended(pid);
            }
            complete(ctx, pid, "gap_comp_undo", null, Map.of());
            return ended(pid) ? null : "instance did not end after undo; active=" + active(pid);
        }, e.equals("flowable")
                ? "needs engine patch: the validator allows only error/timer/message/signal(/escalation...) event sub-process"
                + " starts and there is no compensation-start behaviour in the 8.0.0 runtime; a compensation BOUNDARY event"
                + " on the sub-process is the supported alternative (model change)"
                : "needs engine patch",
                "needs engine patch"));

        g.add(new Gap("gap-adhoc-subprocess", "ad-hoc sub-process (parallel, completionCondition)", ctx -> {
            String pid = start("gap-adhoc-subprocess");
            List<String> enabled = hooks.adhocEnabledActivities(adapter, pid);
            ctx.detail("enabledActivities", enabled);
            hooks.adhocExecute(adapter, pid, "gap_adhoc_taskA");
            hooks.adhocExecute(adapter, pid, "gap_adhoc_taskB");
            ctx.detail("innerTasksStarted", tasks(pid, "gap_adhoc_taskA").size() + tasks(pid, "gap_adhoc_taskB").size());
            complete(ctx, pid, "gap_adhoc_taskA", null, Map.of("adhocDone", true));
            boolean probe = tasks(pid, "gap_adhoc_probe").size() == 1;
            ctx.detail("taskBCancelled", tasks(pid, "gap_adhoc_taskB").isEmpty());
            return enabled.containsAll(List.of("gap_adhoc_taskA", "gap_adhoc_taskB")) && probe ? null
                    : "enabled=" + enabled + ", probe reached=" + probe;
        }, e.equals("operaton")
                ? "needs engine patch: adHocSubProcess is not parsed by BpmnParse (no behaviour, no runtime API); adding"
                + " it means a new parse path and ActivityBehavior (custom BpmnParseFactory at minimum); CMMN discretionary"
                + " items are the native substitute"
                : "needs engine patch",
                "needs extension"));

        g.add(new Gap("gap-io-mapping", "I/O mapping on external task (in feeBase<-amount, out ioResult<-workerResult)", ctx -> {
            String pid = adapter.startProcess(null, "gap-io-mapping", "gap-io-" + (++n), Map.of("amount", 1234L));
            List<ExternalWork> got = adapter.fetchAndLock("io-worker", "io-mapping", 10, java.time.Duration.ofMinutes(5));
            ExternalWork w = got.stream().filter(x -> pid.equals(x.processInstanceId())).findFirst().orElse(null);
            ctx.require("io-mapping work fetched", w != null);
            ctx.detail("workerSees", new java.util.TreeSet<>(w.variables().keySet()));
            boolean feeBase = Long.valueOf(1234L).equals(toLong(w.variables().get("feeBase")));
            adapter.completeExternal(w.id(), "io-worker", w.lockVersion(), Map.of("workerResult", "R-42", "other", "x"));
            if (active(pid).contains("gap_io_mappedTask")) {
                adapter.executeDueJobs();
                ctx.workaround("executeDueJobs() after completeExternal (async continuation)");
            }
            Map<String, Object> v = adapter.getInstance(pid).variables();
            ctx.detail("processVariablesAfter", new java.util.TreeSet<>(v.keySet()));
            boolean out = "R-42".equals(v.get("ioResult"));
            boolean probe = tasks(pid, "gap_io_probe").size() == 1;
            return feeBase && out && probe ? null
                    : "feeBase seen=" + feeBase + ", ioResult=" + v.get("ioResult") + ", probe=" + probe;
        }, "needs extension", "needs extension"));
        return g;
    }

    private static Long toLong(Object o) {
        return o instanceof Number num ? num.longValue() : null;
    }

    @Test
    void gapSet() {
        String e = hooks.engine();
        for (Gap gap : gaps()) {
            String file = Corpus.resolve(gap.model(), "bpmn", e);
            scenario("E11-" + gap.model(), "GAP set", gap.element(), List.of(file), ctx -> {
                Map<String, byte[]> res = Corpus.resources(e, gap.model() + ".bpmn");
                String deployError = null;
                try {
                    adapter.deploy(null, "e11-" + gap.model(), res);
                } catch (RuntimeException ex) {
                    deployError = Results.error(ex);
                }
                ctx.detail("deployOk", deployError == null);
                ctx.detail("deployError", deployError);
                String classification;
                String runtimeOutcome = null;
                Boolean runtimeCorrect = null;
                if (deployError == null) {
                    try {
                        runtimeOutcome = gap.probe().run(ctx);
                    } catch (UnsupportedOperationException ex) {
                        runtimeOutcome = "no API: " + ex.getMessage();
                    } catch (Throwable ex) {
                        runtimeOutcome = "threw: " + Results.error(ex);
                    }
                    runtimeCorrect = runtimeOutcome == null;
                    classification = runtimeCorrect ? "works" : gap.ifWrong();
                } else {
                    classification = gap.ifRejected();
                    // diagnostic: deploy without validation (Flowable only)
                    try {
                        hooks.deployWithoutValidation(adapter, "e11-novalidation-" + gap.model(), res);
                        String diag;
                        try {
                            diag = gap.probe().run(new Ctx());
                            diag = diag == null ? "runtime behaviour CORRECT once validation is skipped" : diag;
                        } catch (UnsupportedOperationException ex) {
                            diag = "no API: " + ex.getMessage();
                        } catch (Throwable ex) {
                            diag = "threw: " + Results.error(ex);
                        }
                        ctx.detail("withoutValidation", Results.map("deployOk", true, "runtime", diag));
                    } catch (UnsupportedOperationException ex) {
                        ctx.detail("withoutValidation", "not available: " + ex.getMessage());
                    } catch (RuntimeException ex) {
                        ctx.detail("withoutValidation", Results.map("deployOk", false, "error", Results.error(ex)));
                    }
                }
                ctx.detail("runtimeCorrect", runtimeCorrect);
                ctx.detail("runtimeOutcome", runtimeOutcome == null && Boolean.TRUE.equals(runtimeCorrect) ? "as specified" : runtimeOutcome);
                ctx.detail("classification", classification.contains(":") ? classification.substring(0, classification.indexOf(':')) : classification);
                ctx.detail("classificationNote", classification);
                ctx.check("deploys", deployError == null);
                ctx.check("runtime behaviour as specified", Boolean.TRUE.equals(runtimeCorrect));
            });
        }
    }

    @AfterAll
    void tearDown() {
        try {
            Map<String, Object> summary = summary(results.scenarios());
            Map<String, Long> byClass = new java.util.TreeMap<>();
            for (Map<String, Object> s : results.scenarios()) {
                Object c = ((Map<?, ?>) s.get("details")).get("classification");
                byClass.merge(String.valueOf(c), 1L, Long::sum);
            }
            summary.put("classification", byClass);
            results.write(hooks.engine(), adapter.engineId(), summary);
        } finally {
            closeAdapter();
        }
    }
}
