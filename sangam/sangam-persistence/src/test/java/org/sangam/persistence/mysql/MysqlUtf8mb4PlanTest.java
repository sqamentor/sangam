// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.persistence.mysql.MysqlUtf8mb4Patch.ForeignKey;
import org.sangam.persistence.mysql.MysqlUtf8mb4Patch.Plan;

/**
 * SL-94: the pure planning half of P-2. The real-database half (MySQL error 3780 on the Operaton 2.2.0 schema, FK
 * re-creation and validation) is {@code MysqlUtf8mb4PatchIT} on the MySQL lane of the acceptance matrix.
 */
class MysqlUtf8mb4PlanTest {

    private static final String S = "sangam";

    private static ForeignKey fk(String name, String child, String parent) {
        return new ForeignKey(S, name, child, List.of("P_ID_"), S, parent, List.of("ID_"), "NO ACTION", "NO ACTION");
    }

    @Test
    void everyStringForeignKeyBetweenConvertedTablesIsDroppedAndRecreated() {
        List<ForeignKey> fks = List.of(fk("ACT_FK_BYTEARR_DEPL", "ACT_GE_BYTEARRAY", "ACT_RE_DEPLOYMENT"),
                fk("ACT_FK_EXE_PARENT", "ACT_RU_EXECUTION", "ACT_RU_EXECUTION"));
        Plan p = MysqlUtf8mb4Patch.plan(List.of("ACT_RU_EXECUTION", "ACT_GE_BYTEARRAY", "ACT_RE_DEPLOYMENT"), S,
                Map.of(), fks, Set.of());
        assertEquals(List.of("ACT_GE_BYTEARRAY", "ACT_RE_DEPLOYMENT", "ACT_RU_EXECUTION"), p.convert());
        assertEquals(fks, p.recreate());
        assertTrue(p.skipped().isEmpty());
    }

    @Test
    void aTooWideIndexSkipsItsWholeForeignKeyComponentAndKeepsItsForeignKeys() {
        List<ForeignKey> fks = List.of(fk("FK_A_B", "ACT_A", "ACT_B"), fk("FK_C_B", "ACT_C", "ACT_B"),
                fk("FK_E_D", "ACT_E", "ACT_D"));
        Plan p = MysqlUtf8mb4Patch.plan(List.of("ACT_A", "ACT_B", "ACT_C", "ACT_D", "ACT_E"), S,
                Map.of("ACT_C", MysqlUtf8mb4Patch.MAX_INDEX_BYTES + 1), fks, Set.of());
        assertEquals(List.of("ACT_D", "ACT_E"), p.convert());
        assertEquals(Set.of("ACT_A", "ACT_B", "ACT_C"), p.skipped().keySet());
        assertTrue(p.skipped().get("ACT_A").startsWith("ACT_C: widest index"), p.skipped().toString());
        assertEquals(List.of(fks.get(2)), p.recreate());
    }

    @Test
    void anOutsideEndThatIsNotUtf8mb4BinSkipsTheComponent() {
        ForeignKey out = new ForeignKey(S, "FK_X", "SGM_X", List.of("DEPL_"), S, "ACT_B", List.of("ID_"),
                "NO ACTION", "CASCADE");
        Plan p = MysqlUtf8mb4Patch.plan(List.of("ACT_A", "ACT_B"), S, Map.of(),
                List.of(fk("FK_A_B", "ACT_A", "ACT_B"), out), Set.of("FK_X"));
        assertTrue(p.convert().isEmpty());
        assertEquals(Set.of("ACT_A", "ACT_B"), p.skipped().keySet());
        assertTrue(p.recreate().isEmpty());

        Plan ok = MysqlUtf8mb4Patch.plan(List.of("ACT_A", "ACT_B"), S, Map.of(),
                List.of(fk("FK_A_B", "ACT_A", "ACT_B"), out), Set.of());
        assertEquals(List.of("ACT_A", "ACT_B"), ok.convert());
        assertEquals(2, ok.recreate().size());
    }

    @Test
    void journalRowRoundTripsTheExactDefinition() {
        ForeignKey f = new ForeignKey(S, "ACT_FK_X", "ACT_C", List.of("A_", "B_"), "other", "ACT_P",
                List.of("ID_", "REV_"), "CASCADE", "SET NULL");
        assertEquals(f, ForeignKey.fromJournal(f.journalName(), f.journalValue()));
        assertEquals("alter table `sangam`.`ACT_C` add constraint `ACT_FK_X` foreign key (`A_`, `B_`) references "
                + "`other`.`ACT_P` (`ID_`, `REV_`) on delete SET NULL on update CASCADE", f.addSql());
    }
}
