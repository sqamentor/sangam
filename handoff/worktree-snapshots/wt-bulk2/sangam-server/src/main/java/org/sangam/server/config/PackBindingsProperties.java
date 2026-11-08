// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sangam.packs.*}: which sangam-integration connector serves each pack connector id (ifms.budget,
 * bank.statement, docs.issue, ...), with a declarative payload mapper. Example:
 *
 * <pre>
 * sangam.packs.bindings.ifms.budget.connector=ifms
 * sangam.packs.bindings.ifms.budget.operation=checkBudget
 * sangam.packs.bindings.ifms.budget.mapping.headOfAccount=budgetHead     # target field = source field (dotted path)
 * sangam.packs.bindings.ifms.budget.constants.channel=SANGAM
 * sangam.packs.bindings.ifms.budget.pass-through=false                   # only mapped and constant fields are sent
 * </pre>
 *
 * A pack connector without a binding uses the packs' simulator gateway when {@code simulator-fallback} is true (the
 * default only on the h2 profile); otherwise the call fails with {@code CONNECTOR_NOT_CONFIGURED}.
 */
@ConfigurationProperties("sangam.packs")
public class PackBindingsProperties {

    private Map<String, BindingSpec> bindings = new LinkedHashMap<>();

    /** null = on for the h2 profile only. */
    private Boolean simulatorFallback;

    public Map<String, BindingSpec> getBindings() {
        return bindings;
    }

    public void setBindings(Map<String, BindingSpec> bindings) {
        this.bindings = bindings;
    }

    public Boolean getSimulatorFallback() {
        return simulatorFallback;
    }

    public void setSimulatorFallback(Boolean simulatorFallback) {
        this.simulatorFallback = simulatorFallback;
    }

    public static class BindingSpec {
        private String connector;
        private String operation;
        private boolean passThrough = true;
        private Map<String, String> mapping = new LinkedHashMap<>();
        private Map<String, Object> constants = new LinkedHashMap<>();

        public String getConnector() {
            return connector;
        }

        public void setConnector(String connector) {
            this.connector = connector;
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public boolean isPassThrough() {
            return passThrough;
        }

        public void setPassThrough(boolean passThrough) {
            this.passThrough = passThrough;
        }

        public Map<String, String> getMapping() {
            return mapping;
        }

        public void setMapping(Map<String, String> mapping) {
            this.mapping = mapping;
        }

        public Map<String, Object> getConstants() {
            return constants;
        }

        public void setConstants(Map<String, Object> constants) {
            this.constants = constants;
        }

        /** The payload mapper for this binding: pass-through copy, then target ← source renames, then constants. */
        public UnaryOperator<Map<String, Object>> mapper() {
            Map<String, String> m = Map.copyOf(mapping);
            Map<String, Object> k = Map.copyOf(constants);
            boolean copy = passThrough;
            if (copy && m.isEmpty() && k.isEmpty()) {
                return UnaryOperator.identity();
            }
            return in -> {
                Map<String, Object> out = copy && in != null ? new LinkedHashMap<>(in) : new LinkedHashMap<>();
                m.forEach((target, source) -> {
                    Object v = path(in, source);
                    if (v != null) {
                        out.put(target, v);
                    }
                });
                out.putAll(k);
                return out;
            };
        }

        @SuppressWarnings("unchecked")
        private static Object path(Map<String, Object> in, String dotted) {
            Object cur = in;
            for (String part : dotted.split("\\.")) {
                if (!(cur instanceof Map<?, ?> map)) {
                    return null;
                }
                cur = ((Map<String, Object>) map).get(part);
            }
            return cur;
        }
    }
}
