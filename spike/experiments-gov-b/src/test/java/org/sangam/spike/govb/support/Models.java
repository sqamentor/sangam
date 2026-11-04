package org.sangam.spike.govb.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the E16-b/E17-b models from {@code src/test/resources/govb/models}. Models are standard BPMN 2.0.2 / DMN 1.3 /
 * CMMN 1.1. Where an element cannot be written in the standard so that the engine executes it, the file holds a
 * token {@code {{NAME args}}} that is rendered per engine; every token rendered is one recorded VARIANT element.
 *
 * <ul>
 *   <li>{@code {{NS}}} vendor namespace on {@code <definitions>} (only emitted when the file has variant tokens)</li>
 *   <li>{@code {{EXT topic}}} external task attributes (corpus V-02)</li>
 *   <li>{@code {{DMN id|name|decisionKey|out1,out2}}} DMN call from BPMN, single result to process variables (V-01)</li>
 *   <li>{@code {{DMNCOLLECT id|name|decisionKey}}} DMN call with a COLLECT result into variable {@code decisionKey}</li>
 *   <li>{@code {{CALLED key|tenant|defIdVar}}} call activity target in ANOTHER tenant</li>
 *   <li>{@code {{CALLIO in=a>a,b>b;out=c>d}}} call activity variable in/out mapping</li>
 * </ul>
 */
public final class Models {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Z]+)(?: ([^}]*))?}}");

    public record Rendered(String name, byte[] bytes, List<String> variants) {
    }

    private Models() {
    }

    public static String raw(String file) {
        try (InputStream in = Models.class.getResourceAsStream("/govb/models/" + file)) {
            if (in == null) {
                throw new IllegalArgumentException("no model " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Rendered render(String engine, String file) {
        String xml = raw(file);
        boolean op = "operaton".equals(engine);
        boolean cmmn = file.endsWith(".cmmn");
        List<String> variants = new ArrayList<>();
        Matcher m = TOKEN.matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String args = m.group(2) == null ? "" : m.group(2).trim();
            String out = switch (name) {
                case "NS" -> "";
                case "EXT" -> {
                    variants.add(file + ": external task topic '" + args + "' (V-02 style: operaton:type=external"
                            + " / flowable:type=external-worker)");
                    yield op ? "operaton:type=\"external\" operaton:topic=\"" + args + "\""
                            : "flowable:type=\"external-worker\" flowable:topic=\"" + args + "\"";
                }
                case "DMN" -> {
                    String[] a = args.split("\\|");
                    variants.add(file + ": DMN call '" + a[2] + "' outputs " + a[3] + " (V-01 style: businessRuleTask"
                            + " operaton:decisionRef + output mapping / serviceTask flowable:type=dmn)");
                    yield op ? operatonDmn(a[0], a[1], a[2], a[3].split(",")) : flowableDmn(a[0], a[1], a[2]);
                }
                case "DMNCOLLECT" -> {
                    String[] a = args.split("\\|");
                    variants.add(file + ": DMN COLLECT call '" + a[2] + "' (operaton:mapDecisionResult=collectEntries"
                            + " -> List / flowable:type=dmn -> JSON array variable named after the decision)");
                    yield op ? "<businessRuleTask id=\"" + a[0] + "\" name=\"" + a[1] + "\" operaton:decisionRef=\""
                            + a[2] + "\" operaton:mapDecisionResult=\"collectEntries\" operaton:resultVariable=\"" + a[2]
                            + "\"/>" : flowableDmn(a[0], a[1], a[2]);
                }
                case "CALLED" -> {
                    String[] a = args.split("\\|");
                    variants.add(file + ": call activity to '" + a[0] + "' in tenant " + a[1]
                            + " (operaton:calledElementTenantId / flowable:calledElementType=id + definition id"
                            + " variable " + a[2] + ")");
                    yield op ? "calledElement=\"" + a[0] + "\" operaton:calledElementTenantId=\"" + a[1] + "\""
                            : "calledElement=\"${" + a[2] + "}\" flowable:calledElementType=\"id\"";
                }
                case "CALLIO" -> {
                    variants.add(file + ": call activity in/out mapping " + args
                            + " (operaton:in/out / flowable:in/out)");
                    yield callIo(op ? "operaton" : "flowable", args);
                }
                default -> throw new IllegalArgumentException("unknown token " + name + " in " + file);
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        String result = sb.toString();
        if (!variants.isEmpty()) {
            String ns = cmmn ? (op ? " xmlns:operaton=\"http://operaton.org/schema/1.0/cmmn\""
                    : " xmlns:flowable=\"http://flowable.org/cmmn\"")
                    : (op ? " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\""
                    : " xmlns:flowable=\"http://flowable.org/bpmn\"");
            result = result.replaceFirst("<definitions ", "<definitions" + ns + " ");
        }
        return new Rendered(file, result.getBytes(StandardCharsets.UTF_8), variants);
    }

    private static String operatonDmn(String id, String name, String key, String[] outs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<businessRuleTask id=\"").append(id).append("\" name=\"").append(name)
                .append("\" operaton:decisionRef=\"").append(key).append("\"><extensionElements><operaton:inputOutput>");
        for (String o : outs) {
            sb.append("<operaton:outputParameter name=\"").append(o).append("\">${decisionResult.getSingleResult().")
                    .append(o).append("}</operaton:outputParameter>");
        }
        sb.append("</operaton:inputOutput></extensionElements></businessRuleTask>");
        return sb.toString();
    }

    private static String flowableDmn(String id, String name, String key) {
        return "<serviceTask id=\"" + id + "\" name=\"" + name + "\" flowable:type=\"dmn\"><extensionElements>"
                + "<flowable:field name=\"decisionTableReferenceKey\"><flowable:string>" + key
                + "</flowable:string></flowable:field>"
                + "<flowable:field name=\"decisionTaskThrowErrorOnNoHits\"><flowable:string>false</flowable:string>"
                + "</flowable:field></extensionElements></serviceTask>";
    }

    private static String callIo(String prefix, String args) {
        StringBuilder sb = new StringBuilder("<extensionElements>");
        for (String part : args.split(";")) {
            String[] kv = part.split("=", 2);
            String dir = kv[0].trim();
            for (String pair : kv[1].split(",")) {
                String[] st = pair.split(">");
                sb.append('<').append(prefix).append(':').append(dir).append(" source=\"").append(st[0].trim())
                        .append("\" target=\"").append(st[1].trim()).append("\"/>");
            }
        }
        return sb.append("</extensionElements>").toString();
    }
}
