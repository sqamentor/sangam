// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;

import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.server.bridge.CellParkedReplayInboxFilter;
import org.sangam.server.bridge.CellParkedReplayTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SL-142 / owner decision 61: replay of parked inbound messages between cells. On a cell ({@code sangam.cell.id}):
 * <ul>
 *   <li>the transport {@link CellParkedReplayTransport} over {@code sangam.cell.gateway-url} with the cell's service
 *       token, only when the gateway URL is set (as the {@code xcell:*} publisher in {@link CellWiring}); without it
 *       the replay route answers 503 REPLAY_NOT_CONFIGURED;</li>
 *   <li>the receive path {@link CellParkedReplayInboxFilter} on {@code POST /api/v1/messages/replay}, ordered with the
 *       cross-cell filter, after {@link org.sangam.server.bridge.CellTenantStateFilter}.</li>
 * </ul>
 * The operator routes are {@link org.sangam.server.web.CellParkedInboundController}. CellWiring is not changed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.cell.id")
public class CellParkedInboundWiring {

    /** application.yaml always defines the key ({@code ${SANGAM_GATEWAY_URL:}}), so only a non-blank value counts. */
    @Bean
    @ConditionalOnExpression("!'${sangam.cell.gateway-url:}'.trim().isEmpty()")
    public PoisonInbound.ParkedReplay sangamCellParkedReplay(Environment env) {
        return new CellParkedReplayTransport(URI.create(env.getRequiredProperty("sangam.cell.gateway-url").trim()),
                env.getProperty("sangam.cell.service-token"), env.getRequiredProperty("sangam.cell.id"));
    }

    @Bean
    @ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<CellParkedReplayInboxFilter> sangamCellParkedReplayFilter(InboxService inbox) {
        FilterRegistrationBean<CellParkedReplayInboxFilter> reg =
                new FilterRegistrationBean<>(new CellParkedReplayInboxFilter(inbox));
        reg.addUrlPatterns(CellParkedReplayInboxFilter.PATH);
        reg.setOrder(CellWiring.XCELL_FILTER_ORDER);
        reg.setName("sangamCellParkedReplayFilter");
        return reg;
    }
}
