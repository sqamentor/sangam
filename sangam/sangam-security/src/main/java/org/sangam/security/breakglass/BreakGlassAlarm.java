// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.breakglass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import org.sangam.core.security.LogSafe;

/**
 * The alarm raised on every use of a break-glass token (M-084, AJ-05 step 7: "every use alerts the CISO"). The audit
 * event {@code BREAK_GLASS_USED} (payload {@code alarm=true}) is the durable record; this callback is the immediate
 * signal. The default writes an ERROR log line on the dedicated logger {@value #LOGGER}, which the log pipeline routes to
 * the security monitoring (SIEM) of the deployment; a deployment may bind its own (a notification to the CISO seat).
 * An alarm must never throw into the authentication.
 */
@FunctionalInterface
public interface BreakGlassAlarm {

    String LOGGER = "org.sangam.security.BREAK_GLASS";

    /** {@code details}: grant id, token class, holder, token id, client IP, use count; never a secret. */
    void raised(String tenantId, Map<String, Object> details);

    /** The default: one ERROR line on {@value #LOGGER}. */
    BreakGlassAlarm LOG = new BreakGlassAlarm() {
        private final Logger log = LoggerFactory.getLogger(LOGGER);

        @Override
        public void raised(String tenantId, Map<String, Object> details) {
            log.error("BREAK-GLASS ACCESS USED in tenant {}: {}", LogSafe.text(tenantId), LogSafe.text(details));
        }
    };
}
