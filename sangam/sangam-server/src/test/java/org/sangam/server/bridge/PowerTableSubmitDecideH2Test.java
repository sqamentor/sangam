// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.jdbc.JdbcPowerTableAdapter;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PowerTablePort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.web.CommandCatalog;
import org.sangam.rest.web.PowerTableController;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Owner decision 322: the power-table lifecycle is two command types, {@code power-table.submit} (the submit route,
 * {@code admin.write}) and {@code power-table.decide} (approve and reject, {@code power-table.approve}); the combined
 * {@code power-table.transition} is gone. With the real controller, the JDBC adapter, the replay rules of the contract
 * and the permission bridge:
 * <ul>
 *   <li>a submitter without approval rights submits, and a retried submit (the same Idempotency-Key) is replayed to
 *       them: the replay asks for {@code admin.write} only;</li>
 *   <li>that submitter can neither decide (403) nor get a stored decision replayed;</li>
 *   <li>a decider decides, and gets a decision replayed, without {@code admin.write};</li>
 *   <li>maker-checker is unchanged: a person holding both permissions cannot approve the table they authored (422
 *       SEGREGATION_OF_DUTIES), nor can another person on the author's seat.</li>
 * </ul>
 */
class PowerTableSubmitDecideH2Test {

    private static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="powers" name="powers"
                         namespace="http://sangam.example/powers">
              <decision id="sanctionPower" name="Sanction power">
                <decisionTable id="t" hitPolicy="FIRST">
                  <input id="i1" label="amount"><inputExpression id="e1" typeRef="long"><text>amount</text></inputExpression></input>
                  <output id="o1" name="authority" typeRef="string"/>
                  <rule id="r1"><inputEntry id="ie1"><text>&lt;= 100000</text></inputEntry>
                    <outputEntry id="oe1"><text>"HEAD_OF_OFFICE"</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="ie2"><text></text></inputEntry>
                    <outputEntry id="oe2"><text>"SECRETARY"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    private static final RequestContext SUBMITTER = ctx("user:maker", "seat-maker", "submitters");
    private static final RequestContext DECIDER = ctx("user:checker", "seat-checker", "deciders");
    private static final RequestContext BOTH = ctx("user:both", "seat-both", "submitters", "deciders");

    private static RequestContext ctx(String person, String seat, String... groups) {
        return new RequestContext("T1", person, Set.of(groups), seat, null, null, null, null, "OIDC");
    }

    private SecurityPermissionBridge permissions;
    private PowerTableController controller;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("..", "sangam-rest", "api", "sangam-api.yaml"))) {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(64 * 1024 * 1024);
            return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(in);
        }
    }

    @BeforeEach
    void wire() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d322" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        SangamSchema.apply(ds);
        TxSupport tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        RestProperties props = new RestProperties();
        // persona groups as the security module grants them: a submitter never holds power-table.approve
        permissions = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter(Map.of(
                "submitters", Set.of("admin.read", "admin.write"),
                "deciders", Set.of("admin.read", "power-table.approve"))), new ReplayRules(contract()), null);
        JdbcAuditAdapter audit = new JdbcAuditAdapter(tx, null, null, props);
        // approvals in this test take effect in the future, so nothing is deployed
        controller = new PowerTableController(new JdbcPowerTableAdapter(tx, null, permissions, audit, props), permissions);
    }

    private String draftBy(RequestContext author) {
        return controller.create(author, new PowerTablePort.PowerTable(null, "sanction-" + UUID.randomUUID().toString()
                .substring(0, 8), "Sanction powers", 0, null, null, null, null, DMN, List.of(), null, 0)).getBody().id();
    }

    private static PowerTablePort.TransitionRequest later(String reason) {
        return new PowerTablePort.TransitionRequest(reason, Instant.now().plus(30, ChronoUnit.DAYS));
    }

    @Test
    void theContractHasTheTwoTypesAndNotTheOldOne() {
        CommandCatalog catalog = new CommandCatalog();
        assertEquals("power-table.submit", catalog.match("POST", "/api/v1/admin/power-tables/x@1/submit").orElseThrow()
                .command().commandType());
        assertEquals("power-table.decide", catalog.match("POST", "/api/v1/admin/power-tables/x@1/approve").orElseThrow()
                .command().commandType());
        assertEquals("power-table.decide", catalog.match("POST", "/api/v1/admin/power-tables/x@1/reject").orElseThrow()
                .command().commandType());
        assertTrue(catalog.commands().stream().noneMatch(c -> "power-table.transition".equals(c.commandType())));
        assertEquals(ReplayRules.Holds.of("admin.write"), permissions.replayRule("power-table.submit"));
        assertEquals(ReplayRules.Holds.of("power-table.approve"), permissions.replayRule("power-table.decide"));
        assertNull(permissions.replayRule("power-table.transition"), "no alias: the old combined type is refused on replay");
        assertFalse(permissions.mayReplay(BOTH, "power-table.transition", "x@1"));
    }

    @Test
    void aSubmitterWithoutApprovalRightsSubmitsAndReplaysASubmitButCannotDecide() {
        String id = draftBy(SUBMITTER);
        assertEquals("IN_REVIEW", controller.submit(SUBMITTER, id, null).getBody().state());
        // the retried submit: IdempotencyFilter asks the permission port before it returns the stored answer
        assertTrue(permissions.mayReplay(SUBMITTER, "power-table.submit", id),
                "the submit replays to a submitter who holds admin.write and not power-table.approve");

        ApiProblem approve = assertThrows(ApiProblem.class, () -> controller.approve(SUBMITTER, id, later("ok")));
        assertEquals(403, approve.status());
        assertEquals("ACTION_NOT_PERMITTED", approve.code());
        ApiProblem reject = assertThrows(ApiProblem.class,
                () -> controller.reject(SUBMITTER, id, new PowerTablePort.TransitionRequest("no", null)));
        assertEquals(403, reject.status());
        assertFalse(permissions.mayReplay(SUBMITTER, "power-table.decide", id),
                "a stored decision is not replayed to someone who may not decide");
        assertEquals("IN_REVIEW", controller.get(SUBMITTER, id).state(), "nothing was decided");
    }

    @Test
    void aDeciderDecidesAndReplaysADecisionWithoutBeingASubmitter() {
        String id = draftBy(SUBMITTER);
        controller.submit(SUBMITTER, id, null);

        PowerTablePort.PowerTable approved = controller.approve(DECIDER, id, later("checked")).getBody();
        assertEquals("APPROVED", approved.state());
        assertEquals("user:checker", approved.approver());
        assertTrue(permissions.mayReplay(DECIDER, "power-table.decide", id));
        assertFalse(permissions.mayReplay(DECIDER, "power-table.submit", id), "a decider is not a submitter");
        assertEquals(403, assertThrows(ApiProblem.class, () -> controller.submit(DECIDER, draftBy(SUBMITTER), null)).status());

        String second = draftBy(SUBMITTER);
        controller.submit(SUBMITTER, second, null);
        assertEquals("REJECTED", controller.reject(DECIDER, second,
                new PowerTablePort.TransitionRequest("limits too high", null)).getBody().state());
    }

    @Test
    void makerCheckerStillRefusesSelfApproval() {
        String id = draftBy(BOTH);
        controller.submit(BOTH, id, null);
        ApiProblem self = assertThrows(ApiProblem.class, () -> controller.approve(BOTH, id, later("my own table")));
        assertEquals(422, self.status());
        assertEquals("SEGREGATION_OF_DUTIES", self.code());

        // another person on the author's seat is refused as well
        RequestContext sameSeat = ctx("user:deputy", "seat-both", "deciders");
        assertEquals("SEGREGATION_OF_DUTIES", assertThrows(ApiProblem.class,
                () -> controller.approve(sameSeat, id, later("same seat"))).code());
        assertEquals("IN_REVIEW", controller.get(BOTH, id).state());

        assertEquals("APPROVED", controller.approve(DECIDER, id, later("independent check")).getBody().state());
    }
}
