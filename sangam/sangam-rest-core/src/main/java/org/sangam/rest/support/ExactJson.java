// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Owner decisions 138 and 381(4): the Jackson 2 mappers whose output reaches engine variables
 * ({@link TypedVariables}) read a JSON number with a fraction as a {@link java.math.BigDecimal} from its text, never as
 * a binary {@code Double}; {@code TypedVariables} refuses a Double by name. Stored JSON (batch parameters, power-table
 * test cases, package DMN tests, event data) is read through these mappers.
 */
public final class ExactJson {

    private ExactJson() {
    }

    /** A plain mapper that reads fractions exactly. */
    public static ObjectMapper mapper() {
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /**
     * The stored-document mapper: fractions exact, java.time as ISO text, unknown properties ignored (a stored
     * document may carry fields a newer server wrote).
     */
    public static ObjectMapper documents() {
        return mapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
