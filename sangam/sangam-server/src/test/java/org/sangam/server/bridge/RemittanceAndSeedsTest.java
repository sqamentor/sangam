// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.layer.Dialect;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.JdbcLedgerStore;
import org.sangam.ledger.LedgerConnector;
import org.sangam.ledger.LedgerControlStore;
import org.sangam.ledger.LedgerDefinition;
import org.sangam.ledger.LedgerRequest;
import org.sangam.ledger.LedgerSchema;
import org.sangam.ledger.LedgerService;
import org.sangam.registry.party.IdentifierTypeCatalogue;
import org.sangam.registry.party.PartyService;

/** 835 posting legs on a real H2 ledger, and identifier seeds through the real loader, scoped by jurisdiction. */
class RemittanceAndSeedsTest {

    static LedgerService ledger(String db) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + db + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            LedgerSchema.create(c, Dialect.POSTGRESQL);
        }
        LedgerService s = new LedgerService(new JdbcLedgerStore(ds, Dialect.POSTGRESQL),
                new LedgerControlStore.JdbcStore(ds, Dialect.POSTGRESQL), null);
        s.define("T", List.of(LedgerDefinition.from(Map.of("id", "RECEIVABLES", "kind", "RECEIVABLE", "currency", "USD",
                "stages", List.of("DEMAND")))));
        return s;
    }

    static AccountKey claim(String holder) {
        return new AccountKey("T", "RECEIVABLES", "OPEN", "CLAIM", holder, "DEMAND", null);
    }

    static ConnectorRequest post(String claimsJson) {
        return new ConnectorRequest("T", UUID.randomUUID(), "a1", 1, "root", "BK", "post",
                Map.of("eraClaims", claimsJson, "ledgerId", "RECEIVABLES", "line", "CLAIM", "period", "OPEN"), null);
    }

    @Test
    void claimPostsPaidAdjustmentAndPatientLegs() throws Exception {
        LedgerService s = ledger("remit1");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", claim("PCN1"), new BigDecimal("100"), "a")).applied());
        var posting = new PackGapConnectors.RemittancePosting(new LedgerConnector(s, (t, d) -> "OPEN", Clock.systemUTC()));
        var r = (ConnectorResult.Success) posting.invoke(post("""
                [{"patientControlNumber":"PCN1","status":"PAID_PRIMARY","paidAmount":"7000","claimBalanced":true,
                  "adjustmentTotals":{"CO":"2000","PR":"1000"}}]""")); // cents (SD-398)
        assertEquals(0, r.output().get("exceptionCount"), String.valueOf(r.output()));
        assertEquals(3, r.output().get("legsPosted"));
        assertEquals(0, s.available(claim("PCN1")).signum(), "receivable cleared");
        assertEquals(0, new BigDecimal("10").compareTo(s.available(claim("patient:PCN1"))), "patient balance");
    }

    @Test
    void unmatchedUnbalancedAndReversalGoToExceptionsOrReverse() throws Exception {
        LedgerService s = ledger("remit2");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", claim("PCN2"), new BigDecimal("50"), "a")).applied());
        var posting = new PackGapConnectors.RemittancePosting(new LedgerConnector(s, (t, d) -> "OPEN", Clock.systemUTC()));
        posting.invoke(post("[{\"patientControlNumber\":\"PCN2\",\"status\":\"PAID_PRIMARY\",\"paidAmount\":\"5000\"}]"));
        assertEquals(0, s.available(claim("PCN2")).signum());
        var r = (ConnectorResult.Success) posting.invoke(post("""
                [{"patientControlNumber":"NOPE","status":"PAID_PRIMARY","paidAmount":"500"},
                 {"patientControlNumber":"PCN2","status":"PAID_PRIMARY","paidAmount":"500","claimBalanced":false},
                 {"patientControlNumber":"PCN2","status":"REVERSAL","paidAmount":"-5000"}]"""));
        assertEquals(2, r.output().get("exceptionCount"), String.valueOf(r.output()));
        assertEquals(0, new BigDecimal("50").compareTo(s.available(claim("PCN2"))), "reversal restores the receivable");
    }

    static ConnectorRequest traced(String trace, String paid) {
        return new ConnectorRequest("T", UUID.randomUUID(), "a1", 1, "root", "BK", "post", Map.of("eraTraceNumber", trace,
                "ledgerId", "RECEIVABLES", "line", "CLAIM", "period", "OPEN", "eraClaims",
                "[{\"patientControlNumber\":\"PCN3\",\"status\":\"PAID_PRIMARY\",\"paidAmount\":\"" + paid + "\"}]"), null);
    }

    @Test
    void secondRemittancePostsSeparatelyAndReplayDoesNot() throws Exception {
        LedgerService s = ledger("remit3");
        LedgerConnector lc = new LedgerConnector(s, (t, d) -> "OPEN", Clock.systemUTC());
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", claim("PCN3"), new BigDecimal("100"), "a")).applied());
        var posting = new PackGapConnectors.RemittancePosting(lc);
        posting.invoke(traced("T1", "3000"));
        posting.invoke(traced("T1", "3000")); // new operation id, same 835
        assertEquals(0, new BigDecimal("70").compareTo(s.available(claim("PCN3"))), "replay posts nothing");
        posting.invoke(traced("T2", "3000"));
        assertEquals(0, new BigDecimal("40").compareTo(s.available(claim("PCN3"))));
        posting.invoke(traced("no-trace", "500"));
        posting.invoke(traced("no-trace", "500"));
        assertEquals(0, new BigDecimal("30").compareTo(s.available(claim("PCN3"))), "no trace: never swallowed as a replay");
        lc.invoke(new ConnectorRequest("T", UUID.randomUUID(), "a1", 1, "root", "BK", "adjust", Map.of(
                "ledgerId", "RECEIVABLES", "line", "CLAIM", "period", "OPEN", "holder", "PCN3", "amount", "1000"), null));
        assertEquals(0, new BigDecimal("40").compareTo(s.available(claim("PCN3"))), "second remittance posts");
        var adj = lc.invoke(new ConnectorRequest("T", UUID.randomUUID(), "a1", 1, "root", "BK", "adjust", Map.of(
                "ledgerId", "RECEIVABLES", "line", "CLAIM", "period", "OPEN", "holder", "PCN3", "amount", "-1500"), null));
        assertTrue(adj instanceof ConnectorResult.Success, String.valueOf(adj));
        assertEquals(0, new BigDecimal("25").compareTo(s.available(claim("PCN3"))), "corrected charge lowers the receivable");
    }

    @Test
    void identifierSeedsApplyOnlyToTheirJurisdiction() {
        PartyService parties = new PartyService(Dialect.POSTGRESQL, null, null);
        RegistrySeedLoader.Result res = RegistrySeedLoader.load(parties, getClass().getClassLoader());
        assertTrue(res.files() >= 3, String.valueOf(res));
        IdentifierTypeCatalogue cat = parties.identifierTypes();
        cat.jurisdictionResolver(t -> Map.of("in-tenant", "IN", "us-tenant", "US").get(t));
        assertNotNull(cat.find("in-tenant", "AADHAAR"));
        assertNull(cat.find("us-tenant", "AADHAAR"));
        assertNull(cat.find("other", "PAN"));
        assertNotNull(cat.find("us-tenant", "NPI"));
        assertNull(cat.find("in-tenant", "NPI"));
        assertNotNull(cat.find("other", "BANK_ACCOUNT"));
    }
}
