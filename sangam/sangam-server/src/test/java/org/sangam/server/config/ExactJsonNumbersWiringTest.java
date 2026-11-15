// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.rest.support.TypedVariables;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.json.JsonMapper;

/**
 * Round-5 lane w2 item 5 (decisions 138 and 333): an untyped JSON fraction in a request body reaches the engine
 * variables with every digit. The mapper under test is the one Spring Boot builds for Spring MVC's JSON converter (the
 * {@link JsonMapper} bean); before {@link ExactJsonNumbersWiring} it read {@code 12345678901234567890.123456789} as the
 * {@link Double} {@code 1.2345678901234567E19}, and {@code TypedVariables} turned that already-rounded Double into a
 * BigDecimal.
 */
class ExactJsonNumbersWiringTest {

    private static final String BODY = "{\"variables\":{\"amount\":12345678901234567890.123456789,"
            + "\"rate\":0.1,\"lines\":[{\"net\":99999999999999999.99}],\"count\":7}}";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variables(JsonMapper json) {
        Map<String, Object> body = json.readValue(BODY, Map.class);
        return TypedVariables.fromJson((Map<String, Object>) body.get("variables"));
    }

    @Test
    void anUntypedFractionInARequestBodyKeepsEveryDigit() {
        runner.withUserConfiguration(ExactJsonNumbersWiring.class).run(ctx -> {
            Map<String, Object> vars = variables(ctx.getBean(JsonMapper.class));
            assertEquals(new BigDecimal("12345678901234567890.123456789"), vars.get("amount"));
            assertEquals(new BigDecimal("0.1"), vars.get("rate"), "the decimal text, not the binary value of 0.1");
            Map<?, ?> line = (Map<?, ?>) ((List<?>) vars.get("lines")).get(0);
            assertEquals(new BigDecimal("99999999999999999.99"), line.get("net"), "nested in a list of objects too");
            assertEquals(7, vars.get("count"), "a JSON integer stays an integer");
        });
    }

    @Test
    void aSpringJacksonPropertyCannotSwitchTheExactReadingOff() {
        runner.withUserConfiguration(ExactJsonNumbersWiring.class)
                .withPropertyValues("spring.jackson.deserialization.use-big-decimal-for-floats=false")
                .run(ctx -> assertEquals(new BigDecimal("12345678901234567890.123456789"),
                        variables(ctx.getBean(JsonMapper.class)).get("amount")));
    }

    @Test
    void withoutTheWiringSpringBootsDefaultMapperReadsABinaryDouble() {
        // the defect this wiring closes: the default mapper rounds before TypedVariables sees the value
        runner.run(ctx -> {
            Map<String, Object> body = ctx.getBean(JsonMapper.class).readValue(BODY, Map.class);
            Object amount = ((Map<?, ?>) body.get("variables")).get("amount");
            assertInstanceOf(Double.class, amount);
            assertEquals(0, new BigDecimal("12345678901234567000")
                    .compareTo((BigDecimal) TypedVariables.fromJsonValue(amount)), "the last digits are gone");
        });
    }
}
