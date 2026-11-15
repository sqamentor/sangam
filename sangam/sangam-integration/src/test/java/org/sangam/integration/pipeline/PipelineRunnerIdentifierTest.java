// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The table and column names of a jdbc load step are operator configuration that goes into statement text (SQL cannot
 * bind identifiers). {@link PipelineRunner#ident} is the one place they pass, and it is the shared
 * {@code SqlIdentifier} gate: plain names, optionally schema-qualified, nothing else.
 */
class PipelineRunnerIdentifierTest {

    @Test
    void aLoadStepNameIsAPlainOrQualifiedIdentifier() {
        for (String ok : List.of("feed_rows", "FEED_ROWS", "staging.feed_rows", "db.dbo.FeedRows", "run_id", "chunk_no",
                "_c1")) {
            assertEquals(ok, PipelineRunner.ident(ok).sql());
        }
    }

    @Test
    void anythingThatCouldExtendTheStatementIsRefused() {
        for (String bad : List.of("t; DROP TABLE x", "A B", "x; NOTIFY y", "", "t--", "t/**/", "a'b", "a\"b", "a,b",
                "a=1 OR 1=1", "(select 1)", "1t", "t\n", "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> PipelineRunner.ident(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> PipelineRunner.ident(null));
    }

    /** Stricter than the former pattern, which let a dot stand anywhere: an empty part is no identifier. */
    @Test
    void anEmptyPartOfAQualifiedNameIsRefused() {
        for (String bad : List.of("a..b", "a.", ".a", ".", "a.b.c.d.e")) {
            assertThrows(IllegalArgumentException.class, () -> PipelineRunner.ident(bad), bad);
        }
    }
}
