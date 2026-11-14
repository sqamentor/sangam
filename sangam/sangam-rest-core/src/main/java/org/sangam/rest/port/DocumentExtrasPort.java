// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;

/** Document versions, legal hold (R61) and template-based generation (R74, EC-18, S12 output documents). */
public interface DocumentExtrasPort {

    /** format: PDFA | DOCX | HTML. */
    record DocumentTemplate(String id, String name, List<String> languages, String format, int version) {
    }

    record LegalHoldRequest(boolean hold, String reason) {
    }

    record GenerateRequest(String templateId, String businessKey, String language) {
    }

    List<DocumentPort.Document> versions(RequestContext ctx, String documentId);

    DocumentPort.Document setLegalHold(RequestContext ctx, String documentId, LegalHoldRequest request);

    List<DocumentTemplate> templates(RequestContext ctx);

    DocumentPort.Document generate(RequestContext ctx, GenerateRequest request);
}
