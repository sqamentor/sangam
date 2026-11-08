// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing.party;

import org.sangam.config.api.PayeeDirectory;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.party.PartyRecords;
import org.sangam.registry.party.PartyService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link PayeeDirectory} over the party registry of {@code sangam-registry} (no second registry). A payee is a party;
 * its bank account is an identifier of the configured identifier type (default {@code BANK_ACCOUNT}, value
 * {@code <account>@<routing code>}). Matching, merges, consent and audit are the registry's own.
 *
 * <ul>
 *   <li>resolve by {@code partyId}: follows merges, reports status and whether the account identifier is verified;</li>
 *   <li>resolve by account: {@code findMatches} with the account identifier and name; more than one matched party on
 *       the account sets {@code multipleBeneficiaries} (IFS-PB-005, IFS-TR-002);</li>
 *   <li>register: idempotent on the account identifier: an existing holder is returned, otherwise the party is created
 *       (the registry then records review candidates).</li>
 * </ul>
 */
public final class RegistryPayeeDirectory implements PayeeDirectory {

    private final DataSource ds;
    private final PartyService parties;
    private final String accountIdentifierType;
    private final Function<String, RegistryContext> context;

    /**
     * @param context tenant to registry context (actor = the system principal of the payment step, purpose
     *                {@code payment})
     */
    public RegistryPayeeDirectory(DataSource ds, PartyService parties, String accountIdentifierType,
                                  Function<String, RegistryContext> context) {
        this.ds = ds;
        this.parties = parties;
        this.accountIdentifierType = accountIdentifierType == null ? "BANK_ACCOUNT" : accountIdentifierType;
        this.context = context == null ? t -> RegistryContext.of(t, "system:payments").withPurpose("payment") : context;
    }

    @Override
    public Resolution resolve(String tenant, Map<String, Object> payee) {
        return tx(c -> resolve(c, context.apply(tenant), payee));
    }

    @Override
    public Resolution register(String tenant, Map<String, Object> payee, String operationId, String actor) {
        return tx(c -> {
            RegistryContext ctx = context.apply(tenant).withOperation(operationId);
            Resolution existing = resolve(c, ctx, payee);
            if (existing.partyId() != null) {
                return existing;
            }
            Map<String, Object> req = probe(payee);
            req.put("source", "payments");
            PartyService.CreateResult cr = parties.create(c, ctx, req);
            String id = cr.autoMergedInto() != null ? cr.autoMergedInto() : cr.party().id();
            return new Resolution(id, "ACTIVE", false, false, matches(cr.matches()));
        });
    }

    private Resolution resolve(Connection c, RegistryContext ctx, Map<String, Object> payee) {
        Object pid = payee.get("partyId");
        if (pid != null && !pid.toString().isBlank()) {
            String current = parties.resolve(c, ctx, pid.toString());
            PartyRecords.Party p = parties.get(c, ctx, current);
            boolean verified = parties.listIdentifiers(c, ctx, current).stream()
                    .anyMatch(i -> accountIdentifierType.equals(i.get("type")) && Boolean.TRUE.equals(i.get("verified")));
            return new Resolution(current, p.status(), verified, false, List.of());
        }
        if (account(payee) == null) {
            return new Resolution(null, "NOT_FOUND", false, false, List.of());
        }
        List<PartyRecords.MatchResult> res = parties.findMatches(c, ctx, probe(payee));
        Set<String> holders = new LinkedHashSet<>();
        for (PartyRecords.MatchResult m : res) {
            if (!"NON_MATCH".equals(m.decision())) {
                holders.add(parties.resolve(c, ctx, m.partyId()));
            }
        }
        String best = holders.isEmpty() ? null : holders.iterator().next();
        return new Resolution(best, best == null ? "NOT_FOUND" : "ACTIVE", false, holders.size() > 1, matches(res));
    }

    private Map<String, Object> probe(Map<String, Object> payee) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", payee.getOrDefault("payeeKind", "PERSON"));
        m.put("name", payee.get("payee"));
        List<Map<String, Object>> ids = new ArrayList<>();
        String acct = account(payee);
        if (acct != null) {
            ids.add(new LinkedHashMap<>(Map.of("type", accountIdentifierType, "value", acct)));
        }
        m.put("identifiers", ids);
        return m;
    }

    private static String account(Map<String, Object> payee) {
        Object a = payee.get("payeeAccount");
        if (a == null || a.toString().isBlank()) {
            return null;
        }
        Object r = payee.get("ifsc");
        return a + (r == null || r.toString().isBlank() ? "" : "@" + r);
    }

    private static List<Map<String, Object>> matches(List<PartyRecords.MatchResult> l) {
        return l.stream().map(PartyRecords.MatchResult::toMap).toList();
    }

    private <T> T tx(Function<Connection, T> f) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                T r = f.apply(c);
                c.commit();
                return r;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("payee directory: " + e.getMessage(), e);
        }
    }
}
