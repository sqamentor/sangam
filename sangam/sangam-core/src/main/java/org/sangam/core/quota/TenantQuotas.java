// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.quota;

/**
 * Per-tenant quota port (SL-43). The shared entry points call it: the REST filter (API calls, upload size), the
 * engine command interceptor (starts, variable size), the batch service (batch size) and the content service
 * (document size, storage). The implementation (sangam-security {@code JdbcTenantQuotas}) counts in the database
 * so every node sees the same totals. Components without dependency injection read {@link #current()}.
 */
public interface TenantQuotas {

    /** Takes {@code permits} units of a rate limit; throws {@link TenantQuotaExceededException} above the limit. */
    void acquire(String tenantId, QuotaLimit limit, long permits);

    /** Checks one amount against a size limit; throws {@link TenantQuotaExceededException} above it. */
    void checkSize(String tenantId, QuotaLimit limit, long amount);

    /** The effective limit of the tenant ({@link QuotaLimit#UNLIMITED} = none). */
    long limit(String tenantId, QuotaLimit limit);

    /** No quotas (tests, embedded use). */
    TenantQuotas NONE = new TenantQuotas() {
        @Override
        public void acquire(String tenantId, QuotaLimit limit, long permits) {
        }

        @Override
        public void checkSize(String tenantId, QuotaLimit limit, long amount) {
        }

        @Override
        public long limit(String tenantId, QuotaLimit limit) {
            return QuotaLimit.UNLIMITED;
        }
    };

    /** The installed quotas, {@link #NONE} until the server installs one. */
    static TenantQuotas current() {
        return Holder.current;
    }

    static void install(TenantQuotas quotas) {
        Holder.current = quotas == null ? NONE : quotas;
    }

    /** Helper for size checks done by the caller: true when {@code amount} is within the tenant's limit. */
    static boolean within(long limitValue, long amount) {
        return limitValue < 0 || amount <= limitValue;
    }

    final class Holder {
        private static volatile TenantQuotas current = NONE;

        private Holder() {
        }
    }
}
