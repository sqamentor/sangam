// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sangam.ports.forms.FormValidator;
import org.sangam.ports.forms.SangamFormService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.FormPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TypedVariables;

/**
 * {@link FormPort} with sangam-flowable-ports' form engine (FLOWABLE-INTO-OPERATON FC-39, {@link SangamFormService}): forms deployed as
 * Sangam form definitions (app bundles, {@code sangam:<key>} form keys) are served and validated by the ports form
 * service; Camunda/form-js forms deployed as plain resources, and every draft call, stay with the default adapter.
 *
 * <p>Version mapping: the REST {@code versionHash} of a ports form is its SHA-256; a numeric {@code version}
 * parameter selects the form version number.
 */
public final class PortsFormBridge implements FormPort {

    private final FormPort base;
    private final SangamFormService forms;

    public PortsFormBridge(FormPort base, SangamFormService forms) {
        this.base = base;
        this.forms = forms;
    }

    @Override
    public FormVersion get(RequestContext ctx, String formKey, String version) {
        Optional<SangamFormService.DeployedForm> f = find(ctx, formKey, version);
        if (f.isEmpty()) {
            return base.get(ctx, formKey, version);
        }
        SangamFormService.DeployedForm d = f.get();
        return new FormVersion(d.key(), d.sha256(), d.sha256(), null, null, d.definition(), null, null, Map.of(), null,
                "ACTIVE");
    }

    @Override
    public ValidationResult validate(RequestContext ctx, String formKey, ValidateRequest request) {
        Optional<SangamFormService.DeployedForm> f = find(ctx, formKey, null);
        if (f.isEmpty()) {
            return base.validate(ctx, formKey, request);
        }
        SangamFormService.DeployedForm d = f.get();
        List<FieldError> errors = new ArrayList<>();
        if (request != null && request.formVersion() != null && !request.formVersion().equals(d.sha256())
                && !request.formVersion().equals(Integer.toString(d.version()))) {
            errors.add(new FieldError("", "FORM_VERSION_STALE", "error.FORM_VERSION_STALE"));
        }
        Map<String, Object> data = TypedVariables.fromJson(request == null ? null : request.data());
        FormValidator.Result r = PortsErrors.call(() -> FormValidator.validate(d.definition(), data));
        r.errors().forEach((field, codes) -> codes.forEach(code ->
                errors.add(new FieldError("/" + field, code, "error." + code))));
        return new ValidationResult(errors.isEmpty() && r.valid(), d.sha256(), errors);
    }

    @Override
    public Page<Draft> drafts(RequestContext ctx, String taskId, Page.PageRequest page) {
        return base.drafts(ctx, taskId, page);
    }

    @Override
    public Draft draft(RequestContext ctx, String draftId) {
        return base.draft(ctx, draftId);
    }

    @Override
    public Draft createDraft(RequestContext ctx, SaveDraftRequest request) {
        return base.createDraft(ctx, request);
    }

    @Override
    public Draft saveDraft(RequestContext ctx, String draftId, long expectedRevision, SaveDraftRequest request) {
        return base.saveDraft(ctx, draftId, expectedRevision, request);
    }

    @Override
    public void deleteDraft(RequestContext ctx, String draftId) {
        base.deleteDraft(ctx, draftId);
    }

    private Optional<SangamFormService.DeployedForm> find(RequestContext ctx, String formKey, String version) {
        if (formKey == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "form key is required");
        }
        String key = formKey.startsWith(SangamFormService.PREFIX) ? formKey.substring(SangamFormService.PREFIX.length()) : formKey;
        Integer number = null;
        if (version != null && version.matches("\\d{1,9}")) {
            number = Integer.valueOf(version);
        }
        final Integer n = number;
        Optional<SangamFormService.DeployedForm> f;
        try {
            f = forms.form(ctx.tenantId(), key, n);
        } catch (RuntimeException e) {
            // Ports form tables absent or form unknown there: the default adapter answers.
            return Optional.empty();
        }
        if (f.isPresent() && version != null && n == null && !version.equals(f.get().sha256())) {
            // A hash of an older ports version: search downwards.
            for (int v = f.get().version() - 1; v >= 1; v--) {
                final int vv = v;
                Optional<SangamFormService.DeployedForm> older = forms.form(ctx.tenantId(), key, vv);
                if (older.isPresent() && version.equals(older.get().sha256())) {
                    return older;
                }
            }
            return Optional.empty();
        }
        return f;
    }
}
