// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.sangam.ai.rag.NativeVectorSql;
import org.sangam.ai.rag.VectorIndex;
import org.springframework.mock.env.MockEnvironment;

/** Decisions 170 and 207: sangam.ai.kb.vector-index picks portable (default), pgvector or oracle; nothing else starts. */
class AiVectorIndexSelectionTest {

    private static VectorIndex select(String value) {
        MockEnvironment env = new MockEnvironment();
        if (value != null) {
            env.setProperty("sangam.ai.kb.vector-index", value);
        }
        return AiWiring.vectorIndex(env);
    }

    /** The native SQL family's name, read from the package-private NativeVectorIndex. */
    private static String nativeName(VectorIndex index) throws Exception {
        Field f = index.getClass().getDeclaredField("sql");
        f.setAccessible(true);
        return ((NativeVectorSql) f.get(index)).name();
    }

    @Test
    void thePortableIndexIsTheDefault() {
        assertEquals("PortableVectorIndex", select(null).getClass().getSimpleName());
        assertEquals("PortableVectorIndex", select("portable").getClass().getSimpleName());
    }

    @Test
    void pgvectorAndOracleAreOptIn() throws Exception {
        VectorIndex pg = select("pgvector");
        assertEquals("NativeVectorIndex", pg.getClass().getSimpleName());
        assertEquals("pgvector", nativeName(pg));
        VectorIndex oracle = select("oracle");
        assertEquals("NativeVectorIndex", oracle.getClass().getSimpleName());
        assertEquals("oracle", nativeName(oracle));
    }

    @Test
    void anUnknownIndexStopsStartUp() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> select("faiss"));
        assertTrue(e.getMessage().startsWith("sangam.ai.kb.vector-index"), e.getMessage());
    }
}
