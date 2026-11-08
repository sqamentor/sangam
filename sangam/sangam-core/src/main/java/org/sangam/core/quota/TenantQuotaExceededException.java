// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.quota;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tenant went over one of its quotas (SL-43). Stable error code {@value #CODE}; {@link #limit()} says which.
 * HTTP mapping: a rate limit answers {@code 429 Too Many Requests} with {@code Retry-After} = the seconds until the
 * counting window ends (RFC 6585 §4, RFC 9110 §10.2.3); a size limit answers {@code 413 Content Too Large}
 * (RFC 9110 §15.5.14) without Retry-After, because resending the same request can never succeed.
 */
public final class TenantQuotaExceededException extends RuntimeException {

    public static final String CODE = "TENANT_QUOTA_EXCEEDED";

    private final String tenantId;
    private final QuotaLimit limit;
    private final long allowed;
    private final long requested;
    private final Integer retryAfterSeconds;

    public TenantQuotaExceededException(String tenantId, QuotaLimit limit, long allowed, long requested,
                                        Integer retryAfterSeconds) {
        super(CODE + ": tenant " + tenantId + " " + limit.key() + " limit " + allowed + " (requested " + requested + ")");
        this.tenantId = tenantId;
        this.limit = limit;
        this.allowed = allowed;
        this.requested = requested;
        this.retryAfterSeconds = limit.isRate() ? (retryAfterSeconds == null ? 1 : Math.max(1, retryAfterSeconds)) : null;
    }

    public String tenantId() {
        return tenantId;
    }

    public QuotaLimit limit() {
        return limit;
    }

    public long allowed() {
        return allowed;
    }

    public long requested() {
        return requested;
    }

    /** Seconds until the window resets (rate limits); null for size limits. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int httpStatus() {
        return limit.isRate() ? 429 : 413;
    }

    /** Problem-detail extension members: {@code tenant}, {@code limit}, {@code allowed}, {@code requested}. */
    public Map<String, Object> details() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", tenantId);
        m.put("limit", limit.key());
        m.put("allowed", allowed);
        m.put("requested", requested);
        return m;
    }

    /** The first quota exception in a cause chain (engines and proxies wrap exceptions). */
    public static TenantQuotaExceededException find(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            if (x instanceof TenantQuotaExceededException q) {
                return q;
            }
        }
        return null;
    }
}
