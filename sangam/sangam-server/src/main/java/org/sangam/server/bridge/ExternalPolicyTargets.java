// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sangam.rest.web.CommandCatalog;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What the external deny-only policy check covers (owner decisions 166 and 200), and the command type of each request:
 * <ol>
 *   <li><b>Sangam REST commands</b>: every state-changing command of the {@link CommandCatalog}
 *       ({@code x-sangam-idempotent}), with its R41 command type and contract path template;</li>
 *   <li><b>external-worker calls</b>: every state-changing operation the contract marks {@code x-sangam-fenced}
 *       (fetch-and-lock, extend-lock, complete, fail, bpmn-error, unlock, outcome-unknown, cancel-ack, the step
 *       checkpoint PUT), command type {@code work.<last literal path segment>}, e.g. {@code work.complete},
 *       {@code work.fetch-and-lock}, {@code work.step};</li>
 *   <li><b>Operaton REST</b>: every POST, PUT, PATCH or DELETE under {@value #ENGINE_REST_PATH}, command type
 *       {@value #ENGINE_REST}; Operaton has no contract here, so the path template sent is the request path itself
 *       (without query) and there are no path parameters.</li>
 * </ol>
 * The worker table is read from the same contract as the command catalogue, so the contract and the check cannot
 * disagree. Reads ({@code GET}) are not commands and are never checked.
 */
public final class ExternalPolicyTargets {

    /** Command type of every state-changing Operaton REST call. */
    public static final String ENGINE_REST = "engine-rest";
    /** Path prefix of Operaton REST. */
    public static final String ENGINE_REST_PATH = "/engine-rest";
    /** Prefix of the worker command types. */
    public static final String WORK_PREFIX = "work.";

    private static final Set<String> CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** The command checked for one request. */
    public record Target(String commandType, String method, String pathTemplate, Map<String, String> pathParams) {
    }

    private record Worker(String commandType, String method, String pathTemplate, PathPattern pattern) {
    }

    private final CommandCatalog catalog;
    private final List<Worker> workers;

    /** Reads the worker operations from {@value CommandCatalog#CONTRACT_RESOURCE} on the class path. */
    public ExternalPolicyTargets(CommandCatalog catalog) {
        this(catalog, loadContract());
    }

    /** Builds the targets from the catalogue and a parsed OpenAPI document (its {@code paths} object). */
    public ExternalPolicyTargets(CommandCatalog catalog, Map<String, Object> openApi) {
        if (catalog == null) {
            throw new IllegalArgumentException("the command catalogue is required");
        }
        this.catalog = catalog;
        this.workers = workers(openApi);
        if (workers.isEmpty()) {
            throw new IllegalStateException(CommandCatalog.CONTRACT_RESOURCE + " declares no fenced worker operations");
        }
    }

    /** Every command type this check can name: catalogue commands, worker calls and {@value #ENGINE_REST}. */
    public Set<String> commandTypes() {
        Set<String> out = new LinkedHashSet<>();
        catalog.commands().forEach(c -> out.add(c.commandType()));
        workers.forEach(w -> out.add(w.commandType()));
        out.add(ENGINE_REST);
        return out;
    }

    /** Whether the path (without context path) can hold a checked request at all. */
    public static boolean covers(String method, String path) {
        return method != null && CHANGING.contains(method.toUpperCase(Locale.ROOT)) && path != null
                && (path.startsWith(CommandCatalog.BASE + "/") || path.equals(ENGINE_REST_PATH)
                || path.startsWith(ENGINE_REST_PATH + "/"));
    }

    /** The command of a request, or empty when the request is not a checked command. */
    public Optional<Target> resolve(String method, String path) {
        if (!covers(method, path)) {
            return Optional.empty();
        }
        String m = method.toUpperCase(Locale.ROOT);
        if (path.equals(ENGINE_REST_PATH) || path.startsWith(ENGINE_REST_PATH + "/")) {
            return Optional.of(new Target(ENGINE_REST, m, path, Map.of()));
        }
        var command = catalog.match(m, path);
        if (command.isPresent()) {
            CommandCatalog.Command c = command.get().command();
            return Optional.of(new Target(c.commandType(), c.method(), c.pathTemplate(), command.get().pathParams()));
        }
        PathContainer pc = PathContainer.parsePath(path);
        for (Worker w : workers) {
            if (!w.method().equals(m)) {
                continue;
            }
            PathPattern.PathMatchInfo info = w.pattern().matchAndExtract(pc);
            if (info != null) {
                return Optional.of(new Target(w.commandType(), m, w.pathTemplate(), info.getUriVariables()));
            }
        }
        return Optional.empty();
    }

    /** {@code work.<last literal segment>} of a fenced path, e.g. {@code /work/{id}/complete} gives work.complete. */
    static String workerCommandType(String template) {
        String[] parts = template.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank() && !parts[i].startsWith("{")) {
                return WORK_PREFIX + parts[i];
            }
        }
        throw new IllegalStateException("fenced operation " + template + " has no literal path segment");
    }

    @SuppressWarnings("unchecked")
    private static List<Worker> workers(Map<String, Object> openApi) {
        Object paths = openApi == null ? null : openApi.get("paths");
        if (!(paths instanceof Map<?, ?> pathMap)) {
            throw new IllegalStateException(CommandCatalog.CONTRACT_RESOURCE + " has no paths");
        }
        PathPatternParser parser = new PathPatternParser();
        List<Worker> out = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>();
        for (Map.Entry<?, ?> p : pathMap.entrySet()) {
            String template = String.valueOf(p.getKey());
            if (!(p.getValue() instanceof Map<?, ?> ops)) {
                continue;
            }
            for (Map.Entry<?, ?> op : ops.entrySet()) {
                String method = String.valueOf(op.getKey()).toUpperCase(Locale.ROOT);
                if (!CHANGING.contains(method) || !(op.getValue() instanceof Map<?, ?> operation)
                        || !Boolean.TRUE.equals(((Map<String, Object>) operation).get("x-sangam-fenced"))) {
                    continue;
                }
                String type = workerCommandType(template);
                if (!types.add(type)) {
                    throw new IllegalStateException("two fenced operations give the command type " + type);
                }
                out.add(new Worker(type, method, template, parser.parse(CommandCatalog.BASE + template)));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadContract() {
        try (InputStream in = ExternalPolicyTargets.class.getClassLoader()
                .getResourceAsStream(CommandCatalog.CONTRACT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(CommandCatalog.CONTRACT_RESOURCE + " is not on the class path");
            }
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(64 * 1024 * 1024);
            return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + CommandCatalog.CONTRACT_RESOURCE, e);
        }
    }
}
