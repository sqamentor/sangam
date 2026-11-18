// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.packs.core.Vars;

/**
 * Owner decisions A1 / E3 for tests that start core execution directly with a money-out connector: core execution
 * pays only under a recorded human approval binding amount, payee account, currency and purpose
 * ({@code verifyApproval=false} relaxes only IN / NONE connectors). This binds such an approval to the given start
 * variables, exactly as a sanction's ApprovalBindingListener would.
 */
final class PackTestApprovals {

    private static final List<String> MATERIAL = List.of("amount", "payeeAccount", "currency", "purpose");

    private PackTestApprovals() {
    }

    static Map<String, Object> bindHumanApproval(Map<String, Object> v) {
        v.put("materialFields", String.join(",", MATERIAL));
        v.putIfAbsent("amount", 100L);
        v.put("payeeAccount", "123456789012@SBIN0000001");
        v.put("currency", "INR");
        v.put("purpose", "Bill payment");
        Map<String, Object> material = new LinkedHashMap<>();
        for (String f : MATERIAL) {
            material.put(f, Vars.canonical(v.get(f)));
        }
        v.put("approvedPayloadHash", Vars.hash(material));
        v.put("approvedBy", "p.officer");
        return v;
    }
}
