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
 * Owner decisions 385 (2) and 387 (1), layer 2, completeness: every place in every sangam-* module that writes
 * variables into the engine (a conversion of caller JSON, a variable set, a process
 * start, a message correlation, a signal) is listed here, either as GUARDED (the file refuses declared DSC slot names
 * through {@code SlotVariableGuard} or {@code DocumentSlotNames} before it writes) or with the reason it writes no
 * caller-named variable. The count is the number of such calls in the file: a new write path, or one more call in a
 * listed file, fails this test until it is guarded or its reason is written down.
 */
class CallerVariableWritePathsTest {

    static final Pattern WRITE = Pattern.compile("TypedVariables\\.fromJson\\(|SlotVariableGuard\\.callerVariables\\("
            + "|\\.setVariables?(?:Local|Async)?\\(|createMessageCorrelation\\(|createProcessInstanceBy(?:Key|Id)\\("
            + "|startProcessInstanceBy(?:Key|Id|Message)\\(|createSignalEvent\\(|\\.signal\\("
            // decision 387 (1): task and work completion, form submission, event delivery, case creation, the engine port
            + "|\\.complete\\(|resolveTask\\(|submitTaskForm\\(|submitStartForm\\(|messageEventReceived\\("
            + "|signalEventReceived\\(|completeCaseExecution\\(|\\.handleBpmnError\\(|\\.withCaseDefinition"
            + "|\\bstartProcess\\(|\\bstartCase\\(|\\bcorrelateMessage(?:ToBranch)?\\(|\\bcompleteTask\\(|\\bcompleteExternal\\("
            + "|\\bbpmnErrorExternal\\(");
    static final String GUARDED = "GUARDED";

    record Path0(int calls, String disposition) {
    }

    static Path0 guarded(int calls) {
        return new Path0(calls, GUARDED);
    }

    static Path0 reason(int calls, String why) {
        return new Path0(calls, why);
    }

    /** Relative to sangam-server (the test's working directory); every sangam-* module with main sources (decision 387). */
    static final Map<String, Path0> PATHS = new TreeMap<>(Map.ofEntries(
            Map.entry("../sangam-ai/src/main/java/org/sangam/ai/agent/AgentRuntime.java", reason(1, "not an engine write: an LLM completion")),
            Map.entry("../sangam-ai/src/main/java/org/sangam/ai/classify/DocumentClassifier.java", reason(1, "not an engine write: an LLM completion")),
            Map.entry("../sangam-ai/src/main/java/org/sangam/ai/draft/DraftService.java", reason(1, "not an engine write: an LLM completion")),
            Map.entry("../sangam-ai/src/main/java/org/sangam/ai/expr/ExpressionPlayground.java", reason(1, "not an engine write: an LLM completion")),
            Map.entry("../sangam-ai/src/main/java/org/sangam/ai/judge/LlmJudge.java", reason(1, "not an engine write: an LLM completion")),
            Map.entry("../sangam-billing/src/main/java/org/sangam/billing/JdbcUnverifiedPaymentStore.java", reason(1, "not an engine write: PendingAuditDrainer.signal queues an audit-chain drain")),
            Map.entry("../sangam-case/src/main/java/org/sangam/casefile/cmmn/CaseFileCmmnListener.java", reason(3, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-case/src/main/java/org/sangam/casefile/delegate/CaseFileDelegate.java", reason(8, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-case/src/main/java/org/sangam/casefile/delegate/DeadlineDelegate.java", reason(5, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-case/src/main/java/org/sangam/casefile/delegate/MeetingDelegate.java", reason(8, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-case/src/main/java/org/sangam/casefile/delegate/ReferenceDelegate.java", reason(8, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-control-plane/src/main/java/org/sangam/controlplane/move/TenantMoveService.java", reason(1, "the tenant move process with fixed variables (moveId, tenant, cells, routeEpoch, retention, drained)")),
            Map.entry("../sangam-control-plane/src/main/java/org/sangam/controlplane/move/TenantMoveSteps.java", reason(3, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-core/src/main/java/org/sangam/core/api/EngineAdapter.java", reason(6, "the engine port's declarations; the Operaton engine adapter refuses a slot name in every implementation call")),
            Map.entry("../sangam-core/src/main/java/org/sangam/core/engine/SangamEngineAdapter.java", reason(1, "the engine port's declaration; the Operaton engine adapter refuses a slot name")),
            Map.entry("../sangam-engine/src/main/java/org/sangam/engine/OperatonEngineAdapter.java", guarded(19)),
            Map.entry("../sangam-engine/src/main/java/org/sangam/engine/RouteCorrection.java", guarded(2)),
            Map.entry("../sangam-engine/src/main/java/org/sangam/engine/continuation/ProcessContinuations.java", reason(2, "a resubmission successor takes the predecessor's own variables; a DSC binding is per instance, so a copied slot is unbound on the successor (layer 1)")),
            Map.entry("../sangam-engine/src/main/java/org/sangam/engine/deploy/MultiInstanceCompensationContext.java", reason(1, "compensation restores a branch's own snapshot of its local variables")),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java", guarded(5)),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/SkipExpressionSupport.java", reason(1, "an activity behaviour passing a signal on, no variables")),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/TriggerableServiceTaskBehavior.java", reason(1, "a comment naming RuntimeService.signal, no call")),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/cmmn/CaseLifecycleService.java", guarded(4)),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/debug/DebuggerService.java", guarded(3)),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/events/EventDispatcher.java", guarded(7)),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/forms/SangamFormService.java", guarded(6)),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/migration/MigrationDocumentService.java", guarded(1)),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/task/TaskEnhancementService.java", guarded(2)),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/tenancy/ChangeTenantService.java", reason(2, "a tenant move recreates an instance with its own variables; a DSC binding is per instance, so a copied slot is unbound on the new one (layer 1)")),
            Map.entry("../sangam-flowable-ports/src/main/java/org/sangam/ports/variables/MultiInstanceAggregation.java", reason(1, "the aggregate target variable is named by the model")),
            Map.entry("../sangam-geo/src/main/java/org/sangam/geo/bpmn/GeoDelegates.java", guarded(9)),
            Map.entry("../sangam-host-conformance/src/main/java/org/sangam/host/conformance/EquivalenceScenarios.java", reason(1, "a conformance scenario writing the literal feeMinor through the guarded process port")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/bridge/ConnectorExternalTaskWorker.java", reason(3, "the result variables are named by the topic binding's resultVariable (configuration); the engine adapter refuses a slot name (decision 387)")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/OperatonLifecyclePorts.java", reason(2, "the fixed cancellation reason variable")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/OperatonMessageCorrelationPort.java", guarded(6)),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/SangamConnectorDelegate.java", reason(3, "a delegate writing the result variable the pack's BPMN names (pack data, not a caller)")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/connector/ConnectorInvoker.java", reason(2, "a deferred-outcome signal of the connector ledger, no variables")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/dsc/DscSigningFlow.java", reason(1, "not an engine write: a prepared PDF signature is completed")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/idempotency/IdempotencyContext.java", reason(1, "not an engine write: the idempotency store records a response")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/idempotency/IdempotencyService.java", reason(1, "not an engine write: the idempotency store records a response")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/packs/PackMessageInboxHandler.java", guarded(2)),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineDelegate.java", reason(1, "a delegate writing its fixed run id variable")),
            Map.entry("../sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineMessages.java", guarded(2)),
            Map.entry("../sangam-ledger/src/main/java/org/sangam/ledger/LedgerJournal.java", reason(1, "not an engine write: PendingAuditDrainer.signal queues an audit-chain drain")),
            Map.entry("../sangam-modeller/src/main/java/org/sangam/modeller/replay/operaton/OperatonReplayCapture.java", reason(4, "M-136 replay capture: writes only on its own fresh isolated engine (ReplayEngines.start), never the server's")),
            Map.entry("../sangam-modeller/src/main/java/org/sangam/modeller/replay/operaton/OperatonReplayDriver.java", reason(4, "M-136 replay: writes only on its own fresh isolated engine (ReplayEngines.start), never the server's")),
            Map.entry("../sangam-onboarding/src/main/java/org/sangam/onboarding/flow/OnboardingFlowEngine.java", reason(2, "the onboarding process of the platform with the fixed variables runId, approved and checker")),
            Map.entry("../sangam-onboarding/src/main/java/org/sangam/onboarding/flow/OnboardingService.java", reason(4, "completes onboarding tasks with the fixed variables approved and checker")),
            Map.entry("../sangam-onboarding/src/main/java/org/sangam/onboarding/flow/OnboardingStepDelegate.java", reason(1, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-ops/src/main/java/org/sangam/ops/flowable/OperatonEngineStateWriter.java", guarded(4)),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/ActorRecorderListener.java", reason(4, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/ApprovalBindingListener.java", reason(7, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/AssignmentStrategyDelegate.java", reason(1, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/BatchDelegate.java", guarded(24)),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/BatchJoinListener.java", reason(3, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/CaseRecordDelegate.java", reason(14, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/CommitteeDelegate.java", reason(7, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/ConnectorCallDelegate.java", guarded(5)),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/CorrelationDelegate.java", reason(2, "correlates with the names the model's variables field gives (name:alias, pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/CumulativeThresholdDelegate.java", reason(3, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/DecisionDelegate.java", guarded(1)),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/DocumentDelegate.java", reason(1, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/DscSignatureGuardListener.java", reason(7, "the DSC gate's own task-local capture and evidence variables")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/ExternalOperationDelegate.java", guarded(28)),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/LedgerDelegate.java", reason(3, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/LifecycleStateDelegate.java", reason(1, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/LinkedInstanceDelegate.java", reason(5, "starts a linked instance with the variables the model's inherit field names; a DSC binding is per instance, so a copied slot is unbound there (layer 1)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/MoneyCalculationDelegate.java", reason(15, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/NotifyDelegate.java", reason(1, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/PackStartListener.java", reason(14, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/PowerLimitGuardListener.java", reason(1, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/ProfileValueDelegate.java", reason(7, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/RecommendationLevelDelegate.java", reason(16, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/SeatResolutionDelegate.java", reason(10, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/SetVariablesDelegate.java", reason(1, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/SignatureDelegate.java", reason(9, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/SlaDelegate.java", reason(22, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/SuspensionDelegate.java", reason(1, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/core/Vars.java", guarded(4)),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/gov/HearingScheduleDelegate.java", reason(4, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/gov/MeritListDelegate.java", reason(2, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/gov/ReconciliationMatchDelegate.java", reason(2, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/gov/WaitlistDelegate.java", reason(5, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/universal/EventWindowDelegate.java", reason(6, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-packs/src/main/java/org/sangam/packs/worker/EmbeddedWorkerRunner.java", guarded(2)),
            Map.entry("../sangam-programme/src/main/java/org/sangam/programme/delegate/DefaulterReminderDelegate.java", reason(3, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-programme/src/main/java/org/sangam/programme/delegate/MilestoneDelayDelegate.java", reason(1, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-programme/src/main/java/org/sangam/programme/delegate/ReturnCampaignDelegate.java", reason(4, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-programme/src/main/java/org/sangam/programme/delegate/ReturnStateDelegate.java", reason(5, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-programme/src/main/java/org/sangam/programme/delegate/RollupDelegate.java", reason(3, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-programme/src/main/java/org/sangam/programme/delegate/VarianceDelegate.java", reason(2, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-registry/src/main/java/org/sangam/registry/bpmn/AssetDelegate.java", reason(6, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-registry/src/main/java/org/sangam/registry/bpmn/CustodyDelegate.java", reason(13, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-registry/src/main/java/org/sangam/registry/bpmn/PartyDelegate.java", reason(9, "a delegate writing names fixed in code or named by the model it runs in (pack data)")),
            Map.entry("../sangam-registry/src/main/java/org/sangam/registry/bpmn/RegistryDelegate.java", reason(19, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/BookingActionDelegate.java", reason(5, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/EntitlementCheckDelegate.java", reason(7, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/HoldBookingDelegate.java", reason(7, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/IssueTokenDelegate.java", reason(4, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/OperatonApprovalPort.java", guarded(2)),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/PublishRosterDelegate.java", reason(1, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/QueueSlaBreachDelegate.java", reason(3, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/RosterSwapDecisionDelegate.java", reason(1, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/bpmn/WaitlistDelegate.java", reason(11, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-resources/src/main/java/org/sangam/resources/rest/QueueRestAdapter.java", reason(1, "not an engine write: a queue token is completed")),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/modules/channels/ChannelsLiveHub.java", reason(1, "not an engine write: an SSE emitter is closed")),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/modules/registry/web/AssetController.java", reason(1, "not an engine write: a maintenance work order of the asset registry is completed")),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/modules/resources/QueueController.java", reason(1, "not an engine write: a queue token is completed")),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/CaseDecisionController.java", guarded(5)),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/PortalController.java", guarded(1)),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/ProcessController.java", guarded(10)),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/TaskController.java", guarded(3)),
            Map.entry("../sangam-rest/src/main/java/org/sangam/rest/web/WorkController.java", reason(1, "passes the request to the guarded work port")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/jdbc/JdbcEventAdapter.java", guarded(1)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/jdbc/JdbcIdempotencyAdapter.java", reason(1, "not an engine write: the idempotency store records a response")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/DmnTesting.java", reason(1, "decision table test inputs, evaluated and never written to an instance")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/EngineAdapterWorkAdapter.java", guarded(5)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonCaseAdapter.java", guarded(8)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonDecisionAdapter.java", reason(1, "decision evaluation inputs, never written to an instance")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonInstanceControlAdapter.java", reason(1, "the fixed suspension time variable")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonInstanceHandlerAdapter.java", guarded(3)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java", guarded(4)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonPortalAdapter.java", reason(1, "a clarification reply completes with the fixed variables clarificationReply and sangamClarificationDocumentIds")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonProcessAdapter.java", guarded(19)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonTaskAdapter.java", guarded(7)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonWorkAdapter.java", guarded(5)),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonWorkspaceAdapter.java", reason(3, "the answers are canonicalised into the one fixed variable fieldAnswers; completion goes through the guarded task port")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/TaskIndex.java", reason(3, "the fixed task-local index variables")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/TrackingWorkPort.java", reason(1, "a decorator that passes the request to the guarded work port")),
            Map.entry("../sangam-rest-core/src/main/java/org/sangam/rest/support/SlotVariableGuard.java", guarded(1)),
            Map.entry("../sangam-security/src/main/java/org/sangam/security/assignment/SeatTaskListener.java", reason(3, "a delegate or listener of the engine writing names fixed in its code")),
            Map.entry("../sangam-security/src/main/java/org/sangam/security/quota/JdbcTenantQuotas.java", reason(1, "not an engine write: a CompletableFuture is completed")),
            Map.entry("src/main/java/org/sangam/server/bridge/AiBindings.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/CaseModuleProcessStarter.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/CellCrossCellInboxHandler.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/ChannelMediaWorker.java", reason(1, "not an engine write: a media queue item is completed")),
            Map.entry("src/main/java/org/sangam/server/bridge/CompliancePurposePlugin.java", reason(1, "an engine plugin writing its fixed resolved-purpose variable")),
            Map.entry("src/main/java/org/sangam/server/bridge/ComplianceWorkflowStarter.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/ConfigCaseBindingPlugin.java", reason(1, "an engine plugin writing its fixed configuration binding variable")),
            Map.entry("src/main/java/org/sangam/server/bridge/FieldEngineEvents.java", reason(2, "field events correlate with fixed variable names (fieldAssignmentId, dispatchJobId, ...)")),
            Map.entry("src/main/java/org/sangam/server/bridge/OperatonDocumentSlots.java", reason(1, "the document route itself: the one writer of a DSC slot (decisions 267, 268)")),
            Map.entry("src/main/java/org/sangam/server/bridge/PortsCaseBridge.java", guarded(1)),
            Map.entry("src/main/java/org/sangam/server/bridge/PortsDynamicBridge.java", guarded(5)),
            Map.entry("src/main/java/org/sangam/server/bridge/PortsFormBridge.java", reason(1, "form data converted for validation only")),
            Map.entry("src/main/java/org/sangam/server/bridge/PortsOpsBridge.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/bridge/ProcessContinuationBridge.java", guarded(1)),
            Map.entry("src/main/java/org/sangam/server/bridge/Remittance835Router.java", guarded(4)),
            Map.entry("src/main/java/org/sangam/server/config/ChannelsWiring.java", guarded(4)),
            Map.entry("src/main/java/org/sangam/server/config/ModellerWiring.java", guarded(2)),
            Map.entry("src/main/java/org/sangam/server/config/ProductionEnvironment.java", reason(1, "EnvironmentPolicy.signal(profile): no engine call")),
            Map.entry("src/main/java/org/sangam/server/web/DscSigningController.java", reason(1, "not an engine write: the DSC signing flow completes a signature, no engine variable")),
            Map.entry("src/main/java/org/sangam/server/web/FlowableWorkerCompatController.java", guarded(3))));

    /** Decision 387 (1): every module of the reactor with main Java sources, found on disk, so a new module is scanned. */
    static List<Path> roots() throws java.io.IOException {
        try (Stream<Path> modules = Files.list(Path.of(".."))) {
            return modules.filter(m -> m.getFileName().toString().startsWith("sangam-"))
                    .map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).sorted().toList();
        }
    }

    /** The file guards before it writes: the REST guard, the core slot names, or the packs' dynamic-name setter. */
    static boolean callsTheGuard(String src) {
        return src.contains("SlotVariableGuard.") || src.contains("DocumentSlotNames.") || src.contains("Vars.setDynamic(");
    }

    @Test
    void everyEngineVariableWritePathIsGuardedOrHasItsReason() throws Exception {
        Map<String, Integer> found = new TreeMap<>();
        List<Path> roots = roots();
        assertTrue(roots.size() >= 30, () -> "the module scan works: " + roots);
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                    Matcher m = WRITE.matcher(Files.readString(p));
                    int n = 0;
                    while (m.find()) {
                        n++;
                    }
                    if (n > 0) {
                        found.put(relative(p), n);
                    }
                }
            }
        }
        assertTrue(found.keySet().stream().anyMatch(f -> f.endsWith("OperatonTaskAdapter.java")),
                () -> "the scan works: " + found);
        Map<String, Integer> expected = new TreeMap<>();
        PATHS.forEach((f, p) -> expected.put(f, p.calls()));
        assertEquals(expected, found, "a new or changed engine variable write path: guard it against declared DSC"
                + " slot names (SlotVariableGuard, DocumentSlotNames, Vars.setDynamic) or list it with its reason (owner"
                + " decisions 385 (2) and 387 (1))");
    }

    /** sangam-server's own files as {@code src/main/java/...}, every other module's as {@code ../<module>/...}. */
    static String relative(Path p) {
        String s = p.normalize().toString().replace('\\', '/');
        return s.startsWith("../sangam-server/") ? s.substring("../sangam-server/".length()) : s;
    }

    @Test
    void everyGuardedPathCallsTheGuard() throws Exception {
        for (Map.Entry<String, Path0> e : PATHS.entrySet()) {
            String src = Files.readString(Path.of(e.getKey()));
            boolean guards = callsTheGuard(src);
            assertEquals(GUARDED.equals(e.getValue().disposition()), guards, e.getKey());
        }
    }
}
