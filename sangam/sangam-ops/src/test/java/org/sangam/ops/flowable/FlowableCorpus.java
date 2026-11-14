// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.sangam.ops.flowable.OperatonStateWriter.ActivityFacts;
import org.sangam.ops.flowable.OperatonStateWriter.ActivityKind;
import org.sangam.ops.flowable.OperatonStateWriter.TargetModel;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * M-135 test support: the synthetic Flowable corpus (decision 169; no real data) and a target model that matches the
 * {@code order} v1 definition of {@link #ORDER_BPMN}. Corpus times are wall-clock in {@link #ZONE}.
 */
final class FlowableCorpus {

    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    static final String TENANT = "acme";
    static final String TARGET_DEFINITION = "order:1:target";
    static final List<DefinitionMapping> MAPPINGS = List.of(new DefinitionMapping("order", 1, "order", 1, Map.of()));

    /** SUPPORTED instances of tenant acme (b1 belongs to tenant beta and is never read). */
    static final List<String> SUPPORTED_IDS = List.of("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8");

    /** Rejected instance id to the REJECT code it must carry. */
    static final Map<String, String> REJECTED = Map.ofEntries(
            Map.entry("r01", StateClassifier.LOCKED_JOB),
            Map.entry("r02", StateClassifier.FAILED_JOB),
            Map.entry("r03", StateClassifier.DEADLETTER_JOB),
            Map.entry("r04", StateClassifier.MULTI_INSTANCE),
            Map.entry("r05", StateClassifier.CALL_ACTIVITY_PARENT),
            Map.entry("r06", StateClassifier.CALL_ACTIVITY_CHILD),
            Map.entry("r07", StateClassifier.COMPENSATION),
            Map.entry("r08", StateClassifier.SUBTASK),
            Map.entry("r09", StateClassifier.BINARY_VARIABLE),
            Map.entry("r10", StateClassifier.JSON_VARIABLE),
            Map.entry("r11", StateClassifier.AMBIGUOUS_LOCAL_TIME),
            Map.entry("r12", StateClassifier.AMBIGUOUS_LOCAL_TIME),
            Map.entry("r13", StateClassifier.CASE_LINKED),
            Map.entry("r14", StateClassifier.NO_DEFINITION_MAPPING),
            Map.entry("r15", StateClassifier.EXECUTION_LOCAL_VARIABLE),
            Map.entry("r16", StateClassifier.TARGET_ACTIVITY_MISSING),
            // decision 169, from the Flowable source check
            Map.entry("r17", StateClassifier.UNSUPPORTED_JOB_HANDLER),
            Map.entry("r18", StateClassifier.LONG_TEXT_VARIABLE),
            Map.entry("r19", StateClassifier.LONG_TEXT_VARIABLE),
            Map.entry("r20", StateClassifier.TIMER_ELEMENT_MISSING),
            // owner decision 231: source model listeners reject unless acknowledged; an unread model always rejects
            Map.entry("r21", StateClassifier.SOURCE_DELETE_LISTENER),
            Map.entry("r22", StateClassifier.SOURCE_EVENT_LISTENER),
            Map.entry("r23", StateClassifier.SOURCE_MODEL_UNREAD));

    private FlowableCorpus() {
    }

    static FlowableRows supported() {
        return load("corpus-supported.json");
    }

    static FlowableRows rejected() {
        return load("corpus-rejected.json");
    }

    static FlowableRows load(String name) {
        try (InputStream in = Objects.requireNonNull(FlowableCorpus.class.getResourceAsStream(name), name)) {
            return FlowableRows.fixture(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The activities of {@link #ORDER_BPMN}, as {@link OperatonEngineStateWriter} reports them, plus a call activity
     * {@code callSub} (not in the BPMN) for the call-activity rejection.
     */
    static final Map<String, ActivityFacts> ORDER_ACTIVITIES = Map.ofEntries(
            Map.entry("review", new ActivityFacts(ActivityKind.USER_TASK, false, false)),
            Map.entry("approve", new ActivityFacts(ActivityKind.USER_TASK, false, false)),
            Map.entry("approveTimeout", new ActivityFacts(ActivityKind.BOUNDARY_EVENT, false, false)),
            Map.entry("waitPayment", new ActivityFacts(ActivityKind.RECEIVE_TASK, false, false)),
            Map.entry("cooling", new ActivityFacts(ActivityKind.CATCH_EVENT, false, false)),
            Map.entry("dispatch", new ActivityFacts(ActivityKind.OTHER, true, false)),
            Map.entry("ship", new ActivityFacts(ActivityKind.EXTERNAL_TASK, false, false)),
            Map.entry("choose", new ActivityFacts(ActivityKind.EVENT_GATEWAY, false, false)),
            Map.entry("cancelMsg", new ActivityFacts(ActivityKind.CATCH_EVENT, false, false)),
            Map.entry("chooseTimer", new ActivityFacts(ActivityKind.CATCH_EVENT, false, false)),
            Map.entry("callSub", new ActivityFacts(ActivityKind.CALL_ACTIVITY, false, false)));

    /** A fixed target model: {@code order} v1 deployed for tenant acme only. */
    static TargetModel model() {
        return new TargetModel() {
            @Override
            public Optional<String> definitionId(String key, int version, String tenantId) {
                return "order".equals(key) && version == 1 && TENANT.equals(tenantId) ? Optional.of(TARGET_DEFINITION) : Optional.empty();
            }

            @Override
            public ActivityFacts activity(String definitionId, String activityId) {
                return ORDER_ACTIVITIES.getOrDefault(activityId, ActivityFacts.missing());
            }
        };
    }

    /**
     * The target definition (Operaton), deployed by {@code OperatonEngineStateWriterH2Test}. The sequence flows only
     * make it a valid model; migration starts activities directly.
     */
    static final String ORDER_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         targetNamespace="urn:sangam:test:flowable">
              <message id="mPayment" name="PaymentReceived"/>
              <message id="mCancel" name="CancelOrder"/>
              <process id="order" isExecutable="true" operaton:historyTimeToLive="P1D">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <userTask id="review" name="Review order"/>
                <sequenceFlow id="f2" sourceRef="review" targetRef="waitPayment"/>
                <receiveTask id="waitPayment" messageRef="mPayment"/>
                <sequenceFlow id="f3" sourceRef="waitPayment" targetRef="cooling"/>
                <intermediateCatchEvent id="cooling">
                  <timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>
                </intermediateCatchEvent>
                <sequenceFlow id="f4" sourceRef="cooling" targetRef="approve"/>
                <userTask id="approve" name="Approve order"/>
                <boundaryEvent id="approveTimeout" attachedToRef="approve">
                  <timerEventDefinition><timeDuration>PT2H</timeDuration></timerEventDefinition>
                </boundaryEvent>
                <sequenceFlow id="f5" sourceRef="approve" targetRef="dispatch"/>
                <sequenceFlow id="f5b" sourceRef="approveTimeout" targetRef="dispatch"/>
                <serviceTask id="dispatch" operaton:asyncBefore="true" operaton:expression="${true}"/>
                <sequenceFlow id="f6" sourceRef="dispatch" targetRef="ship"/>
                <serviceTask id="ship" operaton:type="external" operaton:topic="ship"/>
                <sequenceFlow id="f7" sourceRef="ship" targetRef="choose"/>
                <eventBasedGateway id="choose"/>
                <sequenceFlow id="f8" sourceRef="choose" targetRef="cancelMsg"/>
                <sequenceFlow id="f9" sourceRef="choose" targetRef="chooseTimer"/>
                <intermediateCatchEvent id="cancelMsg"><messageEventDefinition messageRef="mCancel"/></intermediateCatchEvent>
                <intermediateCatchEvent id="chooseTimer">
                  <timerEventDefinition><timeDuration>P1D</timeDuration></timerEventDefinition>
                </intermediateCatchEvent>
                <sequenceFlow id="f10" sourceRef="cancelMsg" targetRef="end"/>
                <sequenceFlow id="f11" sourceRef="chooseTimer" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;
}
