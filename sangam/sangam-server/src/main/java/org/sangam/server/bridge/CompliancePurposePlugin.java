// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.SangamCompliance;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Engine hooks for purpose binding and retention (compliance README §6), registered after the Sangam, security,
 * integration and ports plugins. A post-parse listener adds built-in execution listeners (they also run with
 * {@code skipCustomListeners}) to every process definition:
 * <ul>
 *   <li><b>start:</b> {@code nationalIds.requireNoRawIdentifiers(variables)} (IN: Aadhaar Act s.29 — reference keys
 *       only); when the process declares a purpose ({@code operaton:property name="sangam:purpose"} on the process, or
 *       the start variable {@code sangamPurpose}) also {@code purposeBinding.require(...)} for the data principal
 *       (variable named by {@code sangam:principalVariable}, default {@code sangamApplicantId}, else
 *       {@code principalRef}) and {@code retention.track(...)} for the root;</li>
 *   <li><b>end:</b> {@code retention.onPurposeCompleted(...)} for a declared purpose.</li>
 * </ul>
 * A process without a declared purpose is not purpose-checked. A refusal throws
 * {@code ProcessEngineException("<status> <CODE>: ...")}, which sangam-rest maps to problem+json and which rolls the
 * start back. The listeners run on the engine command's own connection, so compliance rows commit with the instance.
 * The {@link SangamCompliance} bean is resolved lazily (it depends on the engine).
 */
public final class CompliancePurposePlugin extends AbstractProcessEnginePlugin {

    public static final String PURPOSE_PROPERTY = "sangam:purpose";
    public static final String PRINCIPAL_PROPERTY = "sangam:principalVariable";
    public static final String PURPOSE_VARIABLE = "sangamPurpose";
    public static final String CATEGORIES_VARIABLE = "sangamDataCategories";
    private static final String PURPOSE_RESOLVED = "sangamPurposeResolved";

    private final ObjectProvider<SangamCompliance> compliance;

    public CompliancePurposePlugin(ObjectProvider<SangamCompliance> compliance) {
        this.compliance = compliance;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        List<BpmnParseListener> post = cfg.getCustomPostBPMNParseListeners();
        if (post == null) {
            post = new ArrayList<>();
            cfg.setCustomPostBPMNParseListeners(post);
        }
        post.add(new ParseListener());
    }

    final class ParseListener extends AbstractBpmnParseListener {
        @Override
        public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
            String purpose = property(processElement, PURPOSE_PROPERTY);
            String principalVariable = property(processElement, PRINCIPAL_PROPERTY);
            processDefinition.addBuiltInListener(ExecutionListener.EVENTNAME_START,
                    (ExecutionListener) e -> onStart(e, purpose, principalVariable));
            processDefinition.addBuiltInListener(ExecutionListener.EVENTNAME_END,
                    (ExecutionListener) e -> onEnd(e, purpose));
        }
    }

    private void onStart(DelegateExecution e, String declaredPurpose, String principalVariable) {
        SangamCompliance c = compliance.getIfAvailable();
        if (c == null) {
            return;
        }
        String tenant = e.getTenantId();
        Map<String, Object> variables = e.getVariables();
        try {
            try {
                c.nationalIds.requireNoRawIdentifiers(tenant, variables);
            } catch (ComplianceException ex) {
                // no jurisdiction means no identifier rule to apply; a declared purpose below still refuses
                if (ex.code() != ComplianceException.Code.JURISDICTION_NOT_SET) {
                    throw ex;
                }
            }
            String purpose = declaredPurpose != null ? declaredPurpose : str(variables.get(PURPOSE_VARIABLE));
            if (purpose == null) {
                return;
            }
            String principal = str(variables.get(principalVariable != null ? principalVariable : "sangamApplicantId"));
            if (principal == null) {
                principal = str(variables.get("principalRef"));
            }
            Connection conn = connection();
            c.purposeBinding.require(conn, tenant, principal, purpose, categories(variables.get(CATEGORIES_VARIABLE)),
                    Set.of());
            if (principal != null) {
                c.retention.track(conn, tenant, root(e), principal, purpose);
            }
            e.setVariableLocal(PURPOSE_RESOLVED, purpose);
        } catch (ComplianceException ex) {
            throw new ProcessEngineException(ex.httpStatus() + " " + ex.getMessage(), ex);
        }
    }

    private void onEnd(DelegateExecution e, String declaredPurpose) {
        SangamCompliance c = compliance.getIfAvailable();
        if (c == null) {
            return;
        }
        Object resolved = e.getVariableLocal(PURPOSE_RESOLVED);
        String purpose = declaredPurpose != null ? declaredPurpose : str(resolved);
        if (purpose == null) {
            return;
        }
        try {
            c.retention.onPurposeCompleted(connection(), e.getTenantId(), root(e), purpose);
        } catch (ComplianceException ex) {
            throw new ProcessEngineException(ex.httpStatus() + " " + ex.getMessage(), ex);
        }
    }

    private static Connection connection() {
        return Context.getCommandContext().getDbSqlSession().getSqlSession().getConnection();
    }

    private static String root(DelegateExecution e) {
        if (e instanceof ExecutionEntity x && x.getRootProcessInstanceId() != null) {
            return x.getRootProcessInstanceId();
        }
        return e.getProcessInstanceId();
    }

    private static Set<String> categories(Object v) {
        if (v == null) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof Collection<?> col) {
            col.forEach(o -> out.add(String.valueOf(o).trim()));
        } else {
            for (String s : String.valueOf(v).split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static String property(Element process, String name) {
        Element ext = process.element("extensionElements");
        if (ext == null) {
            return null;
        }
        for (Element props : ext.elements("properties")) {
            for (Element p : props.elements("property")) {
                if (name.equals(p.attribute("name"))) {
                    String v = p.attribute("value");
                    return v == null || v.isBlank() ? null : v.trim();
                }
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
