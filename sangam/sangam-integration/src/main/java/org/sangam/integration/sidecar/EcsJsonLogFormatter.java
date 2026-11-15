// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * A {@code java.util.logging} formatter that writes one ECS JSON object per line (owner decision 324), with the field
 * layout of the Spring Boot {@code ecs} console format used by the server and the gateway:
 * {@code @timestamp}, {@code log.level}, {@code log.logger}, {@code process.pid}, {@code process.thread.name},
 * {@code service.name}, {@code message}, {@code error.type}, {@code error.message}, {@code error.stack_trace} and
 * {@code ecs.version}. Every value is escaped: CR, LF, every other control character, U+2028, U+2029 and an unpaired
 * surrogate become a {@code \}{@code uXXXX} escape, so text that reaches a log message from a request can never end
 * the line and start a second, forged log entry. JDK only, no dependency.
 */
public final class EcsJsonLogFormatter extends Formatter {

    /** The ECS version the field layout follows; the same value Spring Boot writes. */
    public static final String ECS_VERSION = "8.11";

    private final String serviceName;
    private final long pid;

    /** @param serviceName the {@code service.name} of every line, for example {@code sangam-ocr-sidecar} */
    public EcsJsonLogFormatter(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("the service name is blank");
        }
        this.serviceName = serviceName;
        this.pid = ProcessHandle.current().pid();
    }

    @Override
    public String format(LogRecord record) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"@timestamp\":");
        Instant at = record.getInstant();
        string(b, (at == null ? Instant.now() : at).toString());
        b.append(",\"log\":{\"level\":");
        string(b, level(record.getLevel()));
        b.append(",\"logger\":");
        string(b, record.getLoggerName() == null ? "" : record.getLoggerName());
        b.append("},\"process\":{\"pid\":").append(pid).append(",\"thread\":{\"name\":");
        // the handler formats on the logging thread (a synchronous stream handler), so this is the caller's thread
        string(b, Thread.currentThread().getName());
        b.append("}},\"service\":{\"name\":");
        string(b, serviceName);
        b.append("},\"message\":");
        string(b, formatMessage(record));
        Throwable t = record.getThrown();
        if (t != null) {
            b.append(",\"error\":{\"type\":");
            string(b, t.getClass().getName());
            b.append(",\"message\":");
            string(b, t.getMessage() == null ? "" : t.getMessage());
            b.append(",\"stack_trace\":");
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            string(b, trace.toString());
            b.append('}');
        }
        b.append(",\"ecs\":{\"version\":\"").append(ECS_VERSION).append("\"}}").append('\n');
        return b.toString();
    }

    /** The ECS level name of a {@code java.util.logging} level (the names Spring Boot writes). */
    static String level(Level level) {
        int v = level == null ? Level.INFO.intValue() : level.intValue();
        if (v >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (v >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (v >= Level.INFO.intValue()) {
            return "INFO";
        }
        if (v >= Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }

    /** Appends {@code s} as one JSON string; nothing in the output can break the line. */
    static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F || c == ' ' || c == ' ' || unpairedSurrogate(s, i)) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    private static boolean unpairedSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        }
        return false;
    }
}
