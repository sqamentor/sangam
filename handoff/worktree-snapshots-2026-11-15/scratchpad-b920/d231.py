import os
import sys

os.chdir(sys.argv[1])
s = ""


def rep(o, n):
    global s
    assert s.count(o) == 1, o[:80]
    s = s.replace(o, n)


p = 'StateClassifier.java'
s = open(p, encoding='utf-8').read()
rep(''' * {@value #LONG_TEXT_VARIABLE}, {@value #JSON_VARIABLE}. WARN codes: {@value #DOUBLE_VARIABLE},
 * {@value #UUID_AS_STRING}, {@value #REPEATING_TIMER}, {@value #OVERDUE_TIMER}, {@value #SOURCE_DELETE_LISTENER},
 * {@value #SOURCE_EVENT_LISTENER}, {@value #SOURCE_MODEL_UNREAD}.''',
    ''' * {@value #LONG_TEXT_VARIABLE}, {@value #JSON_VARIABLE}, {@value #SOURCE_MODEL_UNREAD}, and
 * {@value #SOURCE_DELETE_LISTENER} and {@value #SOURCE_EVENT_LISTENER} unless acknowledged. WARN codes:
 * {@value #DOUBLE_VARIABLE}, {@value #UUID_AS_STRING}, {@value #REPEATING_TIMER}, {@value #OVERDUE_TIMER}, and
 * {@value #SOURCE_DELETE_LISTENER} and {@value #SOURCE_EVENT_LISTENER} of an acknowledged definition.''')
rep(''' * The source listener WARNs describe what ending the instance on Flowable after cut-over fires
 * ({@link FlowableModelListeners}).
 */''', ''' * The source listener findings describe what ending the instance on Flowable after cut-over fires
 * ({@link FlowableModelListeners}).
 *
 * <p>Owner decision 231: those listener findings REJECT by default. An operator may acknowledge them per source process
 * definition ({@link FlowableMigrationPipeline#acknowledgeListeners}); the acknowledgement is bound to the
 * {@link FlowableModelListeners.Found#fingerprint() fingerprint} of the listeners seen, and only an acknowledgement
 * with the same fingerprint turns them into WARN. A model that cannot be read always rejects: nothing can be
 * acknowledged about listeners nobody has seen.
 */''')
rep('''    private final int defaultRetries;

    /**
     * @param defaultRetries the source engine's initial job retries (Flowable default 3); a job below it has failed
     *                       at least once, so an external effect may already have happened
     */
    public StateClassifier(List<DefinitionMapping> mappings, TargetModel model, int defaultRetries) {
        this.mappings = List.copyOf(mappings);
        this.model = Objects.requireNonNull(model, "model");
        this.defaultRetries = defaultRetries;
    }''', '''    private final int defaultRetries;
    private final Map<String, String> acknowledged;

    /** Without any listener acknowledgement (decision 231 default: source listeners reject). */
    public StateClassifier(List<DefinitionMapping> mappings, TargetModel model, int defaultRetries) {
        this(mappings, model, defaultRetries, Map.of());
    }

    /**
     * @param defaultRetries the source engine's initial job retries (Flowable default 3); a job below it has failed
     *                       at least once, so an external effect may already have happened
     * @param acknowledged   Flowable process definition id to the listener fingerprint an operator acknowledged
     */
    public StateClassifier(List<DefinitionMapping> mappings, TargetModel model, int defaultRetries,
                           Map<String, String> acknowledged) {
        this.mappings = List.copyOf(mappings);
        this.model = Objects.requireNonNull(model, "model");
        this.defaultRetries = defaultRetries;
        this.acknowledged = Map.copyOf(acknowledged);
    }''')
start = s.index('    /**\n     * What ending this instance on Flowable after cut-over fires (decision 169)')
end = s.index('    private List<VariableValue> variables(')
s = s[:start] + '''    /**
     * What ending this instance on Flowable after cut-over fires (decisions 169 and 231): the delete or all task
     * listeners of each open user task and the process-scoped event listeners. REJECT unless an operator acknowledged
     * this definition's listeners with the same fingerprint, then WARN; an unread model always rejects.
     */
    private void sourceListeners(Instance in, List<Finding> f) {
        FlowableModelListeners.Found l = in.listeners();
        if (!l.read()) {
            reject(f, SOURCE_MODEL_UNREAD, null, "the deployed Flowable model was not read (" + l.problem() + "); the "
                    + "listeners that ending the instance on Flowable fires are unknown and cannot be acknowledged");
            return;
        }
        String ack = in.definitionId() == null ? null : acknowledged.get(in.definitionId());
        boolean ok = ack != null && ack.equals(l.fingerprint());
        Severity sev = ok ? Severity.WARN : Severity.REJECT;
        String tail = ok ? " (acknowledged for definition " + in.definitionId() + ", decision 231)"
                : ack == null ? "; acknowledge definition " + in.definitionId() + " to migrate it (decision 231)"
                : "; the acknowledgement of definition " + in.definitionId() + " was for other listeners: acknowledge again";
        Set<String> seen = new HashSet<>();
        for (Task t : in.tasks()) {
            if (t.taskDefinitionKey() != null && l.deleteListener(t.taskDefinitionKey()) && seen.add(t.taskDefinitionKey())) {
                f.add(new Finding(sev, SOURCE_DELETE_LISTENER, t.taskDefinitionKey(), "user task " + t.taskDefinitionKey()
                        + " declares task listeners for " + l.taskListeners().get(t.taskDefinitionKey()) + "; ending the "
                        + "instance on Flowable deletes the task and runs them" + tail));
            }
        }
        if (!l.processListeners().isEmpty()) {
            f.add(new Finding(sev, SOURCE_EVENT_LISTENER, null, "the process declares event listeners " + l.processListeners()
                    + "; ending the instance on Flowable dispatches events to them" + tail));
        }
    }

''' + s[end:]
with open(p, 'w', encoding='utf-8', newline='') as fh:
    fh.write(s)

p = 'FlowableModelListeners.java'
s = open(p, encoding='utf-8').read()
rep('''        /** True when ending a task of {@code userTaskId} runs one of its listeners. */''', '''        /**
         * The canonical text of what was found (sorted task ids, events in model order, process listeners in model
         * order); its SHA-256 is {@link #fingerprint()}.
         */
        public String canonical() {
            StringBuilder b = new StringBuilder("read=").append(read);
            new java.util.TreeMap<>(taskListeners).forEach((k, v) -> b.append("\\ntask:").append(k).append('=').append(v));
            return b.append("\\nprocess=").append(processListeners).toString();
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

        /** True when ending a task of {@code userTaskId} runs one of its listeners. */''')
with open(p, 'w', encoding='utf-8', newline='') as fh:
    fh.write(s)
print('ok')
