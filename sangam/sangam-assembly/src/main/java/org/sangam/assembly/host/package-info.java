// SPDX-License-Identifier: Apache-2.0
/**
 * The host SPI (M-144, owner decision 81; design note {@code sangam/docs/M-015-QUARKUS-EXTENSION-DESIGN.md} section 10).
 * Spring is today's host and any later framework (Quarkus, Micronaut, Helidon, Jakarta EE, plain Java) is a thin
 * adapter over these seams, with the core, the domain modules and the assembly untouched:
 *
 * <ul>
 *   <li>lifecycle: {@link org.sangam.assembly.host.SangamRuntime}, {@link org.sangam.assembly.host.Lifecycle};</li>
 *   <li>transaction and connection binding: {@link org.sangam.assembly.host.ConnectionBinding};</li>
 *   <li>HTTP binding (convenience): {@link org.sangam.assembly.host.HttpBinding};</li>
 *   <li>security-context binding: {@link org.sangam.assembly.host.SecurityContextBinding};</li>
 *   <li>config source: {@link org.sangam.assembly.host.SangamProperties},
 *       {@link org.sangam.assembly.host.ModuleSwitch};</li>
 *   <li>scheduling: {@link org.sangam.assembly.host.HostScheduler};</li>
 *   <li>health (convenience): {@link org.sangam.assembly.host.HealthCheck}.</li>
 * </ul>
 *
 * <p>A host collects its seams in {@link org.sangam.assembly.host.HostSeams}; the assembly resolves the
 * {@link org.sangam.assembly.host.CapabilitySet} once from the catalogue
 * {@link org.sangam.assembly.host.Capabilities}. A missing required capability refuses start-up or the affected
 * operation with {@link org.sangam.assembly.host.CapabilityMissingException} ({@code CAPABILITY_MISSING}); a host may
 * not add a default for it. A host contains no business rule, permission check, money arithmetic, audit write,
 * idempotency or dedupe logic, retry or ordering policy, or SQL. Every host passes the host conformance kit
 * ({@code sangam-host-conformance}). This package names no host framework ({@code HostFrameworkBoundaryTest}).
 */
package org.sangam.assembly.host;
