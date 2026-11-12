// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;
import java.util.Map;

/**
 * Owner decisions 87 and 98: the field masks of every variable read. Field rules are classified per resource type and
 * definition key; each variable map belongs to one owning instance, against which the caller's permission is checked
 * (a field the caller may not read is replaced by {@code "***"}). Implemented by the security module (sangam-server
 * bridge to sangam-security {@code FieldMaskService}); without an implementation nothing is masked, because no field
 * rule can exist without the security module.
 *
 * <p>Resource types: {@link #PROCESS_INSTANCE} with the process definition key for every process, task and history
 * variable (the owning process instance), {@link #CASE_INSTANCE} with the case definition key for a CMMN case instance.
 */
public interface VariableMaskPort {

    String PROCESS_INSTANCE = "PROCESS_INSTANCE";
    String CASE_INSTANCE = "CASE_INSTANCE";

    /**
     * Masked copies of {@code variables}, one per entry and in the same order; {@code instanceIds.get(i)} owns
     * {@code variables.get(i)}. All entries share {@code resourceType} and {@code definitionKey}.
     */
    List<Map<String, Object>> mask(RequestContext ctx, String resourceType, String definitionKey, List<String> instanceIds,
                                   List<Map<String, Object>> variables);
}
