// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M-135, decision 169: the listeners of a deployed Flowable process that fire when the operator ends a migrated
 * instance on Flowable after cut-over. Evidence from the Flowable source (reference copy, {@code modules/}):
 * <ul>
 *   <li>ending an instance deletes its tasks with {@code fireTaskListener = true}: {@code flowable-engine ...
 *       ExecutionEntityManagerImpl.java:1003-1009} calls {@code TaskHelper.deleteTasksForExecution}, which calls
 *       {@code internalDeleteTask(..., true, true)} ({@code TaskHelper.java:396-404}); that runs the user task's
 *       listeners for event {@code delete} ({@code TaskHelper.java:442-445}), and
 *       {@code ListenerNotificationHelper.java:115-122} runs every listener whose event is {@code delete} or
 *       {@code all};</li>
 *   <li>the same deletion dispatches engine events ({@code TaskHelper.java:439-440},
 *       {@code ExecutionEntityManagerImpl.java:626, 645}), which reach process-scoped {@code eventListener}s;</li>
 *   <li>element and attribute names: {@code BpmnXMLConstants.java:30-32} (namespaces {@code http://flowable.org/bpmn}
 *       and {@code http://activiti.org/bpmn}), 85-86 ({@code eventListener}, {@code taskListener}), 89
 *       ({@code event}).</li>
 * </ul>
 * Parsing is StAX with DTDs and external entities off. Engine-wide listeners (configured in the Flowable engine, not
 * in the model) are not visible here; that stays an explicit gap in the cut-over report.
 */
public final class FlowableModelListeners {

    static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final Set<String> EXTENSION_NAMESPACES = Set.of("http://flowable.org/bpmn", "http://activiti.org/bpmn");

    /**
     * What the model declares.
     *
     * @param read            the model was found and parsed
     * @param problem         why it was not, else null
     * @param taskListeners   user task id to the events of its task listeners ({@code (none)} for a listener without
     *                        an event, which Flowable refuses at run time)
     * @param processListeners events of the process-scoped event listeners ({@code (all)} when none is named)
     */
    public record Found(boolean read, String problem, Map<String, List<String>> taskListeners,
                        List<String> processListeners) {
        public Found {
            taskListeners = taskListeners == null ? Map.of() : Map.copyOf(taskListeners);
            processListeners = processListeners == null ? List.of() : List.copyOf(processListeners);
        }

        static Found unread(String problem) {
            return new Found(false, problem, Map.of(), List.of());
        }

        /**
         * The canonical text of what was found (sorted task ids, events in model order, process listeners in model
         * order); its SHA-256 is {@link #fingerprint()}.
         */
        public String canonical() {
            StringBuilder b = new StringBuilder("read=").append(read);
            new java.util.TreeMap<>(taskListeners).forEach((k, v) -> b.append("\ntask:").append(k).append('=').append(v));
            return b.append("\nprocess=").append(processListeners).toString();
        }

        /** SHA-256 (hex) of {@link #canonical()}: what an operator acknowledges (decision 231). */
        public String fingerprint() {
            return org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(canonical()));
        }

        /** True when ending some instance of the definition can run a model listener. */
        public boolean firesOnEnd() {
            return !processListeners.isEmpty()
                    || taskListeners.values().stream().anyMatch(e -> e.contains("delete") || e.contains("all"));
        }

        /** True when ending a task of {@code userTaskId} runs one of its listeners. */
        public boolean deleteListener(String userTaskId) {
            List<String> events = taskListeners.getOrDefault(userTaskId, List.of());
            return events.contains("delete") || events.contains("all");
        }
    }

    private FlowableModelListeners() {
    }

    /** Reads the listeners of process {@code processKey} from the deployed BPMN resource. */
    public static Found parse(byte[] xml, String processKey, String resource) {
        if (xml == null) {
            return Found.unread("resource " + resource + " is not in ACT_GE_BYTEARRAY");
        }
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        Map<String, List<String>> tasks = new LinkedHashMap<>();
        List<String> process = new ArrayList<>();
        boolean found = false;
        try {
            XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(xml));
            Deque<String[]> stack = new ArrayDeque<>(); // {namespace, local name, id}
            int processDepth = -1;
            String userTask = null;
            int userTaskDepth = -1;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ns = r.getNamespaceURI();
                    String name = r.getLocalName();
                    String id = r.getAttributeValue(null, "id");
                    if (processDepth < 0 && BPMN.equals(ns) && "process".equals(name) && processKey.equals(id)) {
                        processDepth = stack.size();
                        found = true;
                    } else if (processDepth >= 0) {
                        if (BPMN.equals(ns) && "userTask".equals(name) && userTask == null) {
                            userTask = id;
                            userTaskDepth = stack.size();
                        } else if (EXTENSION_NAMESPACES.contains(ns) && "taskListener".equals(name) && userTask != null) {
                            String event = r.getAttributeValue(null, "event");
                            tasks.computeIfAbsent(userTask, k -> new ArrayList<>()).add(event == null ? "(none)" : event);
                        } else if (EXTENSION_NAMESPACES.contains(ns) && "eventListener".equals(name)
                                && stack.size() == processDepth + 2 && "extensionElements".equals(stack.peek()[1])) {
                            String events = r.getAttributeValue(null, "events");
                            process.add(events == null || events.isBlank() ? "(all)" : events);
                        }
                    }
                    stack.push(new String[]{ns, name, id});
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    stack.pop();
                    if (stack.size() == userTaskDepth) {
                        userTask = null;
                        userTaskDepth = -1;
                    }
                    if (stack.size() == processDepth) {
                        processDepth = -1;
                    }
                }
            }
            r.close();
        } catch (XMLStreamException | RuntimeException e) {
            return Found.unread("resource " + resource + " is not readable BPMN: " + e.getMessage());
        }
        if (!found) {
            return Found.unread("resource " + resource + " has no process " + processKey);
        }
        tasks.replaceAll((k, v) -> List.copyOf(v));
        return new Found(true, null, tasks, process);
    }
}
