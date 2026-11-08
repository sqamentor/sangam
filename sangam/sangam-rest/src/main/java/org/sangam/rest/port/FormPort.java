// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Forms contract (R47 / X.2.5; FLOWABLE-INTO-OPERATON FC-39): data schema (authoritative, JSON Schema 2020-12 profile), layout (form-js,
 * presentation only) and field policy (authoritative, default deny), versioned by their hash triple; server-side
 * validation; server-side drafts with {@code If-Match} revisions ({@code 409 DRAFT_CONFLICT}).
 * The form key {@code drafts} is reserved.
 */
public interface FormPort {

    record FormVersion(String key, String versionHash, String dataSchemaHash, String layoutHash,
                       String fieldPolicyHash, Object dataSchema, Object layout, Object fieldPolicy,
                       Map<String, Map<String, String>> i18n, String deploymentId, String state) {
    }

    record ValidateRequest(Map<String, Object> data, String taskId, String formVersion) {
    }

    record FieldError(String path, String code, String messageKey) {
    }

    record ValidationResult(boolean valid, String formVersion, List<FieldError> errors) {
    }

    /** ownerKind: PERSON | SEAT (X.2.5.8 rule 3). */
    record Draft(String draftId, String tenantId, String taskId, String formKey, String formVersion, String ownerKind,
                 String ownerPrincipalId, String ownerSeatId, long revision, Map<String, Object> data,
                 Instant updatedAt, Instant expiresAt, String lastEditorPrincipalId) {
    }

    record SaveDraftRequest(String taskId, String formKey, String formVersion, String ownerKind,
                            Map<String, Object> data) {
    }

    /** {@code version} null = the version bound to the task, else the ACTIVE one. */
    FormVersion get(RequestContext ctx, String formKey, String version);

    ValidationResult validate(RequestContext ctx, String formKey, ValidateRequest request);

    Page<Draft> drafts(RequestContext ctx, String taskId, Page.PageRequest page);

    Draft draft(RequestContext ctx, String draftId);

    Draft createDraft(RequestContext ctx, SaveDraftRequest request);

    Draft saveDraft(RequestContext ctx, String draftId, long expectedRevision, SaveDraftRequest request);

    void deleteDraft(RequestContext ctx, String draftId);
}
