// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SL-43-DEADLOCK with {@code override-ttl=0s} (the acceptance profile): overrides are read on every check. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class QuotaPoolDeadlockTtl0H2Test extends QuotaPoolDeadlockH2Support {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        database(r, "sl43ttl0", "0s");
    }
}
