// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A stand-in interpreter for {@link ScriptRunnerTest}: started as a child JVM by {@link FakeSandbox}, it speaks the
 * harness protocol (or breaks it on purpose) without nsjail, node or python. Test code only: the product has no
 * unsandboxed {@link Sandbox}.
 */
public final class FakeHarness {

    private FakeHarness() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = json.readValue(System.in.readAllBytes(), Map.class);
        switch (args[0]) {
            case "echo" -> out.println(json.writeValueAsString(Map.of("ok", true,
                    "result", Map.of("seen", request.get("variables"), "context", request.get("context")),
                    "hasValue", true, "value", request.get("script"))));
            case "decimal" -> out.println("{\"ok\":true,\"result\":{\"amount\":12.10},\"hasValue\":false,\"value\":null}");
            case "bpmn" -> out.println("{\"ok\":false,\"bpmnError\":{\"code\":\"NOT_ELIGIBLE\",\"message\":\"no\","
                    + "\"variables\":{\"reason\":\"age\"}}}");
            case "error" -> {
                System.err.println("trace line");
                out.println("{\"ok\":false,\"error\":{\"type\":\"TypeError\",\"message\":\"x is undefined\"}}");
            }
            case "flood" -> {
                byte[] line = "x".repeat(8191).concat("\n").getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 1024; i++) {
                    System.out.write(line);
                }
                System.out.flush();
            }
            case "sleep" -> Thread.sleep(60_000);
            case "garbage" -> out.println("not json");
            case "killed" -> Runtime.getRuntime().halt(137);
            case "silent" -> Runtime.getRuntime().halt(1);
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    /** Starts {@link FakeHarness} in a child JVM with the given mode. */
    record FakeSandbox(String mode) implements Sandbox {
        @Override
        public List<String> command(Language language, ScriptLimits limits) {
            String java = ProcessHandle.current().info().command().orElse("java");
            return List.of(java, "-cp", System.getProperty("java.class.path"), FakeHarness.class.getName(), mode);
        }
    }
}
