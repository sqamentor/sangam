// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Attachment;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentExtrasPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TypedVariables;

/**
 * Default {@link DocumentExtrasPort} on Operaton attachments (same metadata layout as {@link OperatonDocumentAdapter}).
 * <ul>
 *   <li>Versions: every attachment of the logical document (metadata {@code documentId}), ordered by version.</li>
 *   <li>Legal hold (R61): {@code legalHold} in the metadata of every version; a held document cannot be deleted.</li>
 *   <li>Templates (R74): entries of the {@code templates} registry ({@code name, languages, format, version, body}
 *       with {@code body} = language → text with {@code ${variable}} placeholders).</li>
 *   <li>Generation: HTML only, with HTML-escaped variable values. PDF/A and DOCX rendering is provided by
 *       sangam-integration ({@code 422 FORMAT_NOT_AVAILABLE} here).</li>
 * </ul>
 */
public class OperatonDocumentExtrasAdapter implements DocumentExtrasPort {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private final DocumentPort documents;
    private final TaskService tasks;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final RegistryPort registry;
    private final AuditPort audit;
    private final RestProperties props;

    public OperatonDocumentExtrasAdapter(DocumentPort documents, TaskService tasks, RuntimeService runtime,
                                         HistoryService history, RegistryPort registry, AuditPort audit,
                                         RestProperties props) {
        this.documents = documents;
        this.tasks = tasks;
        this.runtime = runtime;
        this.history = history;
        this.registry = registry;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ versions and legal hold

    @Override
    public List<DocumentPort.Document> versions(RequestContext ctx, String documentId) {
        return chain(ctx, documentId).stream().map(OperatonDocumentAdapter::map).toList();
    }

    @Override
    public DocumentPort.Document setLegalHold(RequestContext ctx, String documentId, LegalHoldRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A legal hold change requires a reason (R61)");
        }
        List<Attachment> all = chain(ctx, documentId);
        for (Attachment a : all) {
            Map<String, Object> meta = OperatonDocumentAdapter.meta(a);
            meta.put("legalHold", request.hold());
            a.setDescription(OperatonDocumentAdapter.write(meta));
            OperatonSupport.run(() -> tasks.saveAttachment(a));
        }
        Attachment requested = all.stream().filter(a -> a.getId().equals(documentId)).findFirst().orElse(all.get(0));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document_id", documentId);
        p.put("versions", all.size());
        p.put("reason", request.reason());
        p.put("sha256", OperatonDocumentAdapter.meta(requested).get("sha256"));
        p.values().removeIf(Objects::isNull);
        audit.record(ctx, chainId(requested), request.hold() ? "LEGAL_HOLD_SET" : "LEGAL_HOLD_RELEASED", p);
        return OperatonDocumentAdapter.map(tasks.getAttachment(requested.getId()));
    }

    /** All versions of the logical document that {@code documentId} belongs to, ordered by version. */
    private List<Attachment> chain(RequestContext ctx, String documentId) {
        documents.get(ctx, documentId); // tenant check and 404
        Attachment a = OperatonSupport.call(() -> tasks.getAttachment(documentId));
        Map<String, Object> meta = OperatonDocumentAdapter.meta(a);
        String root = String.valueOf(meta.getOrDefault("documentId", rootOf(a)));
        List<Attachment> scope = new ArrayList<>();
        if (a.getTaskId() != null) {
            scope.addAll(OperatonSupport.call(() -> tasks.getTaskAttachments(a.getTaskId())));
        }
        if (a.getProcessInstanceId() != null) {
            scope.addAll(OperatonSupport.call(() -> tasks.getProcessInstanceAttachments(a.getProcessInstanceId())));
        }
        Map<String, Attachment> byId = new LinkedHashMap<>();
        for (Attachment x : scope) {
            if (!OperatonDocumentAdapter.visible(ctx, x)) {
                continue;
            }
            Map<String, Object> m = OperatonDocumentAdapter.meta(x);
            String r = String.valueOf(m.getOrDefault("documentId", rootOf(x)));
            if (r.equals(root)) {
                byId.putIfAbsent(x.getId(), x);
            }
        }
        byId.putIfAbsent(a.getId(), a);
        List<Attachment> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparingInt(x -> ((Number) OperatonDocumentAdapter.meta(x).getOrDefault("version", 1)).intValue()));
        return out;
    }

    /** Version 1 has no {@code documentId}; walk {@code previous} back to it. */
    private String rootOf(Attachment a) {
        Attachment cur = a;
        for (int guard = 0; guard < 1000; guard++) {
            Object prev = OperatonDocumentAdapter.meta(cur).get("previous");
            if (prev == null) {
                return cur.getId();
            }
            Attachment p = tasks.getAttachment(String.valueOf(prev));
            if (p == null) {
                return cur.getId();
            }
            cur = p;
        }
        return cur.getId();
    }

    private static String chainId(Attachment a) {
        return a.getProcessInstanceId() != null ? a.getProcessInstanceId() : "task:" + a.getTaskId();
    }

    // ------------------------------------------------------------------ templates and generation

    @Override
    public List<DocumentTemplate> templates(RequestContext ctx) {
        return templateEntries(ctx).stream().map(OperatonDocumentExtrasAdapter::template).toList();
    }

    private List<Map<String, Object>> templateEntries(RequestContext ctx) {
        RequestContext sys = RequestContext.system(ctx.tenantId(), ctx.principalId());
        return registry.list(sys, "templates", null, new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items();
    }

    @SuppressWarnings("unchecked")
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

    @Override
    public DocumentPort.Document generate(RequestContext ctx, GenerateRequest r) {
        if (r == null || r.templateId() == null || r.businessKey() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "templateId and businessKey are required");
        }
        Map<String, Object> entry = templateEntries(ctx).stream()
                .filter(e -> r.templateId().equals(String.valueOf(e.get("id")))
                        || r.templateId().equals(String.valueOf(e.get("key"))))
                .findFirst().orElseThrow(() -> ApiProblem.notFound("document template", r.templateId()));
        DocumentTemplate t = template(entry);
        if (!"HTML".equals(t.format())) {
            throw ApiProblem.unprocessable("FORMAT_NOT_AVAILABLE",
                    t.format() + " rendering is provided by sangam-integration; this server renders HTML templates");
        }
        String lang = r.language() == null || r.language().isBlank() ? "en" : r.language();
        String body = body(entry, lang);
        String processInstanceId = instanceId(ctx, r.businessKey());
        Map<String, Object> vars = variables(processInstanceId);
        vars.putIfAbsent("businessKey", r.businessKey());
        String html = render(body, vars);
        byte[] content = html.getBytes(StandardCharsets.UTF_8);
        String name = safe(t.name()) + "-" + safe(r.businessKey()) + "-" + safe(lang) + ".html";
        String sha = Hashes.hex(Hashes.sha256(content));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenant", ctx.tenantId());
        meta.put("sha256", sha);
        meta.put("size", content.length);
        meta.put("contentType", "text/html; charset=UTF-8");
        meta.put("version", 1);
        meta.put("createdBy", ctx.principalId());
        meta.put("scanStatus", "NOT_SCANNED");
        meta.put("metadata", Map.of("templateId", t.id(), "templateVersion", t.version(), "language", lang,
                "generated", true));
        Attachment a = OperatonSupport.call(() -> tasks.createAttachment("text/html", null, processInstanceId, name,
                OperatonDocumentAdapter.write(meta), new ByteArrayInputStream(content)));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document_id", a.getId());
        p.put("template_id", t.id());
        p.put("template_version", t.version());
        p.put("language", lang);
        p.put("sha256", sha);
        p.put("business_key", r.businessKey());
        audit.record(ctx, processInstanceId, "DOCUMENT_ISSUED", p);
        return OperatonDocumentAdapter.map(a);
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
        ProcessInstance pi = OperatonSupport.call(() -> runtime.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).tenantIdIn(ctx.tenantId()).listPage(0, 1))
                .stream().findFirst().orElse(null);
        if (pi != null) {
            return pi.getId();
        }
        HistoricProcessInstance h = OperatonSupport.call(() -> history.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).tenantIdIn(ctx.tenantId())
                .orderByProcessInstanceStartTime().desc().listPage(0, 1)).stream().findFirst().orElse(null);
        if (h == null) {
            throw ApiProblem.notFound("business key", businessKey);
        }
        return h.getId();
    }

    private Map<String, Object> variables(String processInstanceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean running = runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).count() > 0;
        if (running) {
            out.putAll(OperatonSupport.call(() -> runtime.getVariables(processInstanceId)));
        } else {
            for (HistoricVariableInstance v : OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId).listPage(0, 1000))) {
                out.put(v.getName(), v.getValue());
            }
        }
        return out;
    }

    static String render(String body, Map<String, Object> vars) {
        Matcher m = PLACEHOLDER.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            Object plain = TypedVariables.toJsonValue(v);
            String text = plain instanceof Map<?, ?> typed && typed.containsKey("value")
                    ? String.valueOf(typed.get("value")) : v == null ? "" : String.valueOf(plain);
            m.appendReplacement(sb, Matcher.quoteReplacement(escape(text)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "doc" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Count cap applied by template listings. */
    long countCap() {
        return props.getCountCap();
    }
}
