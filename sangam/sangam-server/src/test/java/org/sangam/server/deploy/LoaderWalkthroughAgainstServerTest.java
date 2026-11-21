// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LOADER-WALKTHROUGH, a reload after a partial load, against a server started from the demo fragment's settings in
 * production mode with the packs of GOI.FIN deployed (seeded and funded by the steps of
 * {@link DemoLoaderSequenceAgainstServerTest}).
 * <p>
 * The load is interrupted for real: the loader process is killed as soon as it has started its second case. The
 * server has no rule that a business key is unique, so the next run must find what the killed run left, continue
 * each case from where it stands and start only what is missing; a third run must change nothing. The cases are the
 * three of the data set that reach their target on this code without a connector nobody answers (GPF-0002,
 * ADV-0002 and BA-0002, each waiting at sn_sanction); the other twelve are judged by
 * {@link DemoLoaderSequenceAgainstServerTest}.
 * <p>
 * Register E-27: {@code demo_preflight.py money} against the same server (GOI.FIN is on the fragment's sandbox list
 * and has no environment binding).
 */
class LoaderWalkthroughAgainstServerTest {

    private static final String TENANT = "GOI.FIN";
    private static final Path PRESENTATION = Path.of("..", "deploy", "presentation").toAbsolutePath().normalize();
    private static final Path DATA = PRESENTATION.resolve("data").resolve("ifms-jaipur-fy2026-27.json");
    private static final List<String> CASES = List.of("GPF-0002", "ADV-0002", "BA-0002");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    @Test
    void aKilledLoadIsResumedWithoutASecondInstanceAndTheMoneyCheckPasses() throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        List<String> settings = new ArrayList<>(DemoLoaderSequenceAgainstServerTest.PACKS_AT_START);
        // the fragment does not name the log archive (the image does); keep this test's segments out of the module directory
        settings.add("--sangam.compliance.log-archive.root=" + dir.resolve("compliance-logs"));
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap, settings, "h2", "oidc")) {
            String api = "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
            DemoLoaderSequenceAgainstServerTest.sequence(ctx, "loader-walk: seed and funds", List.of("2c seed", "3a funds"));
            String admin = PresentationSeedAgainstServerTest.token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));

            // register E-27: the money check on the server of the fragment
            PresentationSeedAgainstServerTest.Seed money = PresentationSeedAgainstServerTest.script(
                    PRESENTATION.resolve("demo_preflight.py"), List.of("money"), Map.of("SANGAM_API_BASE", api, "SANGAM_ADMIN_TOKEN", admin));
            System.out.println("LOADER-WALK | money | exit " + money.exit() + " | " + money.out().strip());
            assertEquals(0, money.exit(), "demo_preflight.py money on the sandbox tenant of the fragment:\n" + money.out());

            Path data = threeCases();
            Map<String, String> env = personaEnv(api);

            // run 1: killed as soon as the second case is started
            String killed = runAndKillAtTheSecondStart(data, env);
            Map<String, Integer> afterKill = roots(api, admin, data);
            System.out.println("LOADER-WALK | run 1 killed | instances " + afterKill + " | " + killed.strip().replace("\n", " / "));
            assertEquals(1, afterKill.get(CASES.get(0)), "the first case was started by the killed run: " + afterKill);
            assertEquals(1, afterKill.get(CASES.get(1)), "the second case was started by the killed run: " + afterKill);
            assertEquals(0, afterKill.get(CASES.get(2)), "the third case was not reached: " + afterKill);

            // run 2: continues the two, starts the third
            PresentationSeedAgainstServerTest.Seed second = PresentationSeedAgainstServerTest.script(
                    PRESENTATION.resolve("load_presentation_data.py"), List.of("--data", data.toString()), env);
            Map<String, Integer> afterSecond = roots(api, admin, data);
            System.out.println("LOADER-WALK | run 2 | exit " + second.exit() + " | instances " + afterSecond + " | "
                    + second.out().strip().replace("\n", " / "));
            assertEquals(0, second.exit(), "the next run brings every case to its target:\n" + second.out());
            assertEquals(2, count(second.out(), "    exists: continued"), second.out());
            assertEquals(1, count(second.out(), "    started"), second.out());
            assertEquals(3, count(second.out(), "    waits at sn_sanction"), second.out());
            assertEquals(Map.of(CASES.get(0), 1, CASES.get(1), 1, CASES.get(2), 1), afterSecond, "one instance per business key");

            // run 3: nothing to do
            PresentationSeedAgainstServerTest.Seed third = PresentationSeedAgainstServerTest.script(
                    PRESENTATION.resolve("load_presentation_data.py"), List.of("--data", data.toString()), env);
            Map<String, Integer> afterThird = roots(api, admin, data);
            System.out.println("LOADER-WALK | run 3 | exit " + third.exit() + " | instances " + afterThird + " | "
                    + third.out().strip().replace("\n", " / "));
            assertEquals(0, third.exit(), third.out());
            assertEquals(3, count(third.out(), "    exists: continued"), third.out());
            assertEquals(0, count(third.out(), "    started"), third.out());
            assertTrue(!third.out().contains(" done by "), "a third run completes no task:\n" + third.out());
            assertEquals(afterSecond, afterThird, "one instance per business key, as before");
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(java.util.Set.of());
        }
    }

    /** The data set with only {@link #CASES}, in that order. */
    private Path threeCases() throws Exception {
        ObjectNode ds = (ObjectNode) JSON.readTree(DATA.toFile());
        ArrayNode kept = JSON.createArrayNode();
        for (String id : CASES) {
            for (JsonNode c : ds.path("cases")) {
                if (id.equals(c.path("id").asText())) {
                    kept.add(c);
                }
            }
        }
        assertEquals(CASES.size(), kept.size(), "the cases of this test are in the data set");
        ds.set("cases", kept);
        Path file = dir.resolve("three-cases.json");
        Files.writeString(file, JSON.writeValueAsString(ds), StandardCharsets.UTF_8);
        return file;
    }

    /** Runs the loader unbuffered and kills it at its second "started" line; returns what it printed until then. */
    private static String runAndKillAtTheSecondStart(Path data, Map<String, String> env) throws Exception {
        String python = null;
        for (String c : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(c, "-I", "-c", "import sys; assert sys.version_info >= (3, 8)").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    python = c;
                    break;
                }
            } catch (java.io.IOException ignored) {
                // try the next name
            }
        }
        assertTrue(python != null, "a Python 3 interpreter on the PATH");
        ProcessBuilder pb = new ProcessBuilder(python, "-I", "-u", PRESENTATION.resolve("load_presentation_data.py").toString(),
                "--data", data.toString()).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("KEYCLOAK_") || k.startsWith("SANGAM_"));
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().putAll(env);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        int started = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
                if (line.equals("    started") && ++started == 2) {
                    p.destroyForcibly();
                    break;
                }
            }
        }
        assertTrue(p.waitFor(1, TimeUnit.MINUTES), "the killed loader is gone");
        assertEquals(2, started, "the loader was killed at its second start:\n" + out);
        return out.toString();
    }

    /** Per case id: how many instances of its own (not called by another) carry its business key. */
    private static Map<String, Integer> roots(String api, String admin, Path data) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (JsonNode c : JSON.readTree(data.toFile()).path("cases")) {
            JsonNode found = PresentationSeedAgainstServerTest.get(api + "/process-instances?size=100&businessKey="
                    + URLEncoder.encode(c.path("businessKey").asText(), StandardCharsets.UTF_8), admin);
            int n = 0;
            for (JsonNode x : found.isArray() ? found : found.path("items")) {
                if (x.path("superProcessInstanceId").isNull() || x.path("superProcessInstanceId").isMissingNode()) {
                    n++;
                }
            }
            out.put(c.path("id").asText(), n);
        }
        return out;
    }

    private static int count(String text, String line) {
        int n = 0;
        for (String l : text.split("\\R")) {
            if (l.startsWith(line)) {
                n++;
            }
        }
        return n;
    }

    /** One token per persona (its realm roles, as the seed's plan lists them), under the name the loader reads. */
    private static Map<String, String> personaEnv(String api) throws Exception {
        PresentationSeedAgainstServerTest.Seed plan = PresentationSeedAgainstServerTest.script(
                PRESENTATION.resolve("seed_presentation.py"), List.of("--dry-run"), Map.of());
        assertEquals(0, plan.exit(), plan.out());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SANGAM_API_BASE", api);
        env.put("SANGAM_PRESENTATION_SETTLE_SECONDS", "12");
        Matcher m = Pattern.compile("keycloak \\S+: user (\\S+) roles (\\S+) seats").matcher(plan.out());
        while (m.find()) {
            env.put("SANGAM_PRESENTATION_TOKEN_" + m.group(1).toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'),
                    PresentationSeedAgainstServerTest.token(m.group(1), List.of(m.group(2).split(","))));
        }
        assertTrue(env.size() > 9, "persona tokens: " + env.keySet());
        return env;
    }
}
