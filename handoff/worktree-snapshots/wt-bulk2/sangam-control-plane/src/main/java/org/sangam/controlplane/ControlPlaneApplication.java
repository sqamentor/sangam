// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Sangam control plane (ADR-D-21, SCALE-ARCHITECTURE.md section 5). Never on a cell's request path. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
