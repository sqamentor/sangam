// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 385 (2), layer 2, completeness: every place in sangam-rest-core, sangam-rest, sangam-server and
 * sangam-integration that writes variables into the engine (a conversion of caller JSON, a variable set, a process
 * start, a message correlation, a signal) is listed here, either as GUARDED (the file refuses declared DSC slot names
 * through {@code SlotVariableGuard} or {@code DocumentSlotNames} before it writes) or with the reason it writes no
 * caller-named variable. The count is the number of such calls in the file: a new write path, or one more call in a
 * listed file, fails this test until it is guarded or its reason is written down.
 */
class CallerVariableWritePathsTest {

    static final Pattern WRITE = Pattern.compile("TypedVariables\\.fromJson\\(|SlotVariableGuard\\.callerVariables\\("
            + "|\\.setVariables?(?:Local)?\\(|createMessageCorrelation\\(|createProcessInstanceBy(?:Key|Id)\\("
            + "|startProcessInstanceBy(?:Key|Id|Message)\\(|createSignalEvent\\(|\\.signal\\(");
    static final String GUARDED = "GUARDED";

    record Path0(int calls, String disposition) {
    }

    static Path0 guarded(int calls) {
        return new Path0(calls, GUARDED);
    }

    static Path0 reason(int calls, String why) {
        return new Path0(calls, why);
    }

    /** Relative to sangam-server (the test's working directory). */
    static final Map<String, Path0> PATHS = new TreeMap<>(Map.ofEntries(
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/jdbc/JdbcEventAdapter.java", guarded(1)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/DmnTesting.java",
                    reason(1, "decision table test inputs, evaluated and never written to an instance")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/EngineAdapterWorkAdapter.java",
                    guarded(3)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonCaseAdapter.java",
                    guarded(7)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonDecisionAdapter.java",
                    reason(1, "decision evaluation inputs, never written to an instance")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonInstanceControlAdapter.java",
                    reason(1, "the fixed suspension time variable")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonInstanceHandlerAdapter.java",
                    guarded(3)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java",
                    guarded(4)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonProcessAdapter.java",
                    guarded(19)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonTaskAdapter.java",
                    guarded(5)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonWorkAdapter.java",
                    guarded(3)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonWorkspaceAdapter.java",
                    reason(1, "the answers are canonicalised into the one fixed variable fieldAnswers")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/TaskIndex.java",
                    reason(3, "the fixed task-local index variables")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/support/SlotVariableGuard.java",
                    guarded(1)), // the guard itself: it converts the caller's variables, then refuses a slot name
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/CaseDecisionController.java", guarded(4)),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/PortalController.java", guarded(1)),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/ProcessController.java", guarded(10)),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/TaskController.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/AiBindings.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/CaseModuleProcessStarter.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/CellCrossCellInboxHandler.java", guarded(3)),
            Map.entry("src/main/java/org/sangam/server/bridge/CompliancePurposePlugin.java",
                    reason(1, "an engine plugin writing its fixed resolved-purpose variable")),
            Map.entry("src/main/java/org/sangam/server/bridge/ComplianceWorkflowStarter.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/ConfigCaseBindingPlugin.java",
                    reason(1, "an engine plugin writing its fixed configuration binding variable")),
            Map.entry("src/main/java/org/sangam/server/bridge/FieldEngineEvents.java",
                    reason(3, "field events correlate with fixed variable names (fieldAssignmentId, dispatchJobId, ...)")),
            Map.entry("src/main/java/org/sangam/server/bridge/OperatonDocumentSlots.java",
                    reason(1, "the document route itself: the one writer of a DSC slot (decisions 267, 268)")),
            Map.entry("src/main/java/org/sangam/server/bridge/PortsCaseBridge.java", guarded(1)),
            Map.entry("src/main/java/org/sangam/server/bridge/PortsDynamicBridge.java", guarded(4)),
            Map.entry("src/main/java/org/sangam/server/bridge/PortsFormBridge.java",
                    reason(1, "form data converted for validation only")),
            Map.entry("src/main/java/org/sangam/server/bridge/PortsOpsBridge.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/ProcessContinuationBridge.java", guarded(1)),
            Map.entry("src/main/java/org/sangam/server/bridge/RegistryPortBridges.java",
                    reason(1, "registry facts converted for a decision evaluation, never written to an instance")),
            Map.entry("src/main/java/org/sangam/server/bridge/Remittance835Router.java", guarded(5)),
            Map.entry("src/main/java/org/sangam/server/config/ChannelsWiring.java", guarded(4)),
            Map.entry("src/main/java/org/sangam/server/config/ModellerWiring.java", guarded(3)),
            Map.entry("src/main/java/org/sangam/server/config/ProductionEnvironment.java",
                    reason(1, "EnvironmentPolicy.signal(profile): no engine call")),
            Map.entry("src/main/java/org/sangam/server/web/FlowableWorkerCompatController.java", guarded(1)),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/OperatonLifecyclePorts.java",
                    reason(2, "the fixed cancellation reason variable")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/OperatonMessageCorrelationPort.java",
                    guarded(6)),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/SangamConnectorDelegate.java",
                    reason(3, "a delegate writing the result variable the pack's BPMN names (pack data, not a caller)")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/connector/ConnectorInvoker.java",
                    reason(2, "a deferred-outcome signal of the connector ledger, no variables")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/packs/PackMessageInboxHandler.java",
                    guarded(2)),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineDelegate.java",
                    reason(1, "a delegate writing its fixed run id variable")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineMessages.java",
                    guarded(2))));

    static final List<String> ROOTS = List.of("src/main/java", "../sangam-rest-core/src/main/java",
            "../sangam-rest/src/main/java", "../sangam-integration/src/main/java");

    @Test
    void everyEngineVariableWritePathIsGuardedOrHasItsReason() throws Exception {
        Map<String, Integer> found = new TreeMap<>();
        for (String root : ROOTS) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                    Matcher m = WRITE.matcher(Files.readString(p));
                    int n = 0;
                    while (m.find()) {
                        n++;
                    }
                    if (n > 0) {
                        found.put(p.toString().replace('\\', '/'), n);
                    }
                }
            }
        }
        assertTrue(found.keySet().stream().anyMatch(f -> f.endsWith("OperatonTaskAdapter.java")),
                () -> "the scan works: " + found);
        Map<String, Integer> expected = new TreeMap<>();
        PATHS.forEach((f, p) -> expected.put(f, p.calls()));
        assertEquals(expected, found, "a new or changed engine variable write path: guard it against declared DSC"
                + " slot names (SlotVariableGuard, DocumentSlotNames) or list it with its reason (owner decision 385 (2))");
    }

    @Test
    void everyGuardedPathCallsTheGuard() throws Exception {
        for (Map.Entry<String, Path0> e : PATHS.entrySet()) {
            String src = Files.readString(Path.of(e.getKey()));
            boolean guards = src.contains("SlotVariableGuard.") || src.contains("DocumentSlotNames.");
            assertEquals(GUARDED.equals(e.getValue().disposition()), guards, e.getKey());
        }
    }
}
