// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** National / cross-cell read model (SCALE-ARCHITECTURE.md section 8). Holds counts and ids only, no personal data. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class RollupApplication {

    public static void main(String[] args) {
        SpringApplication.run(RollupApplication.class, args);
    }
}
