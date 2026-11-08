// SPDX-License-Identifier: Apache-2.0
package org.sangam.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Context-load smoke test on the h2 profile (no Docker). Part of the final testing phase; not run during Stage C coding. */
@SpringBootTest
@ActiveProfiles("h2")
@org.springframework.test.annotation.DirtiesContext // D-10: one live engine per JVM, so no cached context outlives the class
class SangamServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
