// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.util.ArrayList;
import java.util.List;

import org.sangam.assembly.host.SangamProperties;

/**
 * Owner decision 317: the Kafka and JMS broker inbox (the consumers the Spring host builds in its
 * {@code BrokerInboxWiring}) is assembled on the Quarkus host only in extraction phase 5. Until then a configuration
 * that switches one on is refused at start-up by name ({@value #CODE}), never ignored: an operator who enabled a broker
 * inbox must not get a host that silently consumes nothing.
 *
 * <p>The keys are the Spring host's own ({@value #KAFKA_ENABLED}, {@value #JMS_ENABLED}; both default {@code false}).
 * They are read as {@link SangamProperties#getBoolean booleans}, so every spelling of "on" is refused and a value that
 * is not a boolean fails with the key named.
 */
public final class BrokerInboxRefusal {

    /** The name of this refusal, first in its message. */
    public static final String CODE = "BROKER_INBOX_NOT_ON_QUARKUS";

    /** The Spring host's switch of the Kafka broker inbox. */
    public static final String KAFKA_ENABLED = "sangam.integration.kafka.enabled";

    /** The Spring host's switch of the JMS broker inbox. */
    public static final String JMS_ENABLED = "sangam.integration.jms.enabled";

    private BrokerInboxRefusal() {
    }

    /** The broker inbox switches that are on in {@code properties}, Kafka first. */
    static List<String> enabled(SangamProperties properties) {
        List<String> on = new ArrayList<>();
        for (String key : List.of(KAFKA_ENABLED, JMS_ENABLED)) {
            if (properties.getBoolean(key, false)) {
                on.add(key);
            }
        }
        return on;
    }

    /**
     * Refuses the start while a broker inbox is switched on.
     *
     * @throws IllegalStateException naming {@value #CODE} and every switch that is on
     */
    public static void refuseIfEnabled(SangamProperties properties) {
        List<String> on = enabled(properties);
        if (!on.isEmpty()) {
            throw new IllegalStateException(CODE + ": start-up refused; " + String.join(" and ", on) + " set to true,"
                    + " but the Quarkus host has no Kafka or JMS broker inbox before extraction phase 5 (owner decision"
                    + " 317). Set them to false, or run the broker inbox on the standalone Spring server; a broker"
                    + " inbox is never switched on without its consumer");
        }
    }
}
