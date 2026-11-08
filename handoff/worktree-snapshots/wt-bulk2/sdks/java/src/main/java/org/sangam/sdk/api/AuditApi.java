// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.AuditEntry;
import org.sangam.sdk.model.AuditVerifyResult;
import org.sangam.sdk.model.Page;

/** Audit timeline and hash-chain verification (R42, AC-01). */
public final class AuditApi extends ApiSupport {

    public AuditApi(HttpTransport http) {
        super(http);
    }

    /** {@code GET /audit/{businessKey}/timeline}; {@code includeHistory} merges class-A history (server default true). */
    public Page<AuditEntry> timeline(String businessKey, Boolean includeHistory, Integer page, Integer size) {
        return readPage(ApiRequest.get("/audit/" + seg(businessKey) + "/timeline").query("includeHistory", includeHistory)
                .query("page", page).query("size", size), AuditEntry.class);
    }

    public Page<AuditEntry> timeline(String businessKey) {
        return timeline(businessKey, null, null, null);
    }

    /** {@code GET /audit/verify}, optionally restricted to one chain or business key. */
    public AuditVerifyResult verify(String chainId, String businessKey) {
        return call(ApiRequest.get("/audit/verify").query("chainId", chainId).query("businessKey", businessKey),
                AuditVerifyResult.class);
    }
}
