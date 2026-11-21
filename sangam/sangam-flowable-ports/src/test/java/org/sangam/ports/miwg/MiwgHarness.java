// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.miwg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.sangam.ports.bpmn.SangamModelValidator;
import org.sangam.ports.support.SecureXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Runs one MIWG reference model through four stages and returns a deterministic result row:
 * <ol>
 *   <li>IMPORT: the Sangam pre-deploy validator (the same rules as every deployment path) finds no error;</li>
 *   <li>SCHEMA: Operaton's BPMN model API reads it (XSD-validating parser);</li>
 *   <li>DEPLOY: a real deployment, unchanged bytes; result = number of executable process definitions, or the
 *       refusal;</li>
 *   <li>RUN: the models with an execution scenario are started and driven to an end with assertions. Service, send and
 *       business-rule tasks whose implementation is vendor specific are bound to Sangam external tasks (topic
 *       {@code miwg}), the documented Sangam binding for work done outside the engine; nothing else in the model is
 *       changed. Every other model is listed as not applicable with its reason.</li>
 * </ol>
 */
final class MiwgHarness {

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    static final String TOPIC = "miwg";

    record Result(String model, String importResult, String schema, String deploy, String run, String note) {
        String row() {
            return String.join("\t", importResult, schema, deploy, run);
        }
    }

    private final ProcessEngine engine;
    private final IntSupplier dueJobs;
    private final SangamModelValidator validator = new SangamModelValidator(new SangamModelValidator.Options(true, true));

    MiwgHarness(ProcessEngine engine, IntSupplier dueJobs) {
        this.engine = engine;
        this.dueJobs = dueJobs;
    }

    Result check(String model) {
        byte[] bytes = MiwgModels.bytes(model);
        String imp = importResult(model, bytes);
        String schema = schema(bytes);
        String deploy = deploy(model, bytes);
        String run;
        String note = MiwgScenarios.NOT_APPLICABLE.get(model);
        if (MiwgScenarios.RUNS.containsKey(model)) {
            run = run(model, bytes);
            note = MiwgScenarios.RUNS.get(model);
        } else {
            run = "N/A";
        }
        return new Result(model, imp, schema, deploy, run, note == null ? "" : note);
    }

    private String importResult(String model, byte[] bytes) {
        List<SangamModelValidator.Finding> f = validator.validate(model + ".bpmn", bytes);
        Set<String> errors = f.stream().filter(x -> x.severity() == SangamModelValidator.Severity.ERROR)
                .map(x -> x.code() + (x.elementId() == null ? "" : "#" + x.elementId())).collect(Collectors.toCollection(TreeSet::new));
        Set<String> warnings = f.stream().filter(x -> x.severity() == SangamModelValidator.Severity.WARNING)
                .map(SangamModelValidator.Finding::code).collect(Collectors.toCollection(TreeSet::new));
        return errors.isEmpty() ? "PASS" + (warnings.isEmpty() ? "" : " (warnings " + String.join(",", warnings) + ")")
                : "FAIL " + String.join(",", errors);
    }

    private static String schema(byte[] bytes) {
        try {
            Bpmn.readModelFromStream(new ByteArrayInputStream(bytes));
            return "PASS";
        } catch (RuntimeException e) {
            return "FAIL " + firstLine(e);
        }
    }

    private String deploy(String model, byte[] bytes) {
        try {
            Deployment d = engine.getRepositoryService().createDeployment().name("miwg-" + model)
                    .addInputStream(model + ".bpmn", new ByteArrayInputStream(bytes)).deploy();
            long n = engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(d.getId()).count();
            return "PASS " + n + " executable";
        } catch (RuntimeException e) {
            return "FAIL " + firstLine(e);
        }
    }

    private String run(String model, byte[] bytes) {
        try {
            byte[] bound = bindVendorImplementations(bytes);
            engine.getRepositoryService().createDeployment().name("miwg-run-" + model)
                    .addInputStream(model + ".run.bpmn", new ByteArrayInputStream(bound)).deploy();
            List<String> failed = new ArrayList<>();
            switch (model) {
                case "C.1.0" -> runC10(failed);
                case "C.9.1" -> runC91(failed);
                default -> failed.add("no scenario");
            }
            return failed.isEmpty() ? "PASS" : "FAIL " + String.join("; ", failed);
        } catch (RuntimeException e) {
            return "FAIL " + firstLine(e);
        } finally {
            ClockUtil.reset();
        }
    }

    // ------------------------------------------------------------------ scenarios

    /** C.1.0 invoice: message start, assign approver, reject, clarify, approve, bank transfer, archive (external). */
    private void runC10(List<String> failed) {
        String pid = engine.getRuntimeService().createMessageCorrelation("invoice-received-C.1.0")
                .setVariable("approver", "demo").correlateStartMessage().getId();
        complete(pid, "assignApprover", Map.of(), failed);
        complete(pid, "approveInvoice", Map.of("approved", false), failed);
        complete(pid, "reviewInvoice", Map.of("clarified", "yes"), failed);
        complete(pid, "approveInvoice", Map.of("approved", true), failed);
        complete(pid, "prepareBankTransfer", Map.of(), failed);
        completeExternal(pid, failed);
        expectEnded(pid, "invoiceProcessed", failed);
    }

    /** C.9.1 document request: external e-mail, receive the document by message; and the daily reminder timer. */
    private void runC91(List<String> failed) {
        String pid = engine.getRuntimeService().startProcessInstanceByKey("requestDocument_en").getId();
        dueJobs.getAsInt();
        completeExternal(pid, failed);
        engine.getRuntimeService().createMessageCorrelation(messageName("Message_1")).processInstanceId(pid).correlate();
        dueJobs.getAsInt();
        expectEnded(pid, "EndEvent_GotDocument", failed);

        String pid2 = engine.getRuntimeService().startProcessInstanceByKey("requestDocument_en").getId();
        dueJobs.getAsInt();
        completeExternal(pid2, failed);
        ClockUtil.setCurrentTime(Date.from(ClockUtil.getCurrentTime().toInstant().plus(Duration.ofDays(1)).plusSeconds(60)));
        dueJobs.getAsInt();
        completeExternal(pid2, failed);   // the reminder e-mail (non-interrupting daily timer)
        List<String> ends = history(pid2);
        if (!ends.contains("EndEvent_ReminderSent")) {
            failed.add("daily timer did not send the reminder; history " + ends);
        }
        engine.getRuntimeService().deleteProcessInstance(pid2, "miwg harness");
    }

    private String messageName(String messageId) {
        return engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("requestDocument_en")
                .latestVersion().list().stream().findFirst()
                .map(pd -> engine.getRepositoryService().getBpmnModelInstance(pd.getId()))
                .map(m -> ((org.operaton.bpm.model.bpmn.instance.Message) m.getModelElementById(messageId)).getName())
                .orElseThrow();
    }

    private void complete(String pid, String key, Map<String, Object> vars, List<String> failed) {
        Task t = engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey(key).singleResult();
        if (t == null) {
            failed.add("no task " + key);
            return;
        }
        engine.getTaskService().complete(t.getId(), vars);
        dueJobs.getAsInt();
    }

    private void completeExternal(String pid, List<String> failed) {
        List<LockedExternalTask> got = engine.getExternalTaskService().fetchAndLock(10, "miwg-harness")
                .topic(TOPIC, 60_000).topic("emailService", 60_000).execute();
        for (LockedExternalTask t : got) {
            if (!pid.equals(t.getProcessInstanceId())) {
                engine.getExternalTaskService().unlock(t.getId());
            }
        }
        got = got.stream().filter(t -> pid.equals(t.getProcessInstanceId())).toList();
        if (got.size() != 1) {
            failed.add("expected one external task, got " + got.size());
            return;
        }
        engine.getExternalTaskService().complete(got.get(0).getId(), "miwg-harness");
        dueJobs.getAsInt();
    }

    private void expectEnded(String pid, String endEvent, List<String> failed) {
        boolean ended = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count() == 0;
        List<String> h = history(pid);
        if (!ended || !h.contains(endEvent)) {
            failed.add("expected end at " + endEvent + "; ended=" + ended + " history " + h);
        }
    }

    private List<String> history(String pid) {
        return engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pid)
                .orderByHistoricActivityInstanceStartTime().asc().list().stream()
                .map(HistoricActivityInstance::getActivityId).toList();
    }

    // ------------------------------------------------------------------ binding

    /**
     * Binds service, send and business-rule tasks that carry no engine implementation (or a vendor delegate) to the
     * Sangam external-task topic {@value #TOPIC}. Tasks already bound to an external topic keep their topic. A
     * definitions-level XPath declaration is removed (owner ruling F3 refuses it at deploy): this is a visible port of the
     * test copy, done only for run scenarios whose expressions are written in JUEL ({@code ${...}}), as in C.1.0; the
     * unchanged file is still refused in the DEPLOY stage.
     */
    static byte[] bindVendorImplementations(byte[] bytes) {
        Document doc = SecureXml.parse(bytes);
        if (org.sangam.ports.bpmn.SangamModelValidator.isXPath(doc.getDocumentElement().getAttribute("expressionLanguage"))) {
            doc.getDocumentElement().removeAttribute("expressionLanguage");
        }
        for (String tag : List.of("serviceTask", "sendTask", "businessRuleTask")) {
            NodeList nl = doc.getElementsByTagNameNS(BPMN_NS, tag);
            for (int i = 0; i < nl.getLength(); i++) {
                Element e = (Element) nl.item(i);
                if ("external".equals(e.getAttributeNS(CAMUNDA_NS, "type"))) {
                    continue;
                }
                for (String a : List.of("class", "delegateExpression", "expression", "decisionRef", "resultVariable")) {
                    e.removeAttributeNS(CAMUNDA_NS, a);
                }
                e.setAttributeNS(CAMUNDA_NS, "camunda:type", "external");
                e.setAttributeNS(CAMUNDA_NS, "camunda:topic", TOPIC);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var t = TransformerFactory.newDefaultInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (javax.xml.transform.TransformerException e) {
            throw new IllegalStateException(e);
        }
    }

    static String firstLine(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = String.valueOf(e.getMessage());
        String r = String.valueOf(root.getMessage());
        String s = (m.equals(r) ? m : m + " / " + r).replaceAll("\\s+", " ").trim();
        s = s.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>");
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }
}
