// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.register;

import org.sangam.registry.RegistryException;
import org.sangam.registry.kernel.ObjectSchema;
import org.sangam.registry.support.Maps;

import java.util.Map;

/**
 * A statutory register, as configuration (N09 {@code RegisterDefinition}):
 * <pre>{@code
 * { "registerKey": "inward", "title": "Inward register", "series": "INW",
 *   "scopeRequired": true, "correctionCountersign": true, "extractTemplate": "tpl-register-extract",
 *   "columns": { "fields": [ {"name": "receivedFrom", "type": "STRING", "required": true},
 *                            {"name": "subject", "type": "TEXT"} ] } }
 * }</pre>
 * Entries are numbered from the gap-free {@code series}; a book is one (register, scope, series period), so the
 * series reset rule (yearly, fiscal year, monthly) is also the book period.
 */
public record RegisterDefinition(String registerKey, String title, String series, boolean scopeRequired,
                                 boolean correctionCountersign, String extractTemplate, ObjectSchema columns,
                                 int version) {

    public static RegisterDefinition from(Map<String, Object> m, int version) {
        String key = Maps.required(m, "registerKey");
        if (key.length() > 64) {
            throw RegistryException.invalid("registerKey longer than 64 characters");
        }
        return new RegisterDefinition(key, Maps.str(m, "title", key), Maps.required(m, "series"),
                Maps.bool(m, "scopeRequired", false), Maps.bool(m, "correctionCountersign", true),
                Maps.str(m, "extractTemplate"), ObjectSchema.from(Maps.map(m, "columns")), version);
    }

    public Map<String, Object> toMap() {
        return Maps.of("registerKey", registerKey, "title", title, "series", series, "scopeRequired", scopeRequired,
                "correctionCountersign", correctionCountersign, "extractTemplate", extractTemplate,
                "columns", columns.toMap(), "version", version);
    }
}
