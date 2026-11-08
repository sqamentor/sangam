// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;
import java.util.Map;

import org.sangam.billing.BillingConnector;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.support.TxSupport;

/**
 * Accounts billed in a cycle ({@code billing.accounts}): every consumer that holds a demand account in one of the
 * tenant's RECEIVABLE ledgers ({@code LDG_BALANCE.holder}, stage {@code DEMAND}). A new consumer joins the register with
 * its first demand (the connection pack raises it). Each item is {@code {consumer, ledgerId}}; the per-cycle batch
 * (GP-09) starts one case per item.
 */
public final class BillingAccountSource implements BillingConnector.AccountSource {

    private final TxSupport tx;
    private final LedgerTenantDefinitions definitions;

    public BillingAccountSource(TxSupport tx, LedgerTenantDefinitions definitions) {
        this.tx = tx;
        this.definitions = definitions;
    }

    @Override
    public List<Map<String, Object>> accounts(String tenant, String cycle) {
        definitions.ensure(tenant);
        List<String> receivable = definitions.ids(tenant).stream()
                .filter(id -> {
                    try {
                        return definitions.ledger().definition(tenant, id).kind().name().equals("RECEIVABLE");
                    } catch (RuntimeException e) {
                        return false;
                    }
                }).toList();
        if (receivable.isEmpty()) {
            receivable = List.of("DEMAND");
        }
        String in = String.join(",", java.util.Collections.nCopies(receivable.size(), "?"));
        Object[] params = new Object[receivable.size() + 1];
        params[0] = tenant;
        for (int i = 0; i < receivable.size(); i++) {
            params[i + 1] = receivable.get(i);
        }
        return tx.withConnection(c -> Jdbc.query(c, "SELECT DISTINCT holder, ledger_id FROM LDG_BALANCE WHERE tenant_id = ?"
                + " AND ledger_id IN (" + in + ") AND stage = 'DEMAND' AND holder <> '-' ORDER BY holder",
                rs -> Map.<String, Object>of("consumer", rs.getString(1), "ledgerId", rs.getString(2)), params));
    }
}
