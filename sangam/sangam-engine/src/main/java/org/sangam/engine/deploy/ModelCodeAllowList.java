// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.persistence.deploy.Deployer;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceEntity;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * SL-160 / TM-10 (owner decision 2026-11-12), part A: Java code named by a model is allow-listed at deploy. A new BPMN
 * or CMMN deployment is refused, with a stable code, when it names
 * <ul>
 *   <li>a Java class ({@code class} attribute: {@code operaton:class} on a task, or {@code class} on an
 *       {@code operaton:executionListener} / {@code taskListener} / {@code caseExecutionListener} ...) that is not one of
 *       Sangam's own reviewed delegate and listener classes ({@link #SANGAM_CLASSES}) or tenant-approved in
 *       {@code sangam.engine.deploy-allowed-classes} (key: the deployment's tenant id, or {@code *} for all tenants):
 *       code {@value #CLASS_NOT_ALLOWED};</li>
 *   <li>a {@code delegateExpression} that is not exactly {@code ${bean}} / {@code #{bean}} for a reviewed bean
 *       ({@link #SANGAM_BEANS}, or {@code sangam.engine.deploy-allowed-beans} for the tenant): code
 *       {@value #DELEGATE_NOT_ALLOWED}. A computed delegate expression is a dynamic class route and is refused.</li>
 * </ul>
 * Every extension namespace is covered ({@code operaton:}, a legacy {@code camunda:}, {@code sangam:}): an attribute
 * counts when it is in a non-model namespace, or unqualified on an element of one. Code outside the list runs as an
 * external worker ({@code operaton:type="external"}). Only <b>new</b> deployments are checked: a deployment already in
 * the database is re-parsed into the cache unchanged, so running instances never break on an engine restart.
 *
 * <p><b>Part B</b> (owner decisions 2026-11-12): every expression in the model (any attribute or element text holding
 * {@code ${..}} / {@code #{..}}, documentation and annotation text excepted) must pass {@link ExpressionPolicy}
 * ({@code SL160-EXPR-*}); and a script in a listener, a condition or an input/output parameter must be {@code juel},
 * inline, and pass the same expression rules ({@value #SCRIPT}); other script code runs in the out-of-process script
 * worker (ADR-D-8). Script tasks stay under the existing R6 / ADR-D-8 parse rule.
 */
public final class ModelCodeAllowList {

    public static final String CLASS_NOT_ALLOWED = "SL160-CLASS";
    public static final String DELEGATE_NOT_ALLOWED = "SL160-DELEGATE";
    public static final String UNREADABLE = "SL160-XML";
    public static final String SCRIPT = "SL160-SCRIPT";

    /** Elements whose text is prose, never evaluated. */
    static final Set<String> PROSE = Set.of("documentation", "text");

    /**
     * Sangam's own reviewed delegate, listener and activity-behaviour classes (binary names): every {@code org.sangam}
     * main-source class that implements {@code JavaDelegate}, {@code ExecutionListener}, {@code TaskListener},
     * {@code ActivityBehavior} or {@code SignallableActivityBehavior} (the plugin example module excluded). Kept equal
     * to the source tree by {@code ModelCodeAllowListGuardTest}: a new class must be reviewed onto this list.
     */
    public static final Set<String> SANGAM_CLASSES = Set.of(
            "org.sangam.casefile.delegate.CaseFileDelegate",
            "org.sangam.casefile.delegate.DeadlineDelegate",
            "org.sangam.casefile.delegate.MeetingDelegate",
            "org.sangam.casefile.delegate.ReferenceDelegate",
            "org.sangam.compliance.bpmn.ComplianceStepDelegate",
            "org.sangam.compliance.bpmn.IncidentClosureGuardListener",
            "org.sangam.engine.deploy.MultiInstanceCompensationContext",
            "org.sangam.engine.deploy.MultiInstanceSizeGuard",
            "org.sangam.engine.deploy.SequentialCompensation$OrderListener",
            "org.sangam.engine.feel.StrictNullResultGuard",
            "org.sangam.geo.bpmn.GeoDelegates$Fence",
            "org.sangam.geo.bpmn.GeoDelegates$Jurisdiction",
            "org.sangam.geo.bpmn.GeoDelegates$NearestN",
            "org.sangam.integration.bridge.operaton.SangamConnectorDelegate",
            "org.sangam.integration.bridge.operaton.SendEventDelegate",
            "org.sangam.integration.pipeline.PipelineDelegate",
            "org.sangam.onboarding.flow.OnboardingStepDelegate",
            "org.sangam.packs.core.ActorRecorderListener",
            "org.sangam.packs.core.ApprovalBindingListener",
            "org.sangam.packs.core.AssignmentStrategyDelegate",
            "org.sangam.packs.core.BatchDelegate",
            "org.sangam.packs.core.BatchItemOutcomeListener",
            "org.sangam.packs.core.BatchJoinListener",
            "org.sangam.packs.core.CaseRecordDelegate",
            "org.sangam.packs.core.CommitteeDelegate",
            "org.sangam.packs.core.ConnectorCallDelegate",
            "org.sangam.packs.core.CorrelationDelegate",
            "org.sangam.packs.core.CumulativeThresholdDelegate",
            "org.sangam.packs.core.DecisionDelegate",
            "org.sangam.packs.core.DocumentDelegate",
            "org.sangam.packs.core.DocumentSlotReadyListener",
            "org.sangam.packs.core.DscSignatureGuardListener",
            "org.sangam.packs.core.ExternalOperationDelegate",
            "org.sangam.packs.core.LedgerDelegate",
            "org.sangam.packs.core.LifecycleStateDelegate",
            "org.sangam.packs.core.LinkedInstanceDelegate",
            "org.sangam.packs.core.MoneyCalculationDelegate",
            "org.sangam.packs.core.NotifyDelegate",
            "org.sangam.packs.core.PackStartListener",
            "org.sangam.packs.core.PowerLimitGuardListener",
            "org.sangam.packs.core.ProfileValueDelegate",
            "org.sangam.packs.core.RecommendationLevelDelegate",
            "org.sangam.packs.core.SeatResolutionDelegate",
            "org.sangam.packs.core.SetVariablesDelegate",
            "org.sangam.packs.core.SignatureDelegate",
            "org.sangam.packs.core.SlaDelegate",
            "org.sangam.packs.core.SodGuardListener",
            "org.sangam.packs.core.SuspensionDelegate",
            "org.sangam.packs.gov.HearingScheduleDelegate",
            "org.sangam.packs.gov.MeritListDelegate",
            "org.sangam.packs.gov.ReconciliationMatchDelegate",
            "org.sangam.packs.gov.WaitlistDelegate",
            "org.sangam.packs.universal.EventWindowDelegate",
            "org.sangam.ports.bpmn.SkipExpressionSupport$SkippableActivity",
            "org.sangam.ports.events.SendEventDelegate",
            "org.sangam.ports.task.TaskEventDispatcher",
            "org.sangam.programme.delegate.DefaulterReminderDelegate",
            "org.sangam.programme.delegate.MilestoneDelayDelegate",
            "org.sangam.programme.delegate.ReturnCampaignDelegate",
            "org.sangam.programme.delegate.ReturnStateDelegate",
            "org.sangam.programme.delegate.RollupDelegate",
            "org.sangam.programme.delegate.VarianceDelegate",
            "org.sangam.registry.bpmn.AssetDelegate",
            "org.sangam.registry.bpmn.CustodyDelegate",
            "org.sangam.registry.bpmn.PartyDelegate",
            "org.sangam.registry.bpmn.RegistryDelegate",
            "org.sangam.resources.bpmn.BookingActionDelegate",
            "org.sangam.resources.bpmn.EntitlementCheckDelegate",
            "org.sangam.resources.bpmn.HoldBookingDelegate",
            "org.sangam.resources.bpmn.IssueTokenDelegate",
            "org.sangam.resources.bpmn.PublishRosterDelegate",
            "org.sangam.resources.bpmn.QueueSlaBreachDelegate",
            "org.sangam.resources.bpmn.RosterSwapDecisionDelegate",
            "org.sangam.resources.bpmn.WaitlistDelegate",
            "org.sangam.security.assignment.SeatTaskListener");

    /**
     * Sangam's own reviewed beans; an entry is a bean (every member) or {@code bean.member}. {@code sangamOnboardingStep}:
     * the shipped onboarding model's delegate. {@code moveSteps.*}: the eight steps the shipped control-plane model
     * {@code tenant-move.bpmn} calls (sangam-control-plane's own bean), member by member. {@code sangamTenants.callee}:
     * the documented model API of sangam-flowable-ports {@code TenantDefinitionResolver} (FLOWABLE-INTO-OPERATON FC-33,
     * {@code operaton:calledElementTenantId="${sangamTenants.callee('child', tenantId)}"}).
     */
    public static final Set<String> SANGAM_BEANS = Set.of("sangamOnboardingStep",
            "moveSteps.prepare", "moveSteps.freeze", "moveSteps.checkDrained", "moveSteps.copy", "moveSteps.verify",
            "moveSteps.cutover", "moveSteps.purgeSource", "moveSteps.rollback", "sangamTenants.callee");

    /** Namespaces of the model itself; attributes in any other namespace are extensions. */
    static final Set<String> MODEL_NAMESPACES = Set.of(
            "http://www.omg.org/spec/BPMN/20100524/MODEL", "http://www.omg.org/spec/BPMN/20100524/DI",
            "http://www.omg.org/spec/DD/20100524/DC", "http://www.omg.org/spec/DD/20100524/DI",
            "http://www.omg.org/spec/CMMN/20151109/MODEL", "http://www.omg.org/spec/CMMN/20151109/CMMNDI",
            "http://www.omg.org/spec/CMMN/20151109/DI", "http://www.omg.org/spec/CMMN/20151109/DC",
            XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
            XMLConstants.XML_NS_URI);

    private static final Pattern BEAN = Pattern.compile("^\\s*[$#]\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}\\s*$");

    /** One refusal reason. */
    public record Finding(String code, String resource, String element, String detail) {
        @Override
        public String toString() {
            return code + " " + resource + " <" + element + ">: " + detail;
        }
    }

    private ModelCodeAllowList() {
    }

    /** The findings of one BPMN or CMMN resource against the given allowed classes and beans (empty: allowed). */
    public static List<Finding> check(String resource, byte[] xml, Set<String> classes, Set<String> beans) {
        List<Finding> out = new ArrayList<>();
        Document doc;
        try {
            doc = parse(xml);
        } catch (Exception e) {
            out.add(new Finding(UNREADABLE, resource, "-", "cannot read the model: " + e.getMessage()));
            return out;
        }
        walk(doc.getDocumentElement(), resource, classes, beans, out);
        return out;
    }

    private static void walk(Element e, String resource, Set<String> classes, Set<String> beans, List<Finding> out) {
        boolean extensionElement = e.getNamespaceURI() != null && !MODEL_NAMESPACES.contains(e.getNamespaceURI());
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String ns = a.getNamespaceURI();
            boolean extension = ns != null ? !MODEL_NAMESPACES.contains(ns) : extensionElement;
            if (!extension) {
                continue;
            }
            String local = a.getLocalName() != null ? a.getLocalName() : a.getName();
            String where = e.getLocalName() + (e.hasAttribute("id") ? " '" + e.getAttribute("id") + "'" : "");
            String value = a.getValue() == null ? "" : a.getValue().trim();
            if ("class".equals(local) && !classes.contains(value)) {
                out.add(new Finding(CLASS_NOT_ALLOWED, resource, where, "Java class '" + value + "' is not on the "
                        + "reviewed allow-list (Sangam's own classes plus sangam.engine.deploy-allowed-classes for the "
                        + "tenant); run other code as an external worker (operaton:type=\"external\")"));
            } else if ("delegateExpression".equals(local)) {
                Matcher m = BEAN.matcher(value);
                if (!m.matches() || !beans.contains(m.group(1))) {
                    out.add(new Finding(DELEGATE_NOT_ALLOWED, resource, where, "delegate expression '" + value + "' is not "
                            + "${bean} for a reviewed bean (Sangam's own plus sangam.engine.deploy-allowed-beans for the "
                            + "tenant); run other code as an external worker (operaton:type=\"external\")"));
                }
                continue; // a delegate expression is a bean reference, checked above, not an evaluated value
            }
            if ("resource".equals(local) && ("script".equals(e.getLocalName()) || "conditionExpression".equals(e.getLocalName()))) {
                out.add(new Finding(SCRIPT, resource, where, "an external script resource '" + value + "' cannot be "
                        + "checked; write the condition inline in juel, or run the code in the script worker (ADR-D-8)"));
            }
        }
        // part B: every attribute holding an expression (any namespace, the delegate expression excepted)
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String local = a.getLocalName() != null ? a.getLocalName() : a.getName();
            if (!"delegateExpression".equals(local) && ExpressionPolicy.hasExpression(a.getValue())) {
                expression(a.getValue(), resource, e, beans, out);
            }
        }
        script(e, resource, beans, out);
        if (!PROSE.contains(e.getLocalName())) {
            String text = ownText(e);
            if (ExpressionPolicy.hasExpression(text) && !isScript(e)) {
                expression(text, resource, e, beans, out);
            }
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                walk(child, resource, classes, beans, out);
            }
        }
    }

    private static String where(Element e) {
        return e.getLocalName() + (e.hasAttribute("id") ? " '" + e.getAttribute("id") + "'" : "");
    }

    private static void expression(String text, String resource, Element e, Set<String> beans, List<Finding> out) {
        for (ExpressionPolicy.Refusal r : ExpressionPolicy.check(text, beans)) {
            out.add(new Finding(r.code(), resource, where(e), r.detail()));
        }
    }

    /** An extension {@code script} element (listener, input/output parameter). */
    private static boolean isScript(Element e) {
        return "script".equals(e.getLocalName()) && e.getNamespaceURI() != null
                && !MODEL_NAMESPACES.contains(e.getNamespaceURI());
    }

    /**
     * Part B rule 4: a script in a listener, an input/output parameter ({@code <operaton:script scriptFormat=..>}) or a
     * condition ({@code <conditionExpression language=..>}) must be {@code juel}; its text then passes the expression
     * rules (a condition's text is checked as any element text).
     */
    private static void script(Element e, String resource, Set<String> beans, List<Finding> out) {
        if (isScript(e)) {
            String format = e.getAttribute("scriptFormat");
            if (!"juel".equalsIgnoreCase(format.trim())) {
                out.add(new Finding(SCRIPT, resource, where(e), "script language '" + format + "' is not allowed in the "
                        + "engine; only juel; run other scripts in the out-of-process script worker (ADR-D-8)"));
            } else if (ExpressionPolicy.hasExpression(ownText(e))) {
                expression(ownText(e), resource, e, beans, out);
            }
        } else if (e.hasAttribute("language")) {
            String language = e.getAttribute("language");
            if (!"juel".equalsIgnoreCase(language.trim())) {
                out.add(new Finding(SCRIPT, resource, where(e), "script language '" + language + "' is not allowed in the "
                        + "engine; only juel; run other scripts in the out-of-process script worker (ADR-D-8)"));
            }
        }
    }

    /** The element's own text (its direct text and CDATA children). */
    private static String ownText(Element e) {
        StringBuilder b = new StringBuilder();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                b.append(n.getNodeValue());
            }
        }
        return b.toString();
    }

    static Document parse(byte[] xml) throws Exception { // hardened (no DOCTYPE, no XXE); shared with OperationRefPinning
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // FLOWABLE-INTO-OPERATON D-51 (no DOCTYPE, no XXE) / R24
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    /** The allowed entries for a tenant: Sangam's own, the {@code *} entry and the tenant's own entry. */
    static Set<String> allowed(Set<String> own, Map<String, ? extends Set<String>> configured, String tenantId) {
        Set<String> out = new LinkedHashSet<>(own);
        if (configured != null) {
            Set<String> all = configured.get("*");
            if (all != null) {
                out.addAll(all);
            }
            Set<String> tenant = tenantId == null ? null : configured.get(tenantId);
            if (tenant != null) {
                out.addAll(tenant);
            }
        }
        return out;
    }

    static boolean isModel(String resourceName) {
        String n = resourceName == null ? "" : resourceName.toLowerCase(Locale.ROOT);
        return n.endsWith(".bpmn") || n.endsWith(".bpmn20.xml") || n.endsWith(".cmmn") || n.endsWith(".cmmn11.xml")
                || n.endsWith(".cmmn.xml");
    }

    /** Pre-deployer: a new deployment with any finding is refused as a whole. */
    public static final class DeployRule implements Deployer {
        private final Map<String, Set<String>> allowedClasses;
        private final Map<String, Set<String>> allowedBeans;

        /**
         * @param allowedClasses tenant id or {@code *} -> extra allowed classes ({@code sangam.engine.deploy-allowed-classes})
         * @param allowedBeans   tenant id or {@code *} -> extra allowed beans ({@code sangam.engine.deploy-allowed-beans})
         */
        public DeployRule(Map<String, Set<String>> allowedClasses, Map<String, Set<String>> allowedBeans) {
            this.allowedClasses = allowedClasses;
            this.allowedBeans = allowedBeans;
        }

        @Override
        public void deploy(DeploymentEntity deployment) {
            if (!deployment.isNew() || deployment.getResources() == null) {
                return; // re-parsing a stored deployment into the cache: never refuse what is already running
            }
            Set<String> classes = allowed(SANGAM_CLASSES, allowedClasses, deployment.getTenantId());
            Set<String> beans = allowed(SANGAM_BEANS, allowedBeans, deployment.getTenantId());
            List<Finding> findings = new ArrayList<>();
            for (ResourceEntity r : deployment.getResources().values()) {
                if (isModel(r.getName()) && r.getBytes() != null) {
                    findings.addAll(check(r.getName(), r.getBytes(), classes, beans));
                }
            }
            if (!findings.isEmpty()) {
                throw new ProcessEngineException("SL-160: deployment '" + deployment.getName() + "' refused: " + findings);
            }
        }
    }
}
