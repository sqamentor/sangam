// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import java.util.ArrayList;
import java.util.List;

import org.sangam.core.engine.RoutingPolicy;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * ADR-D-25 on a BPMN DOM (model tools: validator, importer). The rule itself is the shared engine-neutral
 * {@link RoutingPolicy}; this class only reads the model's exclusive gateways and their outgoing flows.
 */
public final class XorRoutingDom {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** One exclusive gateway with its policy attributes and outgoing flows. */
    public record Gateway(String id, String policy, String policyVersion, String defaultFlow,
                          List<RoutingPolicy.FlowSpec> flows) {

        /** Conditional, non-default outgoing flows. */
        public long conditionalFlows() {
            return flows.stream().filter(f -> f.hasCondition() && !f.flowId().equals(defaultFlow)).count();
        }

        /** Native engines (Flowable, Camunda, Operaton) pick the first true of these in document order. */
        public boolean nativeFirstMatchSensitive() {
            return (policy == null || policy.isBlank()) && conditionalFlows() >= 2;
        }

        public List<String> problems() {
            return RoutingPolicy.validate(id, policy, policyVersion, flows, defaultFlow);
        }
    }

    private XorRoutingDom() {
    }

    /** Every exclusive gateway in the document (any nesting level). */
    public static List<Gateway> gateways(Element root) {
        List<Gateway> out = new ArrayList<>();
        scan(root, out);
        return out;
    }

    private static void scan(Element scope, List<Gateway> out) {
        List<Element> kids = children(scope);
        for (Element g : kids) {
            if (!"exclusiveGateway".equals(local(g)) || !BPMN_NS.equals(g.getNamespaceURI())) {
                continue;
            }
            String id = g.getAttribute("id");
            List<RoutingPolicy.FlowSpec> flows = new ArrayList<>();
            for (Element f : kids) {
                if ("sequenceFlow".equals(local(f)) && id.equals(f.getAttribute("sourceRef"))) {
                    boolean cond = false;
                    for (Element c : children(f)) {
                        if ("conditionExpression".equals(local(c)) && !c.getTextContent().isBlank()) {
                            cond = true;
                        }
                    }
                    String p = f.getAttributeNS(RoutingPolicy.NS_URI, RoutingPolicy.ATTR_PRIORITY);
                    flows.add(new RoutingPolicy.FlowSpec(f.getAttribute("id"), cond, p.isEmpty() ? null : p));
                }
            }
            String policy = g.getAttributeNS(RoutingPolicy.NS_URI, RoutingPolicy.ATTR_POLICY);
            String version = g.getAttributeNS(RoutingPolicy.NS_URI, RoutingPolicy.ATTR_POLICY_VERSION);
            String def = g.getAttribute("default");
            out.add(new Gateway(id, policy.isEmpty() ? null : policy, version.isEmpty() ? null : version,
                    def.isEmpty() ? null : def, flows));
        }
        for (Element k : kids) {
            scan(k, out);
        }
    }

    private static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element c) {
                out.add(c);
            }
        }
        return out;
    }

    private static String local(Element e) {
        return e.getLocalName() == null ? e.getTagName() : e.getLocalName();
    }
}
