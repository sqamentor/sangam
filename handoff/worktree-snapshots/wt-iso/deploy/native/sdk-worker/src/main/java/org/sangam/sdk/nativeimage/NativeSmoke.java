// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.nativeimage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.auth.StaticTokenProvider;
import org.sangam.sdk.http.HttpTransport;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline smoke test of the native-light worker image (SANGAM.md R7, "profile compatibility / build evidence").
 *
 * <p>Runs without a server: for every SDK model record listed in {@code model-classes.txt} it checks that Jackson can
 * introspect, deserialize and serialize the record in this image (the reflection metadata is complete), and that a
 * {@link SangamClient} can be built. Exit code 0 = pass, 1 = a record cannot be mapped (missing metadata).
 *
 * <p>Started by {@code sangam-sdk-worker --smoke} through {@link NativeWorkerMain}, and by CI after the native build.
 */
public final class NativeSmoke {

    private NativeSmoke() {
    }

    public static int run() throws Exception {
        ObjectMapper json = HttpTransport.defaultMapper();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String name : modelClasses()) {
            Class<?> type = Class.forName(name);
            try {
                Object value = json.readValue("{}", type);
                json.writeValueAsString(value);
            } catch (InvalidDefinitionException e) {
                // missing constructor/accessor metadata: the native image cannot map this type
                failures.add(name + ": " + e.getOriginalMessage());
            } catch (Exception e) {
                // validation inside the record (required fields) is fine: the type itself was introspected
                if (!json.canSerialize(type)) {
                    failures.add(name + ": not serializable (" + e.getMessage() + ")");
                }
            }
            checked++;
        }
        SangamClient client = SangamClient.builder()
                .baseUrl("http://127.0.0.1:9")
                .tokenProvider(new StaticTokenProvider("native-smoke-token"))
                .build();
        if (client.work() == null || client.processes() == null || client.decisions() == null) {
            failures.add("SangamClient API groups not available");
        }
        System.out.printf("native smoke: %d model records checked, %d failures%n", checked, failures.size());
        failures.forEach(f -> System.out.println("  FAIL " + f));
        return failures.isEmpty() ? 0 : 1;
    }

    private static List<String> modelClasses() throws Exception {
        List<String> out = new ArrayList<>();
        try (InputStream in = NativeSmoke.class.getResourceAsStream("model-classes.txt")) {
            if (in == null) {
                throw new IllegalStateException("model-classes.txt missing from the image (resource-config)");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isBlank()) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }
}
