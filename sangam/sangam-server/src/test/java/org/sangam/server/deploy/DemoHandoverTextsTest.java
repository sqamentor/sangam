// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The texts and settings a deployer of the hand-over reads, kept true by a test.
 * <p>
 * Register E-20: the go-live runbook had no source in the repository (only a copy in the hand-over folder), and it
 * still said four things the code no longer does: serving with {@code validate} blocked by a pgvector changeset
 * (fixed), the funding route of GIA-0001 open (it is the ledger API: ALLOCATE, then DISTRIBUTE through every stage),
 * a bill payment that ends HELD (tenant GOI.FIN is a sandbox tenant: the simulator pays and matches the statement),
 * and persona tokens from a temporary password-grant client (realm oneifms has none; the tokens come from the
 * authorization-code flow of {@code sangam-ui}). The fragment's header repeated two of them.
 */
class DemoHandoverTextsTest {

    private static final Path DEPLOY = Path.of("..", "deploy").toAbsolutePath().normalize();
    private static final Path RUNBOOK = DEPLOY.resolve("demo").resolve("DEMO-GO-LIVE-RUNBOOK.md");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void theRunbookIsInTheRepositoryAndSaysWhatTheCodeDoes() throws IOException {
        assertTrue(Files.exists(RUNBOOK), "the runbook has a source in the repository: " + RUNBOOK);
        String text = read(RUNBOOK);
        List<String> stale = new ArrayList<>();
        for (String s : List.of(
                // step 4(d): validate is not blocked (changeset 0044-08 fixed, register L-4)
                "blocked by the optional pgvector changeset", "serving with `startup` is the documented alternative",
                // P4: the funding route is answered (OWNER-QUESTIONS Q3)
                "OPEN: the sandbox ledger", "treat GIA-0001 ending at `bl_hold` as the expected outcome",
                // Money note and step 6: GOI.FIN is a sandbox tenant, the payment is simulated to its end
                "A bill payment ends HELD", "it stops HELD or as an incident",
                // P2: no password-grant client
                "temporary Keycloak client", "sangam-demo-loader", "grant_type=password", "direct access grants on",
                // P1: the money check does not read environment bindings; P3: a rerun continues the smoke cases
                "environment bindings", "it skips the two smoke cases")) {
            if (text.contains(s)) {
                stale.add(s);
            }
        }
        assertEquals(List.of(), stale, "stale passages in " + RUNBOOK.getFileName());
        List<String> missing = new ArrayList<>();
        for (String s : List.of("ALLOCATE", "DISTRIBUTE", "`validate`", "PKCE", "browser-flow helper", "GET /me", "Sandbox")) {
            if (!text.contains(s)) {
                missing.add(s);
            }
        }
        assertEquals(List.of(), missing, "what the runbook must say now");
    }

    @Test
    void theFragmentHeaderAndTheReadmePointAtTheSameFacts() throws IOException {
        String fragment = read(DEPLOY.resolve("demo").resolve("sangam-compose-fragment.yml"));
        List<String> stale = new ArrayList<>();
        for (String s : List.of("temporary Keycloak client", "it stops HELD", "pfms.payment binding sandbox")) {
            if (fragment.contains(s)) {
                stale.add(s);
            }
        }
        assertEquals(List.of(), stale, "stale comment lines in the fragment");
        String readme = read(DEPLOY.resolve("demo").resolve("README.md"));
        assertTrue(readme.contains("| `DEMO-GO-LIVE-RUNBOOK.md` |"), "the README lists the runbook as a file of this folder");
        assertTrue(!readme.contains("(outside the repo)"), "the runbook is no longer outside the repository");
    }
}
