package org.sangam.spike.it.support;

import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.flowable.FlowableEngineAdapter;
import org.sangam.spike.operaton.OperatonEngineAdapter;

/** The two candidate engines. Each experiment has one concrete test class per constant. */
public enum EngineKind {
    FLOWABLE(Corpus.FLOWABLE, "flowable-8.0.0"),
    OPERATON(Corpus.OPERATON, "operaton-2.2.0");

    private final String corpusName;
    private final String label;

    EngineKind(String corpusName, String label) {
        this.corpusName = corpusName;
        this.label = label;
    }

    public String corpusName() {
        return corpusName;
    }

    /** Key used in the result JSON files. */
    public String label() {
        return label;
    }

    public EngineAdapter create(String jdbcUrl, String user, String password) {
        return switch (this) {
            case FLOWABLE -> FlowableEngineAdapter.create(jdbcUrl, user, password);
            case OPERATON -> new OperatonEngineAdapter(jdbcUrl, user, password);
        };
    }
}
