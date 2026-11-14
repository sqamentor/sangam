// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Decision 220 wiring: settings, the required history directory, and the two consoles excluding each other. Written, not run. */
class ScriptBackupWiringTest {

    @TempDir
    Path dir;

    MockEnvironment env() {
        String script = dir.resolve("pg-full.sh").toAbsolutePath().toString();
        return new MockEnvironment()
                .withProperty("sangam.backup.console.script.enabled", "true")
                .withProperty("sangam.backup.console.script.history-dir", dir.resolve("history").toAbsolutePath().toString())
                .withProperty("sangam.backup.console.script.env-passthrough", "PGPASSWORD, PGUSER")
                .withProperty("sangam.backup.console.script.targets[0].id", "pg-full")
                .withProperty("sangam.backup.console.script.targets[0].command[0]", script);
    }

    @Test
    void theTargetsComeFromTheSettings() throws Exception {
        assertEquals("pg-full", ScriptBackupWiring.targets(env()).targets().get(0).id());
    }

    @Test
    void bothConsolesAtOnceStopTheStartUp() {
        MockEnvironment e = env().withProperty("sangam.backup.console.kubernetes.enabled", "true");
        assertThrows(IllegalStateException.class, () -> ScriptBackupWiring.targets(e));
    }

    @Test
    void theHistoryDirectoryIsRequired() {
        MockEnvironment e = env().withProperty("sangam.backup.console.script.history-dir", " ");
        assertThrows(IllegalStateException.class, () -> ScriptBackupWiring.targets(e));
    }
}
