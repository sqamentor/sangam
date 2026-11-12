// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.licence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SG-37 guard: a consumer programme's own identifiers (its programme name and its decision ids, for example the
 * licence-policy decision behind SG-27, its requirement documents {@code IFMS R-<n>} and its open questions
 * {@code OQ-<n>}, SL-106) do not appear in Sangam product files: production sources and resources of
 * every reactor module, every pom, the licence policy and the SBOM scripts. Product text uses the generic wording
 * "SG-27 consumer licence-policy exception". {@code gov.ifms} packs stay: "IFMS" names a class of government system,
 * as data (ADR-D-20). Consumer conformance tests and fixtures are test material and live under
 * {@code sangam-server/src/test/.../acceptance/consumer/} (SG-37), outside this scan. The one exemption is the owner's
 * decision record {@code deploy/licence/SG-27-SD-301-exception.md} and references to its file name: a decision
 * record keeps its original name and words (coordinator decision Q-B4-6, 2026-11-12), so it is never renamed. Run from sangam-core, so the reactor root is the parent folder.
 */
class ConsumerIdsOutOfProductFilesTest {

    private static final Pattern CONSUMER_ID =
            Pattern.compile("(?i)oneifms|SD-301(?!-exception\\.md)|secret://ifms/|IFMS R-\\d+|(?-i:\\bOQ-\\d+)");
    /** The owner's decision record: kept under its original name and wording (Q-B4-6), so it is exempt. */
    private static final String EVIDENCE_RECORD = "deploy/licence/SG-27-SD-301-exception.md";
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git");

    @Test
    void productFilesCarryNoConsumerProgrammeIds() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> scanned = new ArrayList<>();
        List<String> hits = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return dir.equals(root) || !(name.startsWith("target") || SKIP_DIRS.contains(name))
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && isProductFile(root, file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        try (Stream<Path> s = files.stream()) {
            s.forEach(p -> {
                scanned.add(p);
                String text;
                try {
                    text = Files.readString(p);
                } catch (MalformedInputException e) {
                    return; // binary resource
                } catch (IOException e) {
                    throw new IllegalStateException(p.toString(), e);
                }
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (CONSUMER_ID.matcher(lines[i]).find()) {
                        hits.add(root.relativize(p) + ":" + (i + 1) + ": " + lines[i].strip());
                    }
                }
            });
        }
        assertTrue(scanned.size() > 500, "scanned the product files (" + scanned.size() + ")");
        assertTrue(scanned.stream().anyMatch(p -> p.endsWith(Path.of("deploy", "licence", "licence-policy.json"))),
                "licence policy is in scope");
        assertTrue(hits.isEmpty(), "consumer programme ids in product files (SG-37: use generic wording): " + hits);
    }

    /** Production sources/resources of reactor modules, poms, and the deploy licence/airgap tooling. */
    static boolean isProductFile(Path root, Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        for (String seg : rel.split("/")) {
            if (seg.startsWith("target") || SKIP_DIRS.contains(seg)) {
                return false;
            }
        }
        if (rel.contains("/src/test/") || rel.equals(EVIDENCE_RECORD)) {
            return false;
        }
        return rel.contains("/src/main/") || rel.endsWith("pom.xml") || rel.startsWith("deploy/licence/")
                || rel.startsWith("deploy/airgap/");
    }

    @Test
    void scopeRuleKeepsTestsAndBuildOutputOut() {
        Path root = Path.of("/r");
        assertTrue(isProductFile(root, Path.of("/r/sangam-x/src/main/java/A.java")));
        assertTrue(isProductFile(root, Path.of("/r/sangam-bom/pom.xml")));
        assertTrue(isProductFile(root, Path.of("/r/deploy/licence/licence-policy.json")));
        assertTrue(!isProductFile(root, Path.of("/r/sangam-x/src/test/java/ATest.java")));
        assertTrue(!isProductFile(root, Path.of("/r/sangam-x/target/classes/A.class")));
        assertTrue(!isProductFile(root, Path.of("/r/sangam-x/target-ws1/x/pom.xml")));
        assertTrue(!isProductFile(root, Path.of("/r/sangam-ui/node_modules/x/src/main/a.js")));
        assertTrue(!isProductFile(root, Path.of("/r/TESTING-GAP-REGISTER.md")));
        assertTrue(!isProductFile(root, Path.of("/r/deploy/licence/SG-27-SD-301-exception.md")));
    }

    @Test
    void patternFlagsConsumerIdsButNotTheGovIfmsPackClass() {
        assertTrue(CONSUMER_ID.matcher("OneIFMS SD-301 bans").find());
        assertTrue(CONSUMER_ID.matcher("secret://ifms/client-secret").find());
        assertTrue(CONSUMER_ID.matcher("(SG-27, SD-301)").find());
        assertTrue(CONSUMER_ID.matcher("(IFMS R-99 v0.2 s6.1, OQ-219)").find());
        assertTrue(CONSUMER_ID.matcher("not encodable until OQ-222").find());
        assertTrue(CONSUMER_ID.matcher("ifms r-12").find());
        assertTrue(!CONSUMER_ID.matcher("IFS-040, IFC-046, GSTR-8, RPR-13").find());
        assertTrue(!CONSUMER_ID.matcher("an open consumer question (VERIFIED absence)").find());
        assertTrue(!CONSUMER_ID.matcher("HQ-1 and oq-1 in lower case").find());
        assertTrue(!CONSUMER_ID.matcher("deploy/licence/SG-27-SD-301-exception.md").find());
        assertTrue(!CONSUMER_ID.matcher("packs/gov.ifms/bill-payment.yaml").find());
        assertTrue(!CONSUMER_ID.matcher("SG-27 consumer licence-policy exception").find());
        assertTrue(!CONSUMER_ID.matcher("Configuration of one IFMS site.").find());
    }
}
