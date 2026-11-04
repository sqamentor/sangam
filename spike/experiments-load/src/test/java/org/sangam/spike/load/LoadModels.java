package org.sangam.spike.load;

import java.nio.charset.StandardCharsets;

/**
 * Load-test models generated in code (standard BPMN 2.0.2 except the one vendor attribute an external task needs).
 * They are not part of the corpus; the corpus models (WF-1, WF-2) are loaded through {@code Corpus}.
 */
public final class LoadModels {

    public static final String TASKS_KEY = "e6-fanout-tasks";
    public static final String MSGS_KEY = "e6-fanout-msgs";
    public static final String R1_KEY = "e9-r1";
    public static final String AFTER = "after";
    public static final String END = "end";

    private LoadModels() {
    }

    /**
     * One root waiting on {@code n} parallel branches, each a user task ({@code messages=false}, ids b_0..) or a
     * message catch event (message name e6msg_i); all join in one parallel gateway, then one user task "after".
     */
    public static byte[] fanout(int n, boolean messages) {
        String key = messages ? MSGS_KEY : TASKS_KEY;
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"d-").append(key)
                .append("\" targetNamespace=\"http://sangam.org/spike/load\">\n");
        if (messages) {
            for (int i = 0; i < n; i++) {
                sb.append("  <message id=\"msg_").append(i).append("\" name=\"e6msg_").append(i).append("\"/>\n");
            }
        }
        sb.append("  <process id=\"").append(key).append("\" isExecutable=\"true\">\n")
                .append("    <startEvent id=\"start\"/>\n")
                .append("    <sequenceFlow id=\"f_s\" sourceRef=\"start\" targetRef=\"fork\"/>\n")
                .append("    <parallelGateway id=\"fork\"/>\n");
        for (int i = 0; i < n; i++) {
            sb.append("    <sequenceFlow id=\"f_in_").append(i).append("\" sourceRef=\"fork\" targetRef=\"b_").append(i).append("\"/>\n");
            if (messages) {
                sb.append("    <intermediateCatchEvent id=\"b_").append(i).append("\"><messageEventDefinition messageRef=\"msg_")
                        .append(i).append("\"/></intermediateCatchEvent>\n");
            } else {
                sb.append("    <userTask id=\"b_").append(i).append("\" name=\"Branch ").append(i).append("\"/>\n");
            }
            sb.append("    <sequenceFlow id=\"f_out_").append(i).append("\" sourceRef=\"b_").append(i).append("\" targetRef=\"join\"/>\n");
        }
        sb.append("    <parallelGateway id=\"join\"/>\n")
                .append("    <sequenceFlow id=\"f_j\" sourceRef=\"join\" targetRef=\"after\"/>\n")
                .append("    <userTask id=\"after\" name=\"After join\"/>\n")
                .append("    <sequenceFlow id=\"f_e\" sourceRef=\"after\" targetRef=\"end\"/>\n")
                .append("    <endEvent id=\"end\"/>\n")
                .append("  </process>\n</definitions>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** WF-R1: start → one external work item (topic "r1") → end. The only engine-specific part is the task type. */
    public static byte[] r1(String engine) {
        String ns;
        String task;
        if ("flowable".equals(engine)) {
            ns = "xmlns:flowable=\"http://flowable.org/bpmn\"";
            task = "<serviceTask id=\"work\" name=\"R1 work\" flowable:type=\"external-worker\" flowable:topic=\"r1\"/>";
        } else {
            ns = "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"";
            task = "<serviceTask id=\"work\" name=\"R1 work\" operaton:type=\"external\" operaton:topic=\"r1\"/>";
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" " + ns
                + " id=\"d-r1\" targetNamespace=\"http://sangam.org/spike/load\">\n"
                + "  <process id=\"" + R1_KEY + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"start\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"work\"/>\n"
                + "    " + task + "\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"work\" targetRef=\"end\"/>\n"
                + "    <endEvent id=\"end\"/>\n"
                + "  </process>\n</definitions>\n";
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
