// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.quota;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * The per-tenant limits (R28, SL-43). Rate limits count units in fixed windows shared by every node; size limits
 * compare one amount against the configured maximum. The {@link #key()} is the name used in the tenant's
 * {@code quotas} map ({@code SEC_TENANT.quotas}, onboarding request {@code tenant.quotas}) and in the
 * {@code sangam.quotas.defaults.<key>} properties.
 */
public enum QuotaLimit {

    /** Process and case instance starts per second (all entry points: REST, engine-rest, Java API). */
    STARTS_PER_SECOND("startsPerSecond", Duration.ofSeconds(1), 50),
    /** Authenticated API calls per minute ({@code /api/**}, {@code /engine-rest/**}). */
    API_CALLS_PER_MINUTE("apiCallsPerMinute", Duration.ofMinutes(1), 6_000),
    /** Items in one batch / bulk request (default = the REST count cap). */
    BATCH_SIZE("maxBatchSize", null, 10_000),
    /** Serialized size of one process variable value, bytes (default 4 MiB, the Zeebe/Camunda 8 message size limit). */
    VARIABLE_BYTES("maxVariableBytes", null, 4L * 1024 * 1024),
    /** Size of one document version or upload request, bytes (default = sangam.content.max-bytes, 256 MiB). */
    DOCUMENT_BYTES("maxDocumentBytes", null, 256L * 1024 * 1024),
    /** Total stored document bytes of the tenant. */
    STORAGE_BYTES("maxStorageBytes", null, 50L * 1024 * 1024 * 1024);

    /** A configured value below zero means "no limit". */
    public static final long UNLIMITED = -1;

    private final String key;
    private final Duration window;
    private final long safeDefault;

    QuotaLimit(String key, Duration window, long safeDefault) {
        this.key = key;
        this.window = window;
        this.safeDefault = safeDefault;
    }

    public String key() {
        return key;
    }

    /** Counting window of a rate limit; null for a size limit. */
    public Duration window() {
        return window;
    }

    public boolean isRate() {
        return window != null;
    }

    /** The built-in default, used when neither the deployment nor the tenant configures the limit. */
    public long safeDefault() {
        return safeDefault;
    }

    /** By {@link #key()} (case-insensitive) or enum name. */
    public static Optional<QuotaLimit> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (QuotaLimit l : values()) {
            if (l.key.equalsIgnoreCase(name) || l.name().equals(name.toUpperCase(Locale.ROOT).replace('-', '_'))) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }
}
