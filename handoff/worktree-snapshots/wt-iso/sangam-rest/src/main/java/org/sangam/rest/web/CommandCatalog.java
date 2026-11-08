// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The state-changing REST commands with their fixed R41 {@code command_type} (X.2.2.1), path template (part of the
 * fingerprint, X.2.2.2) and whether the government profile requires an {@code Idempotency-Key}.
 *
 * <p>The table is read from the OpenAPI contract itself ({@code sangam-api.yaml} on the classpath): every operation
 * with {@code x-sangam-idempotent: true} and an {@code x-sangam-command-type}. The contract and the server therefore
 * cannot disagree. Worker calls ({@code x-sangam-fenced}) are fenced by {@code (taskId, lockVersion, attemptId)}
 * instead (VI.3) and are not listed.
 */
public final class CommandCatalog {

    public static final String BASE = "/api/v1";
    public static final String CONTRACT_RESOURCE = "sangam-api.yaml";

    public record Command(String method, String pathTemplate, String commandType, boolean requiredInGovernmentProfile) {
    }

    public record Match(Command command, Map<String, String> pathParams) {
    }

    private record Entry(Command command, PathPattern pattern) {
    }

    /** Commands for which the government profile requires a key (X.2.2.1 "Required or optional"). */
    public static final Set<String> GOVERNMENT_REQUIRED = Set.of("process.start", "message.correlate",
            "task.complete", "task.return", "task.bulk-complete", "process.cancel", "process.delete",
            "process.resubmit", "deployment.create", "deployment.delete", "package.transition", "case.start",
            "portal.apply", "portal.withdraw", "portal.appeal", "power-table.transition");

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private final List<Entry> entries = new ArrayList<>();

    /** Loads the catalogue from {@value #CONTRACT_RESOURCE} on the class path. */
    public CommandCatalog() {
        this(load());
    }

    /** Builds the catalogue from a parsed OpenAPI document (its {@code paths} object). */
    @SuppressWarnings("unchecked")
    public CommandCatalog(Map<String, Object> openApi) {
        PathPatternParser parser = new PathPatternParser();
        Object paths = openApi.get("paths");
        if (!(paths instanceof Map<?, ?> pathMap)) {
            throw new IllegalStateException(CONTRACT_RESOURCE + " has no paths");
        }
        for (Map.Entry<?, ?> p : pathMap.entrySet()) {
            String template = String.valueOf(p.getKey());
            if (!(p.getValue() instanceof Map<?, ?> ops)) {
                continue;
            }
            for (Map.Entry<?, ?> op : ops.entrySet()) {
                String method = String.valueOf(op.getKey());
                if (!METHODS.contains(method) || !(op.getValue() instanceof Map<?, ?> operation)) {
                    continue;
                }
                Map<String, Object> o = (Map<String, Object>) operation;
                Object type = o.get("x-sangam-command-type");
                if (type == null || !Boolean.TRUE.equals(o.get("x-sangam-idempotent"))) {
                    continue;
                }
                String commandType = type.toString();
                entries.add(new Entry(new Command(method.toUpperCase(java.util.Locale.ROOT), template, commandType,
                        GOVERNMENT_REQUIRED.contains(commandType)), parser.parse(BASE + template)));
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException(CONTRACT_RESOURCE + " declares no idempotent commands");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        ClassLoader cl = CommandCatalog.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(CONTRACT_RESOURCE + " is not on the class path (sangam-rest packages it)");
            }
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(64 * 1024 * 1024);
            return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + CONTRACT_RESOURCE, e);
        }
    }

    public List<Command> commands() {
        return entries.stream().map(Entry::command).toList();
    }

    /**
     * Finds the command for a request path (without context path). Literal paths win over templated ones
     * ({@code /events/channels} before {@code /events/{channel}}), as in Spring MVC.
     */
    public Optional<Match> match(String method, String path) {
        PathContainer pc = PathContainer.parsePath(path);
        Match best = null;
        PathPattern bestPattern = null;
        for (Entry e : entries) {
            if (!e.command.method().equalsIgnoreCase(method)) {
                continue;
            }
            PathPattern.PathMatchInfo info = e.pattern.matchAndExtract(pc);
            if (info != null && (bestPattern == null || PathPattern.SPECIFICITY_COMPARATOR.compare(e.pattern, bestPattern) < 0)) {
                best = new Match(e.command, info.getUriVariables());
                bestPattern = e.pattern;
            }
        }
        return Optional.ofNullable(best);
    }
}
