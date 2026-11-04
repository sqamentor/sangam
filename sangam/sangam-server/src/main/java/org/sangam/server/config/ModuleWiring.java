// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.springframework.context.annotation.Configuration;

/**
 * Index of the module wiring (ARCHITECTURE section 3: "The integrator wires it in sangam-server"). Every default
 * adapter of {@code org.sangam.rest.config.SangamRestAutoConfiguration} is {@code @ConditionalOnMissingBean} and the
 * auto-configuration runs after this user configuration, so a bean of a port type declared in one of these classes
 * replaces the REST default. Each class is gated by {@code sangam.modules.<module>.enabled} (default {@code true}).
 * <ul>
 *   <li>{@link EngineWiring}: sangam-engine applied to the Operaton starter's single engine (plugin order:
 *       starter defaults, Sangam, security, integration, ports); {@code EngineAdapter}/{@code SangamEngineAdapter},
 *       {@code SangamClock}, Micrometer {@code EngineMetrics}.</li>
 *   <li>{@link SecurityWiring}: {@code SangamSecurity}, its engine plugin, API-token and identity-resolution filters in
 *       the server's single filter chain; REST {@code PermissionPort}, {@code AdminPort}, {@code IdentityAdminPort};
 *       core {@code IdentityExtrasPort}.</li>
 *   <li>{@link IntegrationWiring}: outbox writer/relay, inbox, ledger and A-10 expiry policy, lifecycle (VI.5),
 *       reconciliation, maintenance, connectors, the Operaton bridge and engine plugin, back-pressure, the packs'
 *       connector gateway; core {@code UniqueStartPort}.</li>
 *   <li>{@code PortsWiring} / {@code PacksWiring}: sangam-flowable-ports and sangam-packs.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ModuleWiring {
}
