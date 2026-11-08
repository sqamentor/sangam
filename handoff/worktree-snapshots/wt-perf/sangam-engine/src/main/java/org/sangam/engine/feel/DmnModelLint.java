// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *   <li><b>SG-35 warning</b>: an equality test on a zoned {@code date and time}. DMN 1.3 compares date-times on the
 *       time line (OMG issue DMN13-35), but the pinned feel-scala engine's {@code =} also compares the zone, so
 *       the same instant in two zones is "not equal". Compare instants with {@code <}, {@code >}, a range, or
 *       subtraction ({@code a - b = duration("PT0S")}).</li>
 * </ul>
 */
public final class DmnModelLint {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DmnModelLint.class);

    /** Money words; matched as a word or a camelCase / snake_case part of a name. */
    static final Pattern MONEY = Pattern.compile("(?i)(amount|value|cost|fee|limit|budget|price|charge|tariff|premium"
            + "|salary|wage|pay|payment|pension|fine|penalty|balance|credit|debit|entitlement|grant|subsidy|tax|rupee"
            + "|paise|money|ceiling|compensation|escalation|ld|outlay|expenditure|sanction|threshold|cap)");
    private static final Pattern FLOAT_TYPE = Pattern.compile("(?i)double|float");
    private static final Pattern ZONED_DT_EQ = Pattern.compile(
            "(?i)(=|!=)\\s*(date and time\\(\\s*\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\"|@\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\")"
            + "|(date and time\\(\\s*\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\"\\s*\\)|@\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\")\\s*(=|!=)");

    private DmnModelLint() {
    }

    public record Finding(boolean error, String code, String resource, String element, String message) {
        @Override
        public String toString() {
            return code + " " + resource + " " + element + ": " + message;
        }
    }

    public static List<Finding> check(String resource, byte[] xml) {
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
                if (isMoney(subject)) {
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
                if (t != null && (ZONED_DT_EQ.matcher(t).find() || isBareZonedEntry(n, t))) {
                    String owner = n.getParentNode() instanceof Element p ? p.getAttribute("id") : "";
                    out.add(new Finding(false, "SANGAM-DMN-DATETIME-EQ", resource, owner, "equality on a zoned date "
                            + "and time also compares the zone in the pinned FEEL engine; compare instants with < > "
                            + "or subtraction (ADR-D-26, DMN13-35)"));
                }
            }
        }
        return out;
    }

    /** An input entry that is just a zoned date-time literal is an implicit '='. */
    private static boolean isBareZonedEntry(Node text, String t) {
        if (!(text.getParentNode() instanceof Element p) || !"inputEntry".equals(p.getLocalName())) {
            return false;
        }
        return t.trim().matches("(?i)(date and time\\(\\s*\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\"\\s*\\)"
                + "|@\"[^\"]*(Z|[+-]\\d\\d:\\d\\d|@[^\"]+)\")");
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

    /** Pre-deployer: errors refuse the deployment, warnings are logged. */
    public static final class DeployRule implements Deployer {
        @Override
        public void deploy(DeploymentEntity deployment) {
            if (deployment.getResources() == null) {
                return;
            }
            List<Finding> errors = new ArrayList<>();
            for (ResourceEntity r : deployment.getResources().values()) {
                String name = r.getName() == null ? "" : r.getName().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".dmn") || name.endsWith(".dmn11.xml")) || r.getBytes() == null) {
                    continue;
                }
                for (Finding f : check(r.getName(), r.getBytes())) {
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
