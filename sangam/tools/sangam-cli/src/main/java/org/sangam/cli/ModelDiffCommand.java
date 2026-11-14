// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.sangam.modeller.diff.ModelDiff;

/**
 * {@code sangam diff BEFORE AFTER [--json] [--fail-on LEVEL,...] [--resource NAME]} (M-125, §3.3d L1): the semantic
 * diff of two versions of a BPMN, DMN, CMMN or form file, offline (no server, no login), for reviews and PR checks.
 *
 * <ul>
 *   <li>Output: one line per change ({@link ModelDiff.Result#toText()}), or the {@code ModelDiffReport} JSON of
 *       {@code POST /models/diff} with {@code --json}.</li>
 *   <li>An empty file (for example {@code /dev/null}) is an absent version: a new or a deleted model.</li>
 *   <li>The format comes from {@code --resource}, else the AFTER file name (BEFORE when AFTER is empty), else the
 *       content.</li>
 *   <li>Exit codes: 0 no change at a {@code --fail-on} level (or no {@code --fail-on}); 1 at least one change at a
 *       {@code --fail-on} level, so a PR check fails; 2 usage, unreadable file or unreadable model.</li>
 * </ul>
 */
final class ModelDiffCommand {

    static final String USAGE = "diff BEFORE AFTER [--json] [--fail-on ELEMENT,FLOW,EXPRESSION,PROPERTY] [--resource NAME]";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final PrintStream out;
    private final PrintStream err;

    ModelDiffCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /** {@code args} are the words after {@code diff}. */
    int run(List<String> args) {
        List<String> files = new ArrayList<>();
        boolean json = false;
        Set<ModelDiff.Level> failOn = EnumSet.noneOf(ModelDiff.Level.class);
        String resource = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--json" -> json = true;
                case "--fail-on", "--resource" -> {
                    if (i + 1 >= args.size()) {
                        return usage(a + " needs a value");
                    }
                    String v = args.get(++i);
                    if (a.equals("--resource")) {
                        resource = v;
                    } else {
                        try {
                            for (String level : v.split(",")) {
                                failOn.add(ModelDiff.Level.valueOf(level.strip().toUpperCase(Locale.ROOT)));
                            }
                        } catch (IllegalArgumentException e) {
                            return usage("--fail-on takes ELEMENT, FLOW, EXPRESSION and PROPERTY, comma separated: " + v);
                        }
                    }
                }
                default -> {
                    if (a.startsWith("--")) {
                        return usage("unknown option " + a);
                    }
                    files.add(a);
                }
            }
        }
        if (files.size() != 2) {
            return usage("two files are needed");
        }
        byte[] before;
        byte[] after;
        try {
            before = Files.readAllBytes(Path.of(files.get(0)));
            after = Files.readAllBytes(Path.of(files.get(1)));
        } catch (IOException | RuntimeException e) {
            err.println("cannot read: " + e.getMessage());
            return Main.USAGE;
        }
        if (resource == null) {
            Path named = Path.of(files.get(after.length > 0 ? 1 : 0)).getFileName();
            resource = named == null ? files.get(1) : named.toString();
        }
        ModelDiff.Result r;
        try {
            r = ModelDiff.compare(resource, before, after);
        } catch (IllegalArgumentException e) {
            err.println("MODEL_UNREADABLE: " + e.getMessage());
            return Main.USAGE;
        }
        if (json) {
            try {
                out.println(JSON.writeValueAsString(r.toJson()));
            } catch (IOException e) {
                err.println("cannot write the report: " + e.getMessage());
                return Main.USAGE;
            }
        } else {
            out.print(r.toText());
        }
        long failing = r.changes().stream().filter(c -> failOn.contains(c.level())).count();
        if (failing > 0) {
            err.println(failing + (failing == 1 ? " change" : " changes") + " at a --fail-on level " + failOn);
            return Main.REFUSED;
        }
        return Main.OK;
    }

    private int usage(String why) {
        err.println("usage: " + USAGE + "\n" + why);
        return Main.USAGE;
    }
}
