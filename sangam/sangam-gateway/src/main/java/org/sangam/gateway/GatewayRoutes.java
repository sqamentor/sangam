// SPDX-License-Identifier: Apache-2.0
package org.sangam.gateway;

import java.time.Duration;

import io.netty.channel.ChannelOption;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** Catch-all route to {@link CellProxy} (lowest precedence) and the pooled upstream client. */
@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public RouterFunction<ServerResponse> proxyRoute(CellProxy proxy) {
        return RouterFunctions.route(RequestPredicates.path("/actuator/**").negate(), proxy::handle);
    }

    @Bean
    @Primary
    public WebClient.Builder gatewayWebClientBuilder(GatewayProperties props) {
        ConnectionProvider pool = ConnectionProvider.builder("sangam-cells")
                .maxConnections(2000)
                .pendingAcquireMaxCount(10_000)
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
        HttpClient http = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.connectTimeout().toMillis())
                .responseTimeout(props.responseTimeout());
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024));
    }
}
