// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.builder.ServiceTaskBuilder;
import org.sangam.ports.bpmn.SangamBpmn;
import org.sangam.ports.events.SendEventDelegate;
import org.sangam.ports.support.SecureXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CMMN task types Operaton lacks (FLOWABLE-INTO-OPERATON FC-11 external worker for CMMN, FLOWABLE-INTO-OPERATON FC-58 service/HTTP/send-event tasks), delivered by
 * the gap list's pattern route: a CMMN {@code processTask} calls a generated one-step BPMN process that does the
 * work. Mark the process task:
 * <pre>{@code
 * <processTask id="pt" sangam:taskType="external-worker" sangam:topic="kyc-check"/>
 * <processTask id="pt" sangam:taskType="service" sangam:delegateExpression="${myBean}"/>
 * <processTask id="pt" sangam:taskType="http" sangam:topic="sangam.http"/>   (WS-INTEGRATION HTTP worker)
 * <processTask id="pt" sangam:taskType="send-event" sangam:eventType="orderPlaced" sangam:channels="kafkaOut"/>
 * }</pre>
 * {@link #enrich} (called by the app deployment before the engine deployment) sets {@code processRef} to a generated
 * key, adds {@code operaton:in/out variables="all"}, keeps the task blocking, and adds the generated BPMN to the same
 * deployment, so the CMMN and its helpers are versioned and hash-pinned together (R43). The external worker of a CMMN
 * case therefore uses the normal Sangam worker API, with fencing and lockVersion (R45). Native case-execution external
 * tasks would need an engine patch (external-task entity is process-only); not done (stage E on demand).
 */
public final class CmmnTaskPatterns {

    private static final String CMMN_NS = "http://www.omg.org/spec/CMMN/20151109/MODEL";
    private static final String OPERATON_NS = "http://operaton.org/schema/1.0/cmmn";

    private CmmnTaskPatterns() {
    }

    public static BpmnModelInstance externalWorkerProcess(String key, String topic) {
        return Bpmn.createExecutableProcess(key).name("CMMN external worker " + topic)
                .startEvent().serviceTask("work").operatonExternalTask(topic).endEvent().done();
    }

    public static BpmnModelInstance serviceProcess(String key, String delegateExpression) {
        return Bpmn.createExecutableProcess(key).name("CMMN service task")
                .startEvent().serviceTask("work").operatonDelegateExpression(delegateExpression).endEvent().done();
    }

    public static BpmnModelInstance sendEventProcess(String key, String eventKey, String channels) {
        ServiceTaskBuilder b = Bpmn.createExecutableProcess(key).name("CMMN send event " + eventKey)
                .startEvent().serviceTask("send").operatonClass(SendEventDelegate.class.getName())
                .operatonInputParameter("sangamEventKey", eventKey).operatonInputParameter("sangamChannels", channels);
        return b.endEvent().done();
    }

    /** Rewrites CMMN resources that use {@code sangam:taskType} and adds the generated BPMN resources. */
    public static Map<String, byte[]> enrich(Map<String, byte[]> resources) {
        Map<String, byte[]> out = new LinkedHashMap<>(resources);
        for (Map.Entry<String, byte[]> r : resources.entrySet()) {
            String name = r.getKey().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".cmmn") || name.endsWith(".cmmn11.xml") || name.endsWith(".cmmn.xml"))) {
                continue;
            }
            Document doc = SecureXml.parse(r.getValue());
            NodeList tasks = doc.getElementsByTagNameNS(CMMN_NS, "processTask");
            boolean changed = false;
            for (int i = 0; i < tasks.getLength(); i++) {
                Element pt = (Element) tasks.item(i);
                String type = SangamBpmn.attrDom(pt, "taskType");
                if (type.isEmpty()) {
                    continue;
                }
                String key = pt.getAttribute("processRef").isEmpty() ? "cmmn-" + pt.getAttribute("id") : pt.getAttribute("processRef");
                BpmnModelInstance helper = switch (type) {
                    case "external-worker", "http" -> externalWorkerProcess(key, require(pt, "topic"));
                    case "service" -> serviceProcess(key, require(pt, "delegateExpression"));
                    case "send-event" -> sendEventProcess(key, require(pt, "eventType"), require(pt, "channels"));
                    default -> throw new IllegalArgumentException("processTask " + pt.getAttribute("id") + ": unknown sangam:taskType " + type);
                };
                pt.setAttribute("processRef", key);
                if (pt.getAttribute("isBlocking").isEmpty()) {
                    pt.setAttribute("isBlocking", "true");
                }
                ensureVariableMapping(doc, pt);
                ByteArrayOutputStream bpmn = new ByteArrayOutputStream();
                Bpmn.writeModelToStream(bpmn, helper);
                out.put(key + ".bpmn", bpmn.toByteArray());
                changed = true;
            }
            if (changed) {
                out.put(r.getKey(), SecureXml.write(doc));
            }
        }
        return out;
    }

    private static String require(Element pt, String attr) {
        String v = SangamBpmn.attrDom(pt, attr);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("processTask " + pt.getAttribute("id") + ": sangam:" + attr + " is required");
        }
        return v;
    }

    private static void ensureVariableMapping(Document doc, Element pt) {
        Element ext = null;
        NodeList kids = pt.getElementsByTagNameNS(CMMN_NS, "extensionElements");
        if (kids.getLength() > 0) {
            ext = (Element) kids.item(0);
        } else {
            ext = doc.createElementNS(CMMN_NS, "cmmn:extensionElements");
            pt.insertBefore(ext, pt.getFirstChild());
        }
        if (ext.getElementsByTagNameNS(OPERATON_NS, "in").getLength() == 0) {
            Element in = doc.createElementNS(OPERATON_NS, "operaton:in");
            in.setAttribute("variables", "all");
            ext.appendChild(in);
        }
        if (ext.getElementsByTagNameNS(OPERATON_NS, "out").getLength() == 0) {
            Element o = doc.createElementNS(OPERATON_NS, "operaton:out");
            o.setAttribute("variables", "all");
            ext.appendChild(o);
        }
    }
}
