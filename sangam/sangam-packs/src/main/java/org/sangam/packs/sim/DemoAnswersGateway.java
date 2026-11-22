// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.ReconciliationRequest;

/**
 * SL-115: a simulator whose external checks answer like a cooperative external system, from DATA
 * ({@value #RESOURCE}), for demos (the server's simulator fallback). A pack never pre-fills a check result, so without
 * an answer every check fails closed; the demo's happy path therefore comes from what the simulated system answers,
 * never from a pack default.
 *
 * <p>A request that carries no directive of its own (the case variable {@code sim_<connector>}) is given the demo
 * answer of its connector as its directive, and passed to the wrapped {@link SimulatorConnectorGateway}; a case
 * directive always wins. Connectors without a demo answer are untouched.
 */
public final class DemoAnswersGateway implements ConnectorGateway {

    /** Classpath resource with one {@code <connector>=<directive>} line per simulated check. */
    public static final String RESOURCE = "org/sangam/packs/sim/demo-answers.properties";

    private final SimulatorConnectorGateway simulator;
    private final Map<String, String> answers;

    public DemoAnswersGateway(SimulatorConnectorGateway simulator, Map<String, String> answers) {
        this.simulator = Objects.requireNonNull(simulator, "simulator");
        this.answers = Map.copyOf(answers);
    }

    /** A fresh simulator with the shipped demo answers. */
    public static DemoAnswersGateway withShippedAnswers() {
        return new DemoAnswersGateway(new SimulatorConnectorGateway(), shippedAnswers());
    }

    /** The shipped demo answers, connector to directive, sorted. */
    public static Map<String, String> shippedAnswers() {
        return answers(RESOURCE);
    }

    /**
     * The answers of a classpath resource in the format of {@value #RESOURCE}, connector to directive, sorted. A line
     * with an empty directive lists its connector without a directive: the simulator answers it its own way.
     */
    public static Map<String, String> answers(String resource) {
        Properties p = new Properties();
        try (InputStream in = DemoAnswersGateway.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> out = new TreeMap<>();
        p.forEach((k, v) -> out.put(k.toString().trim(), v.toString().trim()));
        return out;
    }

    /** The simulator underneath (journal, reconciliations). */
    public SimulatorConnectorGateway simulator() {
        return simulator;
    }

    ConnectorRequest withAnswer(ConnectorRequest r) {
        Object own = r.payload().get(SimulatorConnectorGateway.DIRECTIVE);
        String answer = answers.get(r.connector());
        if (answer == null || answer.isBlank() || (own != null && !own.toString().isBlank())) {
            return r;
        }
        Map<String, Object> payload = new LinkedHashMap<>(r.payload());
        payload.put(SimulatorConnectorGateway.DIRECTIVE, answer);
        return new ConnectorRequest(r.tenantId(), r.connector(), r.operation(), r.operationId(), r.businessKey(),
                r.processInstanceId(), payload, r.payloadHash());
    }

    @Override
    public ConnectorResult query(ConnectorRequest request) {
        return simulator.query(withAnswer(request));
    }

    @Override
    public ConnectorResult submit(ConnectorRequest request) {
        return simulator.submit(withAnswer(request));
    }

    @Override
    public ConnectorResult lookup(String tenantId, String connector, String operationId) {
        return simulator.lookup(tenantId, connector, operationId);
    }

    @Override
    public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
        return simulator.cancel(tenantId, connector, operationId, reason);
    }

    @Override
    public String openReconciliation(ReconciliationRequest request) {
        return simulator.openReconciliation(request);
    }

    @Override
    public CapabilityClass capabilityOf(String connector) {
        return simulator.capabilityOf(connector);
    }
}
