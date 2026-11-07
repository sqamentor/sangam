// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Plugin SPI ({@link java.util.ServiceLoader}): FEEL functions a drop-in plugin adds to every DMN decision. The server
 * adapts them to Operaton's {@code FeelCustomFunctionProvider}. A function must be pure (no I/O, no clock): decisions
 * are re-evaluated during audit replay.
 */
public interface DmnFunctionProvider {

    /** Function name (as called in FEEL) to function. */
    Map<String, DmnFunction> functions();

    /**
     * One FEEL function.
     *
     * @param params  parameter names in call order
     * @param varargs true when the last parameter takes any number of values
     * @param body    the function; receives the arguments in parameter order
     */
    record DmnFunction(List<String> params, boolean varargs, Function<List<Object>, Object> body) {
        public DmnFunction {
            params = params == null ? List.of() : List.copyOf(params);
            Objects.requireNonNull(body, "body");
        }

        public static DmnFunction of(Function<List<Object>, Object> body, String... params) {
            return new DmnFunction(List.of(params), false, body);
        }
    }
}
