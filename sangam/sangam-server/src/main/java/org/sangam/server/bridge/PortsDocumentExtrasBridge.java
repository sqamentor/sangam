// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.OutputTemplateRenderer;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentExtrasPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;

/**
 * {@link DocumentExtrasPort} on the ports content service: version history from {@code CONTENT_VERSION}, legal hold
 * (R61; held documents refuse deletion in {@link PortsDocumentBridge}) and template generation (R74, S12) through
 * {@link OutputTemplateRenderer}. Templates are the {@code templates} registry entries ({@code body} per language).
 * HTML and plain text are rendered here; PDF/A and DOCX are left to sangam-integration ({@code 422 FORMAT_NOT_AVAILABLE}).
 */
public final class PortsDocumentExtrasBridge implements DocumentExtrasPort {

    private final PortsDocumentBridge documents;
    private final OutputTemplateRenderer renderer;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final RegistryPort registry;
    private final AuditPort audit;

    public PortsDocumentExtrasBridge(PortsDocumentBridge documents, OutputTemplateRenderer renderer, RuntimeService runtime,
                                     HistoryService history, RegistryPort registry, AuditPort audit) {
        this.documents = documents;
        this.renderer = renderer;
        this.runtime = runtime;
        this.history = history;
        this.registry = registry;
        this.audit = audit;
    }

    @Override
    public List<DocumentPort.Document> versions(RequestContext ctx, String documentId) {
        ContentService.ContentItem item = documents.visible(ctx, documentId);
        List<DocumentPort.Document> out = new ArrayList<>();
        for (ContentService.ContentVersion v : PortsErrors.call(() -> documents.content().versions(documentId))) {
            out.add(documents.mapVersion(item, v));
        }
        return out;
    }

    @Override
    public DocumentPort.Document setLegalHold(RequestContext ctx, String documentId, LegalHoldRequest request) {
        if (request == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "hold and reason are required");
        }
        PortsErrors.requireReason(request.reason());
        ContentService.ContentItem item = documents.visible(ctx, documentId);
        documents.setHold(ctx, documentId, request.hold(), request.reason());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document_id", documentId);
        p.put("reason", request.reason());
        audit.record(ctx, item.scopeId() == null ? "doc" : item.scopeId(),
                request.hold() ? "LEGAL_HOLD_SET" : "LEGAL_HOLD_RELEASED", p);
        return documents.map(item);
    }

    @Override
    public List<DocumentTemplate> templates(RequestContext ctx) {
        return entries(ctx).stream().map(PortsDocumentExtrasBridge::template).toList();
    }

    @Override
    public DocumentPort.Document generate(RequestContext ctx, GenerateRequest r) {
        if (r == null || r.templateId() == null || r.businessKey() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "templateId and businessKey are required");
        }
        Map<String, Object> entry = entries(ctx).stream()
                .filter(e -> r.templateId().equals(String.valueOf(e.get("id"))) || r.templateId().equals(String.valueOf(e.get("key"))))
                .findFirst().orElseThrow(() -> ApiProblem.notFound("document template", r.templateId()));
        DocumentTemplate t = template(entry);
        boolean html;
        if ("HTML".equals(t.format())) {
            html = true;
        } else if ("TEXT".equals(t.format())) {
            html = false;
        } else {
            throw ApiProblem.unprocessable("FORMAT_NOT_AVAILABLE",
                    t.format() + " rendering is provided by sangam-integration; this server renders HTML and text templates");
        }
        String lang = r.language() == null || r.language().isBlank() ? "en" : r.language();
        String body = body(entry, lang);
        String pi = instanceId(ctx, r.businessKey());
        Map<String, Object> vars = variables(pi);
        vars.putIfAbsent("businessKey", r.businessKey());
        String name = safe(t.name()) + "-" + safe(r.businessKey()) + "-" + safe(lang) + (html ? ".html" : ".txt");
        ContentService.ContentItem item = PortsErrors.call(() -> renderer.renderToDocument(ctx.tenantId(), name, body, vars,
                html, "bpmn", pi, ctx.principalId()));
        DocumentPort.Document d = documents.map(item);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document_id", d.id());
        p.put("template_id", t.id());
        p.put("template_version", t.version());
        p.put("language", lang);
        p.put("sha256", d.sha256());
        p.put("business_key", r.businessKey());
        audit.record(ctx, pi, "DOCUMENT_ISSUED", p);
        return d;
    }

    private List<Map<String, Object>> entries(RequestContext ctx) {
        RequestContext sys = RequestContext.system(ctx.tenantId(), ctx.principalId());
        return registry.list(sys, "templates", null, new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items();
    }

    private static DocumentTemplate template(Map<String, Object> e) {
        List<String> languages = new ArrayList<>();
        if (e.get("languages") instanceof Collection<?> c) {
            c.forEach(x -> languages.add(String.valueOf(x)));
        } else if (e.get("body") instanceof Map<?, ?> body) {
            body.keySet().forEach(k -> languages.add(String.valueOf(k)));
        }
        int version = e.get("version") instanceof Number n ? n.intValue() : 1;
        String format = e.get("format") == null ? "HTML" : String.valueOf(e.get("format")).toUpperCase(Locale.ROOT);
        return new DocumentTemplate(String.valueOf(e.get("id")), String.valueOf(e.getOrDefault("name", e.get("id"))),
                languages, format, version);
    }

    private static String body(Map<String, Object> entry, String lang) {
        Object b = entry.get("body");
        if (b instanceof Map<?, ?> m) {
            Object text = m.get(lang);
            if (text == null) {
                throw ApiProblem.unprocessable("TEMPLATE_LANGUAGE_MISSING", "the template has no text for language " + lang);
            }
            return String.valueOf(text);
        }
        if (b instanceof String s) {
            return s;
        }
        throw ApiProblem.unprocessable("TEMPLATE_INVALID", "the template has no body");
    }

    private String instanceId(RequestContext ctx, String businessKey) {
        ProcessInstance pi = runtime.createProcessInstanceQuery().processInstanceBusinessKey(businessKey)
                .tenantIdIn(ctx.tenantId()).listPage(0, 1).stream().findFirst().orElse(null);
        if (pi != null) {
            return pi.getId();
        }
        HistoricProcessInstance h = history.createHistoricProcessInstanceQuery().processInstanceBusinessKey(businessKey)
                .tenantIdIn(ctx.tenantId()).orderByProcessInstanceStartTime().desc().listPage(0, 1).stream().findFirst()
                .orElseThrow(() -> ApiProblem.notFound("business key", businessKey));
        return h.getId();
    }

    private Map<String, Object> variables(String processInstanceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).count() > 0) {
            out.putAll(runtime.getVariables(processInstanceId));
        } else {
            for (HistoricVariableInstance v : history.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId).list()) {
                out.putIfAbsent(v.getName(), v.getValue());
            }
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
