// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * The console logging of a sidecar's {@code main} (owner decision 324): structured (ECS JSON, {@link EcsJsonLogFormatter})
 * on standard output, one object per line, in every profile. {@link #install} replaces the handlers of the
 * {@code java.util.logging} root logger, which is also the back end of {@link System.Logger} and of the libraries that
 * log through {@code java.util.logging} (veraPDF, and PDFBox through commons-logging), and makes an exception that
 * escapes a thread (also {@code main} at start-up) one JSON line instead of a plain stack trace. A sidecar writes no
 * other console line.
 */
public final class SidecarLogging {

    private SidecarLogging() {
    }

    /** Installs the JSON console logging on standard output; call first in {@code main}. */
    public static void install(String serviceName) {
        install(serviceName, System.out);
    }

    /** Installs the JSON console logging on {@code out}; returns the installed handler. */
    public static Handler install(String serviceName, OutputStream out) {
        EcsJsonLogFormatter formatter = new EcsJsonLogFormatter(serviceName);
        LogManager.getLogManager().reset();
        Handler handler = new LineHandler(out, formatter);
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        root.addHandler(handler);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> root.log(Level.SEVERE,
                "uncaught exception in thread " + thread.getName(), error));
        return handler;
    }

    /** A stream handler that flushes every line and never closes the console stream. */
    private static final class LineHandler extends StreamHandler {

        LineHandler(OutputStream out, EcsJsonLogFormatter formatter) {
            super(out, formatter);
            try {
                setEncoding(StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("UTF-8 is not supported", e);
            }
            setLevel(Level.ALL);
        }

        @Override
        public synchronized void publish(LogRecord record) {
            super.publish(record);
            flush();
        }

        @Override
        public synchronized void close() {
            flush();
        }
    }
}
