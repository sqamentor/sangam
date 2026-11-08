// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.master;

import java.time.LocalDate;
import java.util.Map;

/**
 * One master code of a scheme segment, effective-dated, with its source (e.g. a correction-slip number) so the audit
 * shows which change created or retired it.
 *
 * @param parentCode code of the parent segment it is nested under (null for a top segment)
 * @param flags      attributes of the code (section, nature, voted/charged default, group, tags, ...)
 */
public record MasterCode(String scope, String scheme, String segment, String code, String parentCode,
                         Map<String, String> name, Map<String, Object> flags, LocalDate validFrom, LocalDate validTo,
                         String sourceRef) {

    public MasterCode {
        name = name == null ? Map.of() : Map.copyOf(name);
        flags = flags == null ? Map.of() : Map.copyOf(flags);
    }

    public boolean validOn(LocalDate d) {
        return (validFrom == null || !d.isBefore(validFrom)) && (validTo == null || d.isBefore(validTo));
    }

    public String key() {
        return scheme + "|" + segment + "|" + (parentCode == null ? "" : parentCode) + "|" + code;
    }
}
