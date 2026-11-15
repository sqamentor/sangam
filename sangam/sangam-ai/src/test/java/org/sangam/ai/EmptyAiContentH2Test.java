// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.ai.agent.AgentDefinition;
import org.sangam.ai.agent.AgentRuntime;
import org.sangam.ai.draft.DraftService;
import org.sangam.ai.llm.SimulatedLlm;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression SL-87 for the two sangam-ai text columns that could be empty. Oracle stores '' as NULL, so both failed
 * with ORA-01400 (and worked on PostgreSQL):
 * <ul>
 *   <li>AI_AGENT_RUN.input_text: a run started without input (a child agent; AgentTools defaults the input to "") is
 *       legitimate. The column is nullable now (changeset 0035-02); an empty input is stored as NULL and read as "".</li>
 *   <li>AI_DRAFT.content: an empty model reply is not a draft. It is refused with {@link DraftService.EmptyDraftException}
 *       (problem DRAFT_EMPTY, 422) and nothing is stored; the column stays NOT NULL.</li>
 * </ul>
 * Runs in H2's PostgreSQL mode: H2's Oracle mode cannot run the audit chain (no ON CONFLICT; its duplicate-key code is
 * 23505, not ORA-00001). The stored-NULL and nothing-stored assertions are the Oracle property; the Oracle IT is the
 * definitive proof.
 */
class EmptyAiContentH2Test {

    @Test
    void emptyAgentInputIsStoredAsNullAndAnEmptyDraftIsRefused() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AiSchema.create(c, Dialect.POSTGRESQL);
        }
        Transactions tx = Transactions.of(ds);
        AuditChain audit = new AuditChain(Dialect.POSTGRESQL);

        AgentRuntime rt = new AgentRuntime(Dialect.POSTGRESQL, tx, audit, new SimulatedLlm().script("done"), n -> Optional.empty(),
                null, Duration.ofMinutes(1));
        AgentDefinition def = new AgentDefinition("a", 1, AgentDefinition.Type.ASSISTANT, "be helpful", List.of(), 5, 0, 1000,
                null, null);
        String runId = rt.start(def, "t", "alice", "", null, null);
        assertEquals("", rt.find(runId).orElseThrow().input(), "a stored NULL input reads back as empty");
        String nullInput = rt.start(def, "t", "alice", null, null, null);
        assertEquals("", rt.find(nullInput).orElseThrow().input(), "a null input is the empty input");
        assertEquals("hi", rt.find(rt.start(def, "t", "alice", "hi", null, null)).orElseThrow().input());
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT input_text FROM AI_AGENT_RUN WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the run row exists");
                assertNull(rs.getString(1), "an empty input is stored as NULL ('' is NULL on Oracle)");
            }
        }

        DraftService empty = new DraftService(Dialect.POSTGRESQL, tx, audit, new SimulatedLlm());   // replies with no content
        assertThrows(DraftService.EmptyDraftException.class,
                () -> empty.generate("t", DraftService.Kind.FEEL_EXPRESSION, "make", null, null, "alice"));
        DraftService fenced = new DraftService(Dialect.POSTGRESQL, tx, audit, new SimulatedLlm().script("```\n```"));
        assertThrows(DraftService.EmptyDraftException.class,
                () -> fenced.generate("t", DraftService.Kind.FEEL_EXPRESSION, "make", null, null, "alice"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM AI_DRAFT")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "a refused draft is not stored");
        }
    }
}
