// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.delegation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.power.OperationMetadata;
import org.sangam.core.power.OperationMetadata.Classification;
import org.sangam.core.power.PowerDecision;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.SangamSecurity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.delegation.DelegationOfPowersService.PowerTestCase;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;

/**
 * RM-08 (owner decisions 142, 193 and 222) through {@link DelegationOfPowersService} on H2: the service decides with
 * the one power-limit evaluator of sangam-core. Unknown metadata, a missing amount or currency, no table, no rule, a
 * malformed value and a failed evaluation are denials; of several limits the lowest ceiling binds; UNLIMITED lifts only
 * the delegated ceiling, never another row's ceiling or the posting power cap; the boundary is inclusive and exact.
 * The tables: {@code dop} selects its rows by the caller extra {@code scenario} (hit policy COLLECT), {@code dop-ct} by
 * the table's {@code caseType}, {@code dop-bad} has two overlapping rows under hit policy UNIQUE (its evaluation fails).
 */
class DelegationOfPowersServiceH2Test {

    static final String T = "t";
    static final String CASE_TYPE = "GRANT-CASE";
    static final Instant NOW = Instant.now();
    static final Instant SINCE = Instant.parse("2020-01-01T00:00:00Z");

    /** The deployed manifest of the test: case type GRANT-CASE version 3 in tenant t. */
    static final OperationMetadata MANIFEST = (tenant, caseType, version, action) -> {
        if (!T.equals(tenant) || !CASE_TYPE.equals(caseType) || version != 3) {
            return Optional.empty();
        }
        String pack = "pack:grants@1.0.0";
        return switch (action) {
            case "SANCTION" -> Optional.of(new Classification(true, "INR", "dop", pack));
            case "DISPOSE" -> Optional.of(new Classification(false, null, "dop", pack));
            case "SANCTION_BAD" -> Optional.of(new Classification(true, "INR", "dop-bad", pack));
            case "SANCTION_CT" -> Optional.of(new Classification(true, "INR", "dop-ct", pack, "GRANT"));
            case "SANCTION_CT_NONE" -> Optional.of(new Classification(true, "INR", "dop-ct", pack));
            default -> Optional.empty();
        };
    };

    static String rule(String id, String scenario, String maxAmount, String allowed) {
        return "<rule id=\"" + id + "\"><inputEntry id=\"" + id + "i\"><text>\"" + scenario + "\"</text></inputEntry>"
                + "<outputEntry id=\"" + id + "m\"><text>" + (maxAmount == null ? "" : "\"" + maxAmount + "\"")
                + "</text></outputEntry><outputEntry id=\"" + id + "a\"><text>" + (allowed == null ? "" : allowed)
                + "</text></outputEntry><outputEntry id=\"" + id + "r\"><text>\"" + id.toUpperCase() + "\"</text></outputEntry>"
                + "</rule>";
    }

    static String table(String hitPolicy, String input, String rules) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\" name=\"d\" namespace=\"sangam\">"
                + "<decision id=\"powers\" name=\"powers\"><decisionTable id=\"t\" hitPolicy=\"" + hitPolicy + "\">"
                + "<input id=\"i1\"><inputExpression id=\"e1\" typeRef=\"string\"><text>" + input + "</text></inputExpression></input>"
                + "<output id=\"o1\" name=\"maxAmount\" typeRef=\"string\"/>"
                + "<output id=\"o2\" name=\"allowed\" typeRef=\"boolean\"/>"
                + "<output id=\"o3\" name=\"ruleRef\" typeRef=\"string\"/>" + rules
                + "</decisionTable></decision></definitions>";
    }

    static final String DOP = table("COLLECT", "scenario",
            rule("r1", "two", "500", null) + rule("r2", "two", "10000", null)
                    + rule("r3", "mixed", "500", null) + rule("r4", "mixed", "UNLIMITED", null)
                    + rule("r5", "unlimited", "UNLIMITED", null)
                    + rule("r6", "band", "100000", null)
                    + rule("r7", "malformed", "ten lakh", null)
                    + rule("r8", "nolimit", null, "true")
                    + rule("r9", "forbidden", "1000", "false"));
    static final String DOP_CT = table("COLLECT", "caseType", rule("c1", "GRANT", "700", null));
    static final String DOP_BAD = table("UNIQUE", "scenario", rule("b1", "dup", "500", null) + rule("b2", "dup", "900", null));

    private Connection c;
    private DelegationOfPowersService powers;

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:dop" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);   // AUTH_EFFECTIVE, AUTH_POLICY_VERSION, AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ
        Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                + " resource_type VARCHAR(64), expression TEXT)");
        // the tables the service reads, as in the H2 baseline changelog (sangam-persistence)
        Jdbc.update(c, "CREATE TABLE SEC_POWER_TABLE (tenant_id VARCHAR(64) NOT NULL, table_key VARCHAR(64) NOT NULL,"
                + " version INTEGER NOT NULL, status VARCHAR(16) NOT NULL, effective_from TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " effective_to TIMESTAMP WITH TIME ZONE, dmn_xml CHARACTER LARGE OBJECT NOT NULL, dmn_hash VARCHAR(64) NOT NULL,"
                + " decision_id VARCHAR(255) NOT NULL, authority_ref VARCHAR(1000), change_note VARCHAR(1000),"
                + " author VARCHAR(255) NOT NULL, approver VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " published_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, table_key, version))");
        Jdbc.update(c, "CREATE TABLE SEC_SEAT (tenant_id VARCHAR(64) NOT NULL, seat_id VARCHAR(64) NOT NULL,"
                + " designation VARCHAR(255) NOT NULL, rank_no INTEGER NOT NULL, pool_flag SMALLINT NOT NULL,"
                + " escalation_seat_id VARCHAR(64), status VARCHAR(16) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " abolished_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, seat_id))");
        Jdbc.update(c, "CREATE TABLE SEC_SEAT_PLACEMENT (tenant_id VARCHAR(64) NOT NULL, seat_id VARCHAR(64) NOT NULL,"
                + " unit_id VARCHAR(64) NOT NULL, valid_from TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " valid_to TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, seat_id, valid_from))");
        Jdbc.update(c, "CREATE TABLE SEC_ORG_UNIT (tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL,"
                + " level_type VARCHAR(64) NOT NULL, level_no INTEGER NOT NULL, name_en VARCHAR(255), name_hi VARCHAR(255),"
                + " head_seat_id VARCHAR(64), status VARCHAR(16) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " PRIMARY KEY (tenant_id, unit_id))");
        Jdbc.update(c, "CREATE TABLE SEC_ORG_UNIT_PARENT (tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL,"
                + " parent_id VARCHAR(64), valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE,"
                + " PRIMARY KEY (tenant_id, unit_id, valid_from))");
        Jdbc.update(c, "CREATE TABLE SEC_ORG_UNIT_NAME (tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL,"
                + " valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE, name_en VARCHAR(255) NOT NULL,"
                + " name_hi VARCHAR(255), order_ref VARCHAR(255), created_by VARCHAR(255),"
                + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, unit_id, valid_from))");
        Jdbc.update(c, "CREATE TABLE SEC_POSTING (tenant_id VARCHAR(64) NOT NULL, posting_id VARCHAR(64) NOT NULL,"
                + " seat_id VARCHAR(64) NOT NULL, person_id VARCHAR(64) NOT NULL, kind VARCHAR(32) NOT NULL,"
                + " valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE, order_ref VARCHAR(255),"
                + " power_cap VARCHAR(40), created_by VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " revoked_at TIMESTAMP WITH TIME ZONE, expiry_notified SMALLINT NOT NULL, PRIMARY KEY (tenant_id, posting_id))");
        Jdbc.update(c, "CREATE TABLE SEC_LEAVE (tenant_id VARCHAR(64) NOT NULL, leave_id VARCHAR(64) NOT NULL,"
                + " person_id VARCHAR(64) NOT NULL, valid_from TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " valid_to TIMESTAMP WITH TIME ZONE NOT NULL, substitute_person_id VARCHAR(64), requested_by VARCHAR(255),"
                + " approved_by VARCHAR(255), status VARCHAR(16) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " PRIMARY KEY (tenant_id, leave_id))");

        Jdbc.update(c, "INSERT INTO SEC_ORG_UNIT VALUES (?, 'U1', 'DISTRICT', 2, 'Unit one', NULL, NULL, 'ACTIVE', ?)", T, SINCE);
        Jdbc.update(c, "INSERT INTO SEC_SEAT VALUES (?, 'S1', 'Collector', 3, 0, NULL, 'ACTIVE', ?, NULL)", T, SINCE);
        Jdbc.update(c, "INSERT INTO SEC_SEAT VALUES (?, 'S-GONE', 'Collector', 3, 0, NULL, 'ABOLISHED', ?, ?)", T, SINCE, SINCE);
        Jdbc.update(c, "INSERT INTO SEC_SEAT_PLACEMENT VALUES (?, 'S1', 'U1', ?, NULL)", T, SINCE);
        // the regular holder of S1, with a posting power cap of 2500
        Jdbc.update(c, "INSERT INTO SEC_POSTING VALUES (?, 'P1', 'S1', 'officer', 'REGULAR', ?, NULL, 'ORD-1', '2500',"
                + " 'setup', ?, NULL, 0)", T, SINCE, SINCE);
        published("dop", DOP);
        published("dop-ct", DOP_CT);
        published("dop-bad", DOP_BAD);
        Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 1)", T);
        Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                        + " condition_id, cond_key, policy_version) VALUES (?, 'role:tenant-admin', ?, '*', 0, ?, NULL, '-', 0)",
                T, ResourceRegistry.POWER_TABLE, Permission.ADMIN.bit() | Permission.REVIEW_PACKAGE.bit());

        powers = new SangamSecurity(SecurityDialect.H2, null).powers();
        powers.useOperationMetadata(MANIFEST);
    }

    private void published(String key, String xml) {
        Jdbc.update(c, "INSERT INTO SEC_POWER_TABLE(tenant_id, table_key, version, status, effective_from, effective_to,"
                        + " dmn_xml, dmn_hash, decision_id, authority_ref, change_note, author, approver, created_at,"
                        + " published_at) VALUES (?,?,1,'PUBLISHED',?,NULL,?,?,'powers',NULL,NULL,'author','reviewer',?,?)",
                T, key, SINCE, xml, Hashes.hex(Hashes.sha256(xml)), SINCE, SINCE);
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    /** The extras of a call: the table's {@code scenario} and what the port has no parameter for. */
    static Map<String, Object> extras(String scenario, String action, String currency) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", scenario);
        if (action != null) {
            m.put(DelegationOfPowersService.IN_ACTION, action);
        }
        m.put(DelegationOfPowersService.IN_CASE_TYPE_VERSION, 3);
        if (currency != null) {
            m.put(DelegationOfPowersService.IN_CURRENCY, currency);
        }
        return m;
    }

    private SecurityPorts.PowerDecision sanction(String scenario, String amount) {
        return powers.check(c, T, "dop", "S1", CASE_TYPE, dec(amount), NOW, extras(scenario, "SANCTION", "INR"));
    }

    private void denied(SecurityPorts.PowerDecision d, String reasonPart) {
        assertFalse(d.allowed(), d.toString());
        assertTrue(d.reason().contains(reasonPart), "reason '" + d.reason() + "' names: " + reasonPart);
    }

    static SecurityIdentity person(String id, String... roles) {
        return new SecurityIdentity(T, id, id, Set.of(), Set.of(roles), Set.of(), Set.of(), "OIDC", "10.0.0.1", null, false,
                Map.of());
    }

    private List<String> audit(String chain, String type) {
        return Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                + " WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=? ORDER BY e.seq", rs -> rs.getString(1), T, chain, type);
    }

    // ------------------------------------------------------------------ DENY: metadata, amount, currency

    @Test
    void withoutBoundMetadataEveryCheckIsDenied() {
        DelegationOfPowersService unbound = new SangamSecurity(SecurityDialect.H2, null).powers();
        SecurityPorts.PowerDecision d = unbound.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW,
                extras("unlimited", "SANCTION", "INR"));
        denied(d, "no trusted metadata");
        assertTrue(sanction("unlimited", "1").allowed(), "the same call with the manifest bound");
        assertThrows(NullPointerException.class, () -> powers.useOperationMetadata(null));
    }

    @Test
    void anUnknownCaseTypeVersionOrActionIsDenied() {
        Map<String, Object> in = extras("unlimited", "SANCTION", "INR");
        denied(powers.check(c, T, "dop", "S1", "OTHER-CASE", dec("1"), NOW, in), "no trusted metadata");
        in.put(DelegationOfPowersService.IN_CASE_TYPE_VERSION, 4);
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, in), "no trusted metadata");
        in.put(DelegationOfPowersService.IN_CASE_TYPE_VERSION, "three");
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, in), "no trusted metadata");
        in.remove(DelegationOfPowersService.IN_CASE_TYPE_VERSION);
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, in), "no trusted metadata");
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, extras("unlimited", "PAY", "INR")),
                "no trusted metadata");
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, extras("unlimited", null, "INR")),
                "without actor, action");
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, null), "without actor, action");
        // the string form of the version is the same version
        Map<String, Object> text = extras("unlimited", "SANCTION", "INR");
        text.put(DelegationOfPowersService.IN_CASE_TYPE_VERSION, "3");
        assertTrue(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, text).allowed());
    }

    @Test
    void aMonetaryActionNeedsAnAmountAndTheDeclaredCurrency() {
        denied(sanction("unlimited", null), "without an amount");
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, extras("unlimited", "SANCTION", null)),
                "without a valid currency");
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, extras("unlimited", "SANCTION", "inr")),
                "without a valid currency");
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, extras("unlimited", "SANCTION", "USD")),
                "declares INR");
        assertTrue(sanction("unlimited", "0").allowed(), "zero is an amount");
    }

    // ------------------------------------------------------------------ DENY: table, seat, rows

    @Test
    void noTableInForceTheWrongTableAndAnInactiveSeatAreDenied() {
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), Instant.parse("2019-06-01T00:00:00Z"),
                extras("unlimited", "SANCTION", "INR")), "no delegation-of-powers table in force");
        // the caller cannot choose the table: the manifest declares dop for SANCTION
        SecurityPorts.PowerDecision wrong = powers.check(c, T, "dop-ct", "S1", CASE_TYPE, dec("1"), NOW,
                extras("unlimited", "SANCTION", "INR"));
        denied(wrong, "declares limit table dop");
        assertEquals(0, wrong.tableVersion(), "the other table was not read");
        denied(powers.check(c, T, "dop", "S-GONE", CASE_TYPE, dec("1"), NOW, extras("unlimited", "SANCTION", "INR")),
                "seat S-GONE is not active");
        denied(powers.check(c, T, "dop", "S-NONE", CASE_TYPE, dec("1"), NOW, extras("unlimited", "SANCTION", "INR")),
                "seat S-NONE is not active");
    }

    @Test
    void rowsThatStateNoPowerOrAMalformedCeilingAreDenied() {
        denied(sanction("none", "1"), "no delegation-of-powers row matched");
        denied(sanction("nolimit", "1"), "no maxAmount");
        denied(sanction("forbidden", "1"), "allowed = false");
        denied(sanction("malformed", "1"), "malformed ceiling");
        SecurityPorts.PowerDecision d = sanction("malformed", "1");
        assertEquals(1, d.tableVersion(), "the table in force was read");
        assertEquals("R7", d.matchedRule());
    }

    @Test
    void aFailedEvaluationIsADenialNotAnException() {
        SecurityPorts.PowerDecision d = powers.check(c, T, "dop-bad", "S1", CASE_TYPE, dec("1"), NOW,
                extras("dup", "SANCTION_BAD", "INR"));
        denied(d, "power rule evaluation failed");
    }

    // ------------------------------------------------------------------ LIMITED and UNLIMITED

    @Test
    void ofSeveralLimitsTheLowestCeilingBinds() {
        SecurityPorts.PowerDecision at = sanction("two", "500");
        assertTrue(at.allowed(), at.reason());
        assertEquals(0, new BigDecimal("500").compareTo(at.limit()), "the binding ceiling is the lowest: " + at.limit());
        assertEquals("R1", at.matchedRule());
        assertEquals(1, at.tableVersion());
        denied(sanction("two", "500.01"), "exceeds the delegated power 500");
        denied(sanction("two", "501"), "exceeds the delegated power 500");
        denied(sanction("two", "10000"), "exceeds the delegated power 500");
    }

    @Test
    void anUnlimitedRowNeverLiftsAnotherRowsCeiling() {
        assertTrue(sanction("mixed", "500").allowed());
        denied(sanction("mixed", "501"), "exceeds the delegated power 500");
    }

    @Test
    void unlimitedLiftsOnlyTheDelegatedCeilingNeverThePostingCap() {
        SecurityPorts.PowerDecision free = sanction("unlimited", "999999999999.99");
        assertTrue(free.allowed(), free.reason());
        assertNull(free.limit(), "no delegated ceiling and, in a check without a person, no posting cap");

        SecurityIdentity officer = person("officer");
        SecurityPorts.PowerDecision capped = powers.require(c, officer, "dop", "S1", CASE_TYPE, dec("2500"),
                extras("unlimited", "SANCTION", "INR"));
        assertTrue(capped.allowed());
        assertEquals(0, new BigDecimal("2500").compareTo(capped.limit()), "the posting cap still binds");
        SangamSecurityException.PowerLimitExceeded over = assertThrows(SangamSecurityException.PowerLimitExceeded.class,
                () -> powers.require(c, officer, "dop", "S1", CASE_TYPE, dec("2500.01"), extras("unlimited", "SANCTION", "INR")));
        assertTrue(over.getMessage().contains("mandatory cap"), over.getMessage());
        // the cap also binds below a delegated ceiling: 10000 and 500 are delegated, the lowest of all three applies
        assertEquals(0, new BigDecimal("500").compareTo(powers.require(c, officer, "dop", "S1", CASE_TYPE, dec("500"),
                extras("two", "SANCTION", "INR")).limit()));
        assertThrows(SangamSecurityException.PowerLimitExceeded.class, () -> powers.require(c, officer, "dop", "S1",
                CASE_TYPE, dec("100000"), extras("band", "SANCTION", "INR")), "the band allows 100000, the cap 2500");
    }

    @Test
    void theBoundaryIsInclusiveAndExact() {
        assertTrue(sanction("band", "99999.99").allowed());
        assertTrue(sanction("band", "100000").allowed());
        assertTrue(sanction("band", "100000.00").allowed(), "scale does not matter");
        denied(sanction("band", "100000.01"), "exceeds the delegated power 100000");
        denied(sanction("band", "100001"), "exceeds the delegated power 100000");
    }

    @Test
    void aNegativeAmountIsComparedByItsMagnitude() {
        assertTrue(sanction("band", "-100000").allowed());
        denied(sanction("band", "-100000.01"), "exceeds the delegated power 100000");
        denied(sanction("two", "-501"), "exceeds the delegated power 500");
    }

    @Test
    void aNonMonetaryActionIsDecidedByTheRowsAlone() {
        assertTrue(powers.check(c, T, "dop", "S1", CASE_TYPE, null, NOW, extras("unlimited", "DISPOSE", null)).allowed());
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, null, NOW, extras("forbidden", "DISPOSE", null)), "allowed = false");
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, null, NOW, extras("none", "DISPOSE", null)), "no delegation-of-powers row");
        // a ceiling confers the power; without a stated currency the table's currency is unknown and it does not
        denied(powers.check(c, T, "dop", "S1", CASE_TYPE, null, NOW, extras("two", "DISPOSE", null)), "currency is unknown");
        assertTrue(powers.check(c, T, "dop", "S1", CASE_TYPE, null, NOW, extras("two", "DISPOSE", "INR")).allowed());
    }

    // ------------------------------------------------------------------ decision 222: the table's case type

    @Test
    void theTablesCaseTypeComesFromTheManifestNeverFromTheCaller() {
        Map<String, Object> in = extras("unused", "SANCTION_CT", "INR");
        in.put("caseType", "OTHER");   // a caller value never chooses the row
        assertTrue(powers.check(c, T, "dop-ct", "S1", CASE_TYPE, dec("700"), NOW, in).allowed());
        denied(powers.check(c, T, "dop-ct", "S1", CASE_TYPE, dec("700.01"), NOW, in), "exceeds the delegated power 700");
        // a manifest that declares no rule case type gives the table none: the caller's GRANT is not used either
        Map<String, Object> none = extras("unused", "SANCTION_CT_NONE", "INR");
        none.put("caseType", "GRANT");
        assertFalse(powers.check(c, T, "dop-ct", "S1", CASE_TYPE, dec("1"), NOW, none).allowed());
    }

    // ------------------------------------------------------------------ require: who, and the audit

    @Test
    void aPermitIsAuditedWithTheContractPayload() {
        Map<String, Object> in = extras("band", "SANCTION", "INR");
        in.put(DelegationOfPowersService.IN_CASE_ID, "case-1");
        in.put(DelegationOfPowersService.IN_STATE_VERSION, 7);
        // the system principal holds no posting, so no posting cap applies (none is invented)
        SecurityPorts.PowerDecision d = powers.require(c, SecurityIdentity.system(T, null), "dop", "S1", CASE_TYPE,
                dec("99999.50"), in);
        assertTrue(d.allowed());
        List<String> events = audit("case-1", SecurityEventType.AUTHORITY_DECISION.name());
        assertEquals(1, events.size(), events.toString());
        String payload = events.get(0);
        for (String part : List.of(PowerDecision.CONTRACT_VERSION, "\"decision\":\"PERMIT\"", "\"amount\":\"99999.50\"",
                "\"currency\":\"INR\"", "\"action\":\"SANCTION\"", "\"metadata_version\":\"pack:grants@1.0.0\"",
                "\"state_version\":7", "dop#R6@1", "\"limit\":\"100000\"", "\"outcome\":\"SUCCESS\"")) {
            assertTrue(payload.contains(part), part + " in " + payload);
        }
        assertTrue(payload.contains("\"amount\":\"99999.5\""), "the event's own amount, as the audit normalises decimals");
    }

    @Test
    void aRefusalIsAuditedOnTheDenialChainAndThrows() {
        Map<String, Object> in = extras("two", "SANCTION", "INR");
        in.put(DelegationOfPowersService.IN_CASE_ID, "case-2");
        SangamSecurityException.PowerLimitExceeded e = assertThrows(SangamSecurityException.PowerLimitExceeded.class,
                () -> powers.require(c, person("officer"), "dop", "S1", CASE_TYPE, dec("501"), in));
        assertTrue(e.getMessage().contains("exceeds the delegated power 500"), e.getMessage());
        assertTrue(audit("case-2", SecurityEventType.AUTHORITY_DECISION.name()).isEmpty(), "no decision of authority");
        List<String> denials = audit(SecurityAuditService.denialChain(T, "case-2"), SecurityEventType.POWER_LIMIT_EXCEEDED.name());
        assertEquals(1, denials.size(), denials.toString());
        for (String part : List.of(PowerDecision.CONTRACT_VERSION, "\"decision\":\"DENY\"", "\"amount\":\"501\"",
                "\"outcome\":\"DENIED\"", "\"actor\":\"officer\"")) {
            assertTrue(denials.get(0).contains(part), part + " in " + denials.get(0));
        }
    }

    @Test
    void aPersonWhoDoesNotHoldTheSeatIsRefusedBeforeAnyEvaluation() {
        assertThrows(SangamSecurityException.AccessDenied.class, () -> powers.require(c, person("stranger"), "dop", "S1",
                CASE_TYPE, dec("1"), extras("unlimited", "SANCTION", "INR")));
        assertThrows(IllegalArgumentException.class, () -> {
            Map<String, Object> in = extras("unlimited", "SANCTION", "INR");
            in.put(DelegationOfPowersService.IN_STATE_VERSION, "latest");
            powers.check(c, T, "dop", "S1", CASE_TYPE, dec("1"), NOW, in);
        }, "a state version that is not a number is refused, not defaulted");
    }

    // ------------------------------------------------------------------ drafts are tested by the same evaluator

    @Test
    void aDraftIsTestedAndPreviewedThroughTheSameEvaluator() {
        SecurityIdentity author = person("author-2", "tenant-admin");
        List<PowerTestCase> good = List.of(
                new PowerTestCase("at the lowest ceiling", "S1", CASE_TYPE, dec("500"), extras("two", "SANCTION", "INR"), true),
                new PowerTestCase("above the lowest ceiling", "S1", CASE_TYPE, dec("501"), extras("two", "SANCTION", "INR"), false),
                new PowerTestCase("no amount", "S1", CASE_TYPE, null, extras("unlimited", "SANCTION", "INR"), false));
        DelegationOfPowersService.PowerTable draft = powers.draft(c, author, "dop", "powers", DOP,
                Instant.parse("2031-01-01T00:00:00Z"), "GO-12", "ceilings revised", good);
        assertEquals(2, draft.version());
        assertEquals("DRAFT", draft.status());
        // the second evaluator took the highest ceiling of the two rows (10000): this expectation passed there
        List<PowerTestCase> permissive = List.of(new PowerTestCase("the highest row wins", "S1", CASE_TYPE, dec("501"),
                extras("two", "SANCTION", "INR"), true));
        SangamSecurityException.PolicyRejected e = assertThrows(SangamSecurityException.PolicyRejected.class,
                () -> powers.draft(c, author, "dop", "powers", DOP, Instant.parse("2031-01-01T00:00:00Z"), "GO-12", "x",
                        permissive));
        assertTrue(e.getMessage().contains("expected allowed but was denied"), e.getMessage());
        assertEquals(Map.of("at the lowest ceiling", true, "above the lowest ceiling", false, "no amount", false),
                powers.impact(c, T, "dop", 2, good));
        // a draft changes nothing in force
        assertEquals(1, sanction("two", "500").tableVersion());
    }
}
