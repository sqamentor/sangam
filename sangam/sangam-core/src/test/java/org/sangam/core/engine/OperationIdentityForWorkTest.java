// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * T35: one derivation of the operation id of engine work ({@link OperationIdentity#forWork}). The engine adapter's
 * lease, the REST worker API, delete tombstones, previews and the ops console must all hand out the same id, so no
 * engine-work path may derive it any other way (the E3-t35 defect: the REST fetch derived it from engine ids).
 */
class OperationIdentityForWorkTest {

    @Test
    void forWorkIsTheBusinessKeyIdentityWithTheRootAsFallback() {
        UUID withKey = OperationIdentity.forWork("t1", "bk-1", "root-1", "payment", 0, null, null);
        assertEquals(OperationIdentity.of("t1", "bk-1", "payment", OperationIdentity.scope(0, null, null)), withKey);
        assertEquals(withKey, OperationIdentity.forWork("t1", "bk-1", "root-2", "payment", 0, null, null),
                "the root id does not matter when there is a business key (survives restore and re-creation)");
        assertEquals(OperationIdentity.of("t1", "root-1", "payment", OperationIdentity.scope(0, null, null)),
                OperationIdentity.forWork("t1", null, "root-1", "payment", 0, null, null));
        assertEquals(OperationIdentity.forWork("t1", "", "root-1", "payment", 0, null, null),
                OperationIdentity.forWork("t1", null, "root-1", "payment", 0, null, null));
        assertNotEquals(withKey, OperationIdentity.forWork("t1", "bk-1", "root-1", "payment", 1, null, null));
        assertNotEquals(withKey, OperationIdentity.forWork("t1", "bk-1", "root-1", "payment", 0, 2, null));
        assertNotEquals(withKey, OperationIdentity.forWork("t1", "bk-1", "root-1", "payment", 0, null, "k"));
    }

    /** Source guard: the engine-work paths (sangam-engine, sangam-rest) derive the id only through forWork. */
    @Test
    void noEngineWorkPathHasASecondDerivation() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<String> offenders = new ArrayList<>();
        for (String module : List.of("sangam-engine", "sangam-rest")) {
            Path src = root.resolve(module).resolve("src/main/java");
            assertTrue(Files.isDirectory(src), "module sources not found: " + src);
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String s = Files.readString(f, StandardCharsets.UTF_8);
                    if (s.contains("OperationLedger.operationId(") || s.contains("OperationIdentity.of(")
                            || s.contains("OperatonWorkAdapter.operationId(")) {
                        offenders.add(root.relativize(f).toString());
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "engine-work operation ids must come from OperationIdentity.forWork");
    }
}
