// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Upgrade guard for PATCHES.md I-4 (case migration A1, {@link CaseMigrationService}). The migration relies on
 * Operaton's own update statements: a case execution and a task persist a changed case definition id, a case
 * execution never persists a changed plan item id, a sentry part only its satisfied flag. If an Operaton upgrade
 * changes any of these statements, this test fails and A1 must be re-assessed before the upgrade is merged.
 * <p>
 * The pinned SHA-256 values were checked identical in operaton-engine 2.2.0 and 2.2.1 (R10-cmmn, 2026-11-18).
 */
class CaseMigrationEngineContractTest {

    private static String statement(String mappingFile, String updateId) throws Exception {
        String path = "org/operaton/bpm/engine/impl/mapping/entity/" + mappingFile + ".xml";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Operaton mapping " + path + " is on the class path");
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<update id=\"" + updateId + "\"[\\s\\S]*?</update>").matcher(xml);
            assertTrue(m.find(), updateId + " exists in " + path);
            return m.group();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "CaseExecution, updateCaseExecution, c69b7e967df4270343d7583982a241f42511ca21962520d9ace5a991f8b9820d",
            "CaseSentryPart, updateCaseSentryPart, ecf966bba460f7d446d9bc64f1f0448d79154f1399b4b454cb798d072d77e9ab",
            "Task, updateTask, 23e67953281825e45a128e1b2a0e47c8d9f512bd440529270905d14c44fe5c9c",
    })
    void updateStatementIsUnchanged(String file, String id, String sha256) throws Exception {
        String sql = statement(file, id);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
        assertEquals(sha256, actual, id + " changed in this Operaton version; re-assess CaseMigrationService (PATCHES.md I-4):\n" + sql);
    }

    @ParameterizedTest
    @CsvSource({"CaseExecution, updateCaseExecution", "Task, updateTask"})
    void caseDefinitionIdIsPersisted(String file, String id) throws Exception {
        assertTrue(statement(file, id).contains("CASE_DEF_ID_ = #{caseDefinitionId"), id + " persists CASE_DEF_ID_");
    }

    @ParameterizedTest
    @CsvSource({"CaseExecution, updateCaseExecution, ACT_ID_", "CaseSentryPart, updateCaseSentryPart, SENTRY_ID_"})
    void identifiersAreNotPersisted_soA1RejectsRenames(String file, String id, String column) throws Exception {
        assertTrue(!statement(file, id).contains(column), id + " does not persist " + column);
    }
}
