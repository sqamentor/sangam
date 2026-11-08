// SPDX-License-Identifier: Apache-2.0
package org.sangam.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Sangam routing edge (SCALE-ARCHITECTURE.md section 5.5). Stateless; scale horizontally behind an L4 balancer. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
