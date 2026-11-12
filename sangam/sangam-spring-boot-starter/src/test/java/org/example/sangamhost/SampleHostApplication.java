// SPDX-License-Identifier: Apache-2.0
package org.example.sangamhost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A sample host application (M-014): an ordinary Spring Boot 4.1 application in its own package with
 * sangam-spring-boot-starter on the class path and nothing Sangam-specific in its code. Sangam arrives through the
 * starter's auto-configuration; the host configures a data source (its own JDBC driver) and enables the modules it
 * uses, for example {@code sangam.modules.billing.enabled=true}.
 */
@SpringBootApplication
public class SampleHostApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleHostApplication.class, args);
    }
}
