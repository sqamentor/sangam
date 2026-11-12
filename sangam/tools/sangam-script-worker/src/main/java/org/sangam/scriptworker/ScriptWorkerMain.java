// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.sangam.sdk.SangamClient;
import org.sangam.sdk.auth.ClientCredentialsTokenProvider;
import org.sangam.sdk.auth.TokenProvider;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TopicSubscription;

/**
 * Entry point of the script-worker container. Configuration (environment only; the token is never logged):
 * <ul>
 *   <li>{@code SANGAM_URL}, and {@code SANGAM_TOKEN} or {@code SANGAM_OIDC_TOKEN_URL} + {@code SANGAM_CLIENT_ID} +
 *       {@code SANGAM_CLIENT_SECRET} (+ {@code SANGAM_SCOPE}), as every SDK worker;</li>
 *   <li>{@code SANGAM_WORKER_ID}; {@code SANGAM_SCRIPT_LANGUAGES} (default {@code javascript,python});
 *       {@code SANGAM_SCRIPT_MAX_CONCURRENCY} (default: the number of CPUs); {@code SANGAM_SCRIPT_RETRIES} (default 3,
 *       used only for sandbox failures);</li>
 *   <li>the limits {@code SANGAM_SCRIPT_*} of {@link ScriptLimits#fromEnv};</li>
 *   <li>{@code SANGAM_SCRIPT_NSJAIL}, {@code SANGAM_SCRIPT_RUNTIME}, {@code SANGAM_SCRIPT_SECCOMP},
 *       {@code SANGAM_SCRIPT_NODE}, {@code SANGAM_SCRIPT_PYTHON} (the image's paths by default).</li>
 * </ul>
 * The worker refuses to start when nsjail, the harness or the seccomp policy is missing: it never runs a script
 * without the sandbox.
 */
public final class ScriptWorkerMain {

    private ScriptWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        ScriptLimits limits = ScriptLimits.fromEnv(env);
        NsjailSandbox sandbox = new NsjailSandbox(
                Path.of(env(env, "SANGAM_SCRIPT_NSJAIL", NsjailSandbox.DEFAULT_NSJAIL.toString())),
                Path.of(env(env, "SANGAM_SCRIPT_RUNTIME", NsjailSandbox.DEFAULT_RUNTIME.toString())),
                Path.of(env(env, "SANGAM_SCRIPT_SECCOMP", NsjailSandbox.DEFAULT_POLICY.toString())),
                env(env, "SANGAM_SCRIPT_NODE", "/usr/bin/node"),
                env(env, "SANGAM_SCRIPT_PYTHON", "/usr/bin/python3"));
        List<Language> languages = languages(env(env, "SANGAM_SCRIPT_LANGUAGES", "javascript,python"));
        requireSandbox(env, languages);

        SangamClient client = SangamClient.builder()
                .baseUrl(env(env, "SANGAM_URL", "http://localhost:8080"))
                .tokenProvider(tokenProvider(env))
                .build();
        ScriptTaskHandler handler = new ScriptTaskHandler(new ScriptRunner(sandbox, limits), limits);
        SangamWorker.Builder b = SangamWorker.builder(client)
                .workerId(env(env, "SANGAM_WORKER_ID", null))
                .maxConcurrency(Integer.parseInt(env(env, "SANGAM_SCRIPT_MAX_CONCURRENCY",
                        Integer.toString(Runtime.getRuntime().availableProcessors()))));
        for (Language l : languages) {
            b.subscribe(TopicSubscription.topic(ScriptTopics.topic(l), handler)
                    .lockDuration(lockDuration(limits))
                    .retries(Integer.parseInt(env(env, "SANGAM_SCRIPT_RETRIES", "3")))
                    .build());
        }
        b.build().run();
    }

    /** The lease outlives the wall limit plus the kill grace, so a running script never loses its lease. */
    static Duration lockDuration(ScriptLimits limits) {
        Duration d = limits.wallTime().plusMillis(ScriptRunner.GRACE_MS).multipliedBy(2);
        return d.compareTo(Duration.ofSeconds(30)) < 0 ? Duration.ofSeconds(30) : d;
    }

    static List<Language> languages(String csv) {
        List<Language> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String name = s.strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            Language l = ScriptTopics.language(name)
                    .orElseThrow(() -> new IllegalArgumentException("SANGAM_SCRIPT_LANGUAGES: unknown language " + name));
            if (!out.contains(l)) {
                out.add(l);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("SANGAM_SCRIPT_LANGUAGES names no language");
        }
        return List.copyOf(out);
    }

    private static void requireSandbox(Map<String, String> env, List<Language> languages) {
        List<Path> needed = new ArrayList<>(List.of(
                Path.of(env(env, "SANGAM_SCRIPT_NSJAIL", NsjailSandbox.DEFAULT_NSJAIL.toString())),
                Path.of(env(env, "SANGAM_SCRIPT_SECCOMP", NsjailSandbox.DEFAULT_POLICY.toString()))));
        Path runtime = Path.of(env(env, "SANGAM_SCRIPT_RUNTIME", NsjailSandbox.DEFAULT_RUNTIME.toString()));
        for (Language l : languages) {
            needed.add(runtime.resolve(l.harness()));
        }
        for (Path p : needed) {
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("the script sandbox is incomplete, missing " + p
                        + ": the worker does not run scripts without it");
            }
        }
    }

    private static TokenProvider tokenProvider(Map<String, String> env) {
        String tokenUrl = env.get("SANGAM_OIDC_TOKEN_URL");
        if (tokenUrl != null && !tokenUrl.isBlank()) {
            return ClientCredentialsTokenProvider.builder()
                    .tokenEndpoint(tokenUrl)
                    .clientId(env.get("SANGAM_CLIENT_ID"))
                    .clientSecret(env.get("SANGAM_CLIENT_SECRET"))
                    .scope(env.get("SANGAM_SCOPE"))
                    .build();
        }
        String token = env.get("SANGAM_TOKEN");
        return token != null && !token.isBlank() ? TokenProvider.of(token) : TokenProvider.none();
    }

    private static String env(Map<String, String> env, String name, String dflt) {
        String v = env.get(name);
        return v == null || v.isBlank() ? dflt : v;
    }
}
