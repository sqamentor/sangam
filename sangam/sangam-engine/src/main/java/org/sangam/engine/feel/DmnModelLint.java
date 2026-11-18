// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.persistence.deploy.Deployer;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceEntity;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * ADR-D-26 deploy-time DMN rules (R44), run for every {@code .dmn} resource before Operaton parses it:
 * <ul>
 *   <li><b>SG-33 error</b>: {@code typeRef="double"} or {@code "float"} on a decision-table input, output, or a
 *       decision variable whose name, label or input expression is money (amount, value, cost, fee, limit, budget,
 *       price, charge, ...). Binary floating point cannot hold decimal money; use {@code long} (minor units) or
 *       {@code number} (exact decimal).</li>
 *   <li><b>SG-33 warning</b>: any other {@code double}/{@code float}: {@code number} is recommended.</li>
 *   <li><b>SG-35 error</b> (owner ruling G1, 2026-11-18; a warning before): an equality test on a zoned {@code date and time}. DMN 1.3 compares date-times on the
 *       time line (OMG issue DMN13-35), but the pinned feel-scala engine's {@code =} also compares the zone, so
 *       the same instant in two zones is "not equal". Compare instants with {@code <}, {@code >}, a range, or
 *       subtraction ({@code a - b = duration("PT0S")}).</li>
 *   <li><b>Decision 158 warning</b> {@code SANGAM-DMN-TIME-IANA}: a time literal with an IANA zone id, deprecated as
 *       of DMN 1.6.</li>
 *   <li><b>Owner DEC-5 error</b> {@code SANGAM-DMN-MONEY-ROUNDING}: {@code decimal(...)} in an output entry, default
 *       output entry or literal expression whose output is money. DMN never rounds money; the financial core does.</li>
 *   <li><b>Owner D2 error</b> {@code SANGAM-DMN-UNSUPPORTED}: business knowledge models, decision services, boxed
 *       contexts, relations, invocations and function definitions are not supported.</li>
 * </ul>
 */
public final class DmnModelLint {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DmnModelLint.class);

    /** Money words; matched as a word or a camelCase / snake_case part of a name. */
    static final Pattern MONEY = Pattern.compile("(?i)(amount|value|cost|fee|limit|budget|price|charge|tariff|premium"
            + "|salary|wage|pay|payment|pension|fine|penalty|balance|credit|debit|entitlement|grant|subsidy|tax|rupee"
            + "|paise|money|ceiling|compensation|escalation|ld|outlay|expenditure|sanction|threshold|cap|gratuity|commutation"
            + "|emoluments|emolument|allowance|recovery|refund)");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern FLOAT_TYPE = Pattern.compile("(?i)double|float");
    private static final Pattern ZONED_DT_EQ = Pattern.compile(
            "(?i)(=|!=)\\s*(date and time\\(\\s*\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\"|@\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\")"
            + "|(date and time\\(\\s*\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\"\\s*\\)|@\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\")\\s*(=|!=)");

    /**
     * Decision 158: a time literal (no date part) whose zone is an IANA id, {@code time("10:30:00@Europe/Paris")} or
     * {@code @"10:30:00@Europe/Paris"}; deprecated as of DMN 1.6 (date-time literals with IANA ids are not).
     */
    static final Pattern TIME_IANA = Pattern.compile(
            "(?i)(time\\(\\s*\"|@\")\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?@[A-Za-z][A-Za-z0-9_+\\-]*(/[A-Za-z0-9_+\\-]+)*\"");

    /** Owner D2: DMN elements Sangam does not support; deploy refuses them. */
    static final String[] UNSUPPORTED = {"businessKnowledgeModel", "decisionService", "context", "relation",
        "invocation", "functionDefinition"};
    /** DEC-5 and the integrator's floor/ceiling ruling: the FEEL rounding functions, matched outside string literals. */
    private static final Pattern ROUNDING_CALL = Pattern.compile("(?<![\\w.])(decimal|floor|ceiling)\\s*\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private DmnModelLint() {
    }

    public record Finding(boolean error, String code, String resource, String element, String message) {
        @Override
        public String toString() {
            return code + " " + resource + " " + element + ": " + message;
        }
    }

    public static List<Finding> check(String resource, byte[] xml) {
        return check(resource, xml, Set.of());
    }

    /**
     * As {@link #check(String, byte[])}, with the money variables the pack declares ({@code pack.json}
     * {@code money.variables}): a name in that set is money whatever it is called; the word list ({@link #MONEY}) is
     * only the fallback for a name the pack does not declare.
     */
    public static List<Finding> check(String resource, byte[] xml, Set<String> declaredMoney) {
        Set<String> money = declaredMoney == null ? Set.of() : declaredMoney;
        List<Finding> out = new ArrayList<>();
        Document doc = parse(resource, xml);
        for (String tag : new String[] {"inputExpression", "output", "variable"}) {
            NodeList nl = doc.getElementsByTagNameNS("*", tag);
            for (int i = 0; i < nl.getLength(); i++) {
                Element e = (Element) nl.item(i);
                String type = e.getAttribute("typeRef");
                if (!FLOAT_TYPE.matcher(type.trim()).matches()) {
                    continue;
                }
                String subject = subject(e);
                String id = e.getAttribute("id");
                if (isMoney(subject, money)) {
                    out.add(new Finding(true, "SANGAM-DMN-MONEY-DOUBLE", resource, tag + " " + id, "'" + subject
                            + "' is money but has typeRef=\"" + type + "\"; use \"long\" (minor units) or \"number\""));
                } else {
                    out.add(new Finding(false, "SANGAM-DMN-DOUBLE", resource, tag + " " + id, "'" + subject
                            + "' has typeRef=\"" + type + "\"; \"number\" (exact decimal) is recommended"));
                }
            }
        }
        for (String tag : new String[] {"text"}) {
            NodeList nl = doc.getElementsByTagNameNS("*", tag);
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                String t = n.getTextContent();
                if (t != null && (ZONED_DT_EQ.matcher(t).find() || isBareZonedEntry(n, t) || computedDateTimeEquality(n, t))) {
                    String owner = n.getParentNode() instanceof Element p ? p.getAttribute("id") : "";
                    out.add(new Finding(true, "SANGAM-DMN-DATETIME-EQ", resource, owner, "date-time = or != against a "
                            + "zoned literal is refused (owner ruling G1): the FEEL engine's = also compares the zone, so "
                            + "the same instant in two zones is 'not equal'. Compare instants with < > a range, or "
                            + "a - b = duration(\"PT0S\"); date-time inputs reach the decision as UTC instants"));
                }
                if (t != null && TIME_IANA.matcher(t).find()) {
                    String owner = n.getParentNode() instanceof Element p ? p.getAttribute("id") : "";
                    out.add(new Finding(false, "SANGAM-DMN-TIME-IANA", resource, owner, "a time literal with an IANA "
                            + "zone id is deprecated as of DMN 1.6 (clause 10.3.4.1); use date and time(date, time, "
                            + "timezone) or a zone offset (decision 158)"));
                }
            }
        }
        for (String tag : UNSUPPORTED) {
            NodeList nl = doc.getElementsByTagNameNS("*", tag);
            for (int i = 0; i < nl.getLength(); i++) {
                Element e = (Element) nl.item(i);
                out.add(new Finding(true, "SANGAM-DMN-UNSUPPORTED", resource, tag + " " + e.getAttribute("id"),
                        "'" + tag + "' is not supported by Sangam (owner decision D2, ADR-DMN-ENGINE); use decision "
                        + "tables and literal expressions"));
            }
        }
        for (String tag : new String[] {"outputEntry", "defaultOutputEntry", "literalExpression", "inputExpression"}) {
            NodeList nl = doc.getElementsByTagNameNS("*", tag);
            for (int i = 0; i < nl.getLength(); i++) {
                Element e = (Element) nl.item(i);
                String text = withoutStrings(e.getTextContent()).trim();
                String subject = moneySubject(e);
                boolean moneyOut = subject != null && isMoney(subject, money);
                java.util.regex.Matcher rm = ROUNDING_CALL.matcher(text);
                while (rm.find()) {
                    int end = closing(text, rm.end());
                    String arg = text.substring(rm.end(), Math.max(rm.end(), end - 1));
                    String moneyRef = moneyReference(arg, money);
                    boolean whole = rm.start() == 0 && end == text.length();
                    if (moneyRef != null || (moneyOut && whole)) {
                        out.add(new Finding(true, "SANGAM-DMN-MONEY-ROUNDING", resource, tag + " " + e.getAttribute("id"),
                                "'" + (moneyRef != null ? moneyRef : subject) + "' is money and is rounded with "
                                + rm.group(1) + "(); DMN gives the exact figure and never rounds money, the financial "
                                + "core does (owner DEC-5; floor and ceiling by integrator ruling 2026-11-18)"));
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** The index just after the ')' closing the call whose argument starts at {@code from} (or the text length). */
    private static int closing(String t, int from) {
        int depth = 1;
        int c = from;
        while (c < t.length() && depth > 0) {
            char ch = t.charAt(c++);
            depth += ch == '(' ? 1 : ch == ')' ? -1 : 0;
        }
        return c;
    }

    /** The first name in {@code feel} that is money (declared, else by the word list), or null. */
    static String moneyReference(String feel, Set<String> declaredMoney) {
        java.util.regex.Matcher m = IDENT.matcher(feel);
        while (m.find()) {
            String w = m.group();
            if (declaredMoney.contains(w) || isMoney(w)) {
                return w;
            }
        }
        return null;
    }

    /** FEEL text with every string literal emptied, so a word inside a string is never read as a call. */
    static String withoutStrings(String t) {
        return t == null ? "" : STRING_LITERAL.matcher(t).replaceAll("\"\"");
    }

    /**
     * Integrator follow-up to G1: {@code =} or {@code !=} next to a date-time that FEEL computes ({@code date and
     * time(...)} of anything but one string literal, or {@code now()}), and an input entry that is only such a call.
     * Its zone is not known at deploy, and the engine's {@code =} compares zones. Not detectable statically: a
     * date-time produced by arithmetic or a context entry and then compared by name (recorded in ADR-DMN-ENGINE).
     */
    static boolean computedDateTimeEquality(Node text, String raw) {
        String t = withoutStrings(raw);
        boolean entry = text.getParentNode() instanceof Element p && "inputEntry".equals(p.getLocalName());
        for (String head : new String[] {"date and time(", "now("}) {
            int from = 0;
            int s;
            while ((s = t.indexOf(head, from)) >= 0) {
                from = s + head.length();
                if (s > 0 && (Character.isLetterOrDigit(t.charAt(s - 1)) || t.charAt(s - 1) == '.')) {
                    continue;
                }
                int depth = 1;
                int c = from;
                while (c < t.length() && depth > 0) {
                    char ch = t.charAt(c++);
                    depth += ch == '(' ? 1 : ch == ')' ? -1 : 0;
                }
                String arg = t.substring(from, Math.max(from, c - 1)).trim();
                if (head.startsWith("date") && arg.equals("\"\"")) {
                    continue; // one string literal: the zoned-literal rule decides
                }
                String before = t.substring(0, s).stripTrailing();
                String after = t.substring(Math.min(c, t.length())).stripLeading();
                boolean eqBefore = before.endsWith("=") && !before.endsWith("<=") && !before.endsWith(">=");
                boolean eqAfter = after.startsWith("=") || after.startsWith("!=");
                if (eqBefore || eqAfter || (entry && before.isEmpty() && after.isEmpty())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The output (or literal decision) a value expression produces: its name and label. */
    private static String moneySubject(Element e) {
        if ("inputExpression".equals(e.getLocalName())) {
            return subject(e);
        }
        if ("defaultOutputEntry".equals(e.getLocalName())) {
            return e.getParentNode() instanceof Element o ? subject(o) : null;
        }
        if ("outputEntry".equals(e.getLocalName())) {
            if (!(e.getParentNode() instanceof Element rule) || !(rule.getParentNode() instanceof Element table)) {
                return null;
            }
            int idx = 0;
            for (Node n = rule.getFirstChild(); n != null && n != e; n = n.getNextSibling()) {
                if (n instanceof Element s && "outputEntry".equals(s.getLocalName())) {
                    idx++;
                }
            }
            int k = 0;
            for (Node n = table.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element o && "output".equals(o.getLocalName()) && k++ == idx) {
                    String s = subject(o);
                    if (s.isEmpty() && table.getParentNode() instanceof Element d) {
                        s = decisionSubject(d);
                    }
                    return s;
                }
            }
            return null;
        }
        Node p = e.getParentNode();
        while (p instanceof Element pe && !"decision".equals(pe.getLocalName())) {
            p = pe.getParentNode();
        }
        return p instanceof Element d ? decisionSubject(d) : null;
    }

    private static String decisionSubject(Element decision) {
        StringBuilder s = new StringBuilder();
        append(s, decision.getAttribute("name"));
        append(s, decision.getAttribute("label"));
        for (Node n = decision.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element v && "variable".equals(v.getLocalName())) {
                append(s, v.getAttribute("name"));
            }
        }
        return s.toString().trim();
    }

    /** An input entry that is just a zoned date-time literal is an implicit '='. */
    private static boolean isBareZonedEntry(Node text, String t) {
        if (!(text.getParentNode() instanceof Element p) || !"inputEntry".equals(p.getLocalName())) {
            return false;
        }
        return t.trim().matches("(?i)(date and time\\(\\s*\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\"\\s*\\)"
                + "|@\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\")");
    }

    /** True when a whole name in {@code subject} is a declared money variable, else by the word list. */
    static boolean isMoney(String subject, Set<String> declaredMoney) {
        java.util.regex.Matcher m = IDENT.matcher(subject);
        while (m.find()) {
            if (declaredMoney.contains(m.group())) {
                return true;
            }
        }
        return isMoney(subject);
    }

    static boolean isMoney(String subject) {
        for (String part : subject.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")) {
            if (!part.isEmpty() && MONEY.matcher(part).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Name, label and expression text that identify what the typed value is. */
    private static String subject(Element e) {
        StringBuilder s = new StringBuilder();
        append(s, e.getAttribute("name"));
        append(s, e.getAttribute("label"));
        if ("inputExpression".equals(e.getLocalName())) {
            if (e.getParentNode() instanceof Element input) {
                append(s, input.getAttribute("label"));
                append(s, input.getAttribute("name"));
            }
            append(s, e.getTextContent());
        }
        return s.toString().trim();
    }

    private static void append(StringBuilder s, String v) {
        if (v != null && !v.isBlank()) {
            s.append(s.isEmpty() ? "" : " ").append(v.trim());
        }
    }

    private static Document parse(String resource, byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new ProcessEngineException("ADR-D-26: cannot read DMN resource '" + resource + "': " + e.getMessage(), e);
        }
    }

    /**
     * The money variables declared by the pack manifest(s) deployed with the models ({@code pack.json}, decision 193:
     * the manifest is a deployment resource): the union of every {@code money.variables} list. A manifest that cannot
     * be read refuses the deployment (never guessed).
     */
    static Set<String> declaredMoney(Collection<ResourceEntity> resources) {
        Set<String> out = new HashSet<>();
        for (ResourceEntity r : resources) {
            String name = r.getName() == null ? "" : r.getName().replace('\\', '/');
            if (!(name.equals("pack.json") || name.endsWith("/pack.json")) || r.getBytes() == null) {
                continue;
            }
            out.addAll(declaredMoney(name, r.getBytes()));
        }
        return out;
    }

    /** {@code money.variables} of one pack manifest. */
    public static Set<String> declaredMoney(String resource, byte[] packJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode vars = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(packJson).path("money").path("variables");
            Set<String> out = new HashSet<>();
            for (com.fasterxml.jackson.databind.JsonNode v : vars) {
                if (v.isTextual()) {
                    out.add(v.asText());
                }
            }
            return out;
        } catch (java.io.IOException e) {
            throw new ProcessEngineException("ADR-D-26: cannot read pack manifest '" + resource + "': " + e.getMessage(), e);
        }
    }

    /** Pre-deployer: errors refuse the deployment, warnings are logged. */
    public static final class DeployRule implements Deployer {
        @Override
        public void deploy(DeploymentEntity deployment) {
            if (deployment.getResources() == null) {
                return;
            }
            List<Finding> errors = new ArrayList<>();
            Set<String> money = declaredMoney(deployment.getResources().values());
            for (ResourceEntity r : deployment.getResources().values()) {
                String name = r.getName() == null ? "" : r.getName().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".dmn") || name.endsWith(".dmn11.xml")) || r.getBytes() == null) {
                    continue;
                }
                for (Finding f : check(r.getName(), r.getBytes(), money)) {
                    if (f.error()) {
                        errors.add(f);
                    } else {
                        LOG.warn("{}", f);
                    }
                }
            }
            if (!errors.isEmpty()) {
                throw new ProcessEngineException("ADR-D-26: deployment '" + deployment.getName() + "' refused: "
                        + errors);
            }
        }
    }
}
