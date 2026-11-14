// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Import;

/**
 * Decision 187: maps {@link HealthHooksFallbackController} only when no {@link HealthHooksPort} bean exists, so the
 * health routes answer 501 CAPABILITY_NOT_INSTALLED while the optional module is off. Not conditional on any health
 * class: it must work exactly when sangam-connectors-health is missing.
 */
@AutoConfiguration(after = {SangamRestAutoConfiguration.class, ConnectorsHealthRestConfiguration.class})
@ConditionalOnMissingBean(HealthHooksPort.class)
@Import(HealthHooksFallbackController.class)
public class HealthHooksFallbackConfiguration {
}
