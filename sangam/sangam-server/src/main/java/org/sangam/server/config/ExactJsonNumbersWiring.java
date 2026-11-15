// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;

/**
 * Owner decision 138: decimal inputs are read from their decimal text, never through a binary float. Spring MVC reads
 * request bodies with the server's Jackson 3 mapper; by default an untyped JSON number with a fraction (a variable in
 * {@code variables}, an event's {@code data}, a form answer) became a {@link Double} there, so a value with more than
 * 17 significant digits had already lost digits before {@link org.sangam.rest.support.TypedVariables} saw it. With
 * {@code USE_BIG_DECIMAL_FOR_FLOATS} the mapper keeps the exact text as a {@link java.math.BigDecimal}. A customizer
 * without an order runs after Spring Boot's own ({@code spring.jackson.*}), so a property cannot switch it off.
 */
@Configuration(proxyBeanMethods = false)
public class ExactJsonNumbersWiring {

    @Bean
    public JsonMapperBuilderCustomizer sangamExactJsonFractions() {
        return builder -> builder.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
