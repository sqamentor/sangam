"""Splits SangamTestEngine.java (997 lines) into the engine, EngineModel (state + problem/wire helpers) and
EngineWorkCalls (the fenced worker calls of VI.3). Line numbers refer to the file as read before the split."""
import re
import sys

base = sys.argv[1]
p = base + "/SangamTestEngine.java"
L = open(p, encoding="utf-8").read().split("\n")  # L[i-1] is line i


def rng(a, b):
    return L[a - 1:b]


HDR = "// SPDX-License-Identifier: Apache-2.0\npackage org.sangam.sdk.testing;\n"

# ---------------------------------------------------------------- EngineModel
model_types = rng(397, 466)
model_types = [re.sub(r"^    private (static final class|record)", r"    \1", l) for l in model_types]
model_types = [l.replace("    record Stored", "    record Stored").replace("    record Answer", "    record Answer")
               for l in model_types]
helpers = rng(717, 731) + [""] + rng(936, 948) + [""] + rng(980, 996)
helpers = [l.replace("    private static ", "    static ") for l in helpers]
model = HDR + """
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State and wire helpers of {@link SangamTestEngine} (M-021): the work item with its lease row, operation ledger entry
 * and tombstone, reconciliation items, the R41 store entry, and RFC 9457 problems built like the server's
 * {@code ApiProblem} ({@code staleLease}, {@code gone}, {@code notFound}). Package-private.
 */
final class EngineModel {

    private EngineModel() {
    }

""" + "\n".join(model_types) + "\n\n" + "\n".join(helpers) + """

    static Duration lockDuration(Long ms) {
        Duration d = ms == null || ms <= 0 ? SangamTestEngine.DEFAULT_LOCK : Duration.ofMillis(ms);
        return d.compareTo(SangamTestEngine.MAX_LOCK) > 0 ? SangamTestEngine.MAX_LOCK : d;
    }
}
"""

# ---------------------------------------------------------------- EngineWorkCalls
calls_body = rng(781, 934)
calls_body = [l.replace("    private ", "    ") for l in calls_body]
calls_body = [l.replace("clock.instant()", "clock.instant()") for l in calls_body]
calls = HDR + """
import static org.sangam.sdk.testing.EngineModel.endLease;
import static org.sangam.sdk.testing.EngineModel.gone;
import static org.sangam.sdk.testing.EngineModel.lockDuration;
import static org.sangam.sdk.testing.EngineModel.notFound;
import static org.sangam.sdk.testing.EngineModel.removeLease;
import static org.sangam.sdk.testing.EngineModel.staleLease;
import static org.sangam.sdk.testing.EngineModel.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.sangam.sdk.testing.EngineModel.Problem;
import org.sangam.sdk.testing.EngineModel.Work;

/**
 * The fenced worker calls of {@link SangamTestEngine} (VI.3, VI.5): extend-lock, complete, fail, bpmn-error, unlock,
 * outcome-unknown and cancel-ack, each checked like {@code OperatonWorkAdapter} (fence order NO_LEASE, STALE_VERSION,
 * NOT_OWNER, EXPIRED, then the attempt id). The engine calls them while it holds its lock. Package-private.
 */
final class EngineWorkCalls {

    private final Map<String, Work> works;
    private final Set<String> irreversible;
    private final Clock clock;
    private final ObjectMapper json;
    private final BiFunction<String, Work, String> openRecon;

    EngineWorkCalls(Map<String, Work> works, Set<String> irreversible, Clock clock, ObjectMapper json,
                    BiFunction<String, Work, String> openRecon) {
        this.works = works;
        this.irreversible = irreversible;
        this.clock = clock;
        this.json = json;
        this.openRecon = openRecon;
    }

    private String openRecon(String kind, Work w) {
        return openRecon.apply(kind, w);
    }

""" + "\n".join(calls_body) + "\n}\n"

# ---------------------------------------------------------------- the engine
keep = rng(1, 396) + rng(468, 686) + rng(691, 716) + rng(732, 780) + rng(949, 979) + ["}", ""]
s = "\n".join(keep)
s = s.replace("import java.util.concurrent.atomic.AtomicLong;\n",
              "import java.util.concurrent.atomic.AtomicLong;\n"
              "import org.sangam.sdk.testing.EngineModel.Answer;\n"
              "import org.sangam.sdk.testing.EngineModel.Problem;\n"
              "import org.sangam.sdk.testing.EngineModel.Recon;\n"
              "import org.sangam.sdk.testing.EngineModel.Stored;\n"
              "import org.sangam.sdk.testing.EngineModel.Work;\n", 1)
s = s.replace("package org.sangam.sdk.testing;\n\n",
              "package org.sangam.sdk.testing;\n\n"
              "import static org.sangam.sdk.testing.EngineModel.defaultTitle;\n"
              "import static org.sangam.sdk.testing.EngineModel.endLease;\n"
              "import static org.sangam.sdk.testing.EngineModel.lockDuration;\n"
              "import static org.sangam.sdk.testing.EngineModel.notFound;\n"
              "import static org.sangam.sdk.testing.EngineModel.text;\n\n", 1)
# the worker-call switch delegates to EngineWorkCalls
for name in ("extendLock(", "complete(", "fail(", "bpmnError(", "unlock(", "outcomeUnknown(", "cancelAck("):
    s = s.replace("ok(200, " + name, "ok(200, workCalls." + name)
    s = s.replace("ok(202, " + name, "ok(202, workCalls." + name)
    s = s.replace("                        " + name, "                        workCalls." + name)
anchor = "    private volatile Duration longPollCap = Duration.ofSeconds(1);\n"
assert anchor in s
clock_end = "    };\n\n    private SangamTestEngine() throws IOException {"
assert clock_end in s
s = s.replace(clock_end, "    };\n    private final EngineWorkCalls workCalls = new EngineWorkCalls(works, irreversible, clock, json, this::openRecon);"
                         "\n\n    private SangamTestEngine() throws IOException {", 1)
open(p, "w", encoding="utf-8", newline="\n").write(s)
open(base + "/EngineModel.java", "w", encoding="utf-8", newline="\n").write(model)
open(base + "/EngineWorkCalls.java", "w", encoding="utf-8", newline="\n").write(calls)
for f in ("SangamTestEngine.java", "EngineModel.java", "EngineWorkCalls.java"):
    print(f, open(base + "/" + f, encoding="utf-8").read().count("\n"))
