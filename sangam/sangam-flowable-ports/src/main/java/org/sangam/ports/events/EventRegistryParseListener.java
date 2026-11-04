// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.bpmn.parser.EventSubscriptionDeclaration;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.sangam.ports.bpmn.SangamBpmn;

/**
 * BPMN side of the event registry (FC-02), read by a {@link BpmnParseListener} as the gap list prescribes.
 * <ul>
 *   <li>Throw/end events and send tasks with {@code sangam:eventType} and {@code sangam:channels} send the event on
 *       activity start (a {@link SendEventDelegate} execution listener; outbox in the engine transaction).</li>
 *   <li>Message start/catch/boundary events and receive tasks with {@code sangam:eventType} must use the event key
 *       as message name; the listener rejects a mismatch at deploy, because inbound events correlate by message
 *       name (see {@link EventDispatcher}).</li>
 * </ul>
 */
public class EventRegistryParseListener implements BpmnParseListener {

    @Override
    public void parseIntermediateThrowEvent(Element e, ScopeImpl scope, ActivityImpl activity) {
        attachSend(e, activity);
    }

    @Override
    public void parseEndEvent(Element e, ScopeImpl scope, ActivityImpl activity) {
        attachSend(e, activity);
    }

    @Override
    public void parseSendTask(Element e, ScopeImpl scope, ActivityImpl activity) {
        attachSend(e, activity);
    }

    @Override
    public void parseStartEvent(Element e, ScopeImpl scope, ActivityImpl activity) {
        checkCatch(e, activity);
    }

    @Override
    public void parseIntermediateCatchEvent(Element e, ScopeImpl scope, ActivityImpl activity) {
        checkCatch(e, activity);
    }

    @Override
    public void parseBoundaryEvent(Element e, ScopeImpl scope, ActivityImpl activity) {
        checkCatch(e, activity);
    }

    @Override
    public void parseReceiveTask(Element e, ScopeImpl scope, ActivityImpl activity) {
        checkCatch(e, activity);
    }

    private void attachSend(Element e, ActivityImpl activity) {
        String eventType = SangamBpmn.attr(e, SangamBpmn.EVENT_TYPE);
        if (eventType == null) {
            return;
        }
        String channels = SangamBpmn.attr(e, SangamBpmn.CHANNELS);
        if (channels == null) {
            throw new IllegalArgumentException("element " + activity.getId() + ": sangam:eventType needs sangam:channels");
        }
        activity.setProperty("sangam.eventType", eventType);
        activity.addListener(ExecutionListener.EVENTNAME_START,
                new SendEventDelegate(SangamBpmn.expression(eventType), SangamBpmn.expression(channels)));
    }

    private void checkCatch(Element e, ActivityImpl activity) {
        String eventType = SangamBpmn.attr(e, SangamBpmn.EVENT_TYPE);
        if (eventType == null) {
            return;
        }
        activity.setProperty("sangam.eventType", eventType);
        for (ScopeImpl scope : new ScopeImpl[]{activity.getEventScope() instanceof ScopeImpl s ? s : null, activity.getFlowScope(), activity}) {
            if (scope == null) {
                continue;
            }
            EventSubscriptionDeclaration d = EventSubscriptionDeclaration.getDeclarationsForScope(scope).get(activity.getId());
            if (d != null && "message".equals(d.getEventType()) && d.getUnresolvedEventName() != null
                    && !eventType.equals(d.getUnresolvedEventName())) {
                throw new IllegalArgumentException("element " + activity.getId() + ": message name " + d.getUnresolvedEventName()
                        + " must equal sangam:eventType " + eventType + " (events correlate by message name)");
            }
        }
    }
}
