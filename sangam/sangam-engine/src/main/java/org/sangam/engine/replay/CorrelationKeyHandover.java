// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import java.util.function.Supplier;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.impl.ServiceImpl;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.engine.tx.CommandState;

/**
 * Decision 332: an adapter that is called with a correlation key hands that key to the message capture point
 * ({@link CapturePoints#messageEventHandler()}) through the command state. Operaton does not pass the business key of
 * a correlation to the event handler of a waiting execution, so without this the captured
 * {@code MESSAGE_DELIVERED} had no {@code correlationKey}.
 *
 * <p>The key is on the command state only while the adapter's correlation runs, and the capture point uses it only for
 * a delivery made by the correlation command the adapter itself issued ({@link CommandState#deliveredCorrelationKey}).
 * A correlation by a delegate, a listener, the inbox or any other caller hands nothing over, so its captured key stays
 * null. On an engine without replay capture (every production engine) nothing reads the key.
 */
public final class CorrelationKeyHandover {

    private CorrelationKeyHandover() {
    }

    /**
     * Runs {@code correlation} inside the engine command {@code cc} with {@code correlationKey} handed over.
     * For an adapter that already runs in a command.
     */
    public static <T> T within(CommandContext cc, String correlationKey, Supplier<T> correlation) {
        if (correlationKey == null) {
            return correlation.get();
        }
        CommandState state = CommandState.of(cc);
        CommandState.CorrelationKey previous = state.handOverCorrelationKey(correlationKey);
        try {
            return correlation.get();
        } finally {
            state.restoreCorrelationKey(previous);
        }
    }

    /**
     * Runs {@code correlation} (calls on {@code runtime}) with {@code correlationKey} handed over, for an adapter that
     * is not in an engine command: one command is opened around the correlation so that both share a command state.
     * The correlation's own command reuses that command's context, transaction and flush, as it does under any outer
     * command. Without a key, or on a {@link RuntimeService} that is not the engine's own, the correlation runs as is.
     */
    public static <T> T call(RuntimeService runtime, String correlationKey, Supplier<T> correlation) {
        if (correlationKey == null || !(runtime instanceof ServiceImpl service) || service.getCommandExecutor() == null) {
            return correlation.get();
        }
        return service.getCommandExecutor().execute(new WithCorrelationKey<>(correlationKey, correlation));
    }

    /** A named command (not a lambda), so command spans and logs show a bounded name. */
    static final class WithCorrelationKey<T> implements Command<T> {
        private final String correlationKey;
        private final Supplier<T> correlation;

        WithCorrelationKey(String correlationKey, Supplier<T> correlation) {
            this.correlationKey = correlationKey;
            this.correlation = correlation;
        }

        @Override
        public T execute(CommandContext cc) {
            return within(cc, correlationKey, correlation);
        }
    }
}