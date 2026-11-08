// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SL-43-DEADLOCK with the production {@code override-ttl=30s}: the first round meets a cold override cache (the same
 * situation as every cache expiry under load), later rounds the cached path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class QuotaPoolDeadlockTtl30sH2Test extends QuotaPoolDeadlockH2Support {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        database(r, "sl43ttl30", "30s");
    }
}
