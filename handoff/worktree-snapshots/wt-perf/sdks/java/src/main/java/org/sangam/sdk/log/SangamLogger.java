// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.log;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Structured logging hook. Every SDK log line is an {@code event} name (stable, machine-readable, for example
 * {@code work.completed}) plus key/value {@code fields} (workId, topic, operationId, attemptId, lockVersion,
 * businessKey, ...). Plug in SLF4J, Log4j, JSON logs or metrics by implementing this interface.
 */
@FunctionalInterface
public interface SangamLogger {

    enum Level { DEBUG, INFO, WARN, ERROR }

    void log(Level level, String event, String message, Map<String, ?> fields, Throwable error);

    default void debug(String event, String message, Map<String, ?> fields) { log(Level.DEBUG, event, message, fields, null); }
    default void info(String event, String message, Map<String, ?> fields) { log(Level.INFO, event, message, fields, null); }
    default void warn(String event, String message, Map<String, ?> fields, Throwable e) { log(Level.WARN, event, message, fields, e); }
    default void error(String event, String message, Map<String, ?> fields, Throwable e) { log(Level.ERROR, event, message, fields, e); }

    /** Discards everything. */
    static SangamLogger noop() {
        return (level, event, message, fields, error) -> { };
    }

    /** Default: {@link System.Logger} named {@code org.sangam.sdk}, rendering fields as {@code key=value}. */
    static SangamLogger system() {
        System.Logger logger = System.getLogger("org.sangam.sdk");
        return (level, event, message, fields, error) -> {
            System.Logger.Level l = switch (level) {
                case DEBUG -> System.Logger.Level.DEBUG;
                case INFO -> System.Logger.Level.INFO;
                case WARN -> System.Logger.Level.WARNING;
                case ERROR -> System.Logger.Level.ERROR;
            };
            if (!logger.isLoggable(l)) return;
            String kv = fields == null || fields.isEmpty() ? "" : " " + fields.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
            String line = "event=" + event + " msg=\"" + message + "\"" + kv;
            if (error != null) logger.log(l, line, error);
            else logger.log(l, line);
        };
    }
}
