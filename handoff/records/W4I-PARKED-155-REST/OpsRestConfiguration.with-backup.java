// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** REST exposure of sangam-ops; active when the module is on the class path and sangam-server bound {@link OpsApi}. */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.ops.search.GlobalSearch")
public class OpsRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.rest.modules.ops.OpsApi")
    @Import({OpsController.class, OpsUiController.class})
    static class Controllers {
    }

    /** SL-44 bulk ingest; active when sangam-server bound {@link org.sangam.ops.bulk.BulkIngest}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.ops.bulk.BulkIngest")
    @Import({BulkController.class, BulkProblemAdvice.class})
    static class Bulk {
    }

    /**
     * Owner decision 155 (M-102): the backup console routes exist whenever sangam-backup is on the class path; they answer
     * 501 {@code BACKUP_CONSOLE_NOT_CONFIGURED} until {@code sangam.backup.console.kubernetes.enabled=true} creates the
     * console (off by default; the chart grants the namespaced Role only then).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.sangam.backup.BackupConsole")
    @Import(BackupController.class)
    static class Backup {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(name = "sangam.backup.console.kubernetes.enabled", havingValue = "true")
        org.sangam.backup.BackupConsole sangamBackupConsole(Environment env) throws java.io.IOException {
            String namespace = env.getProperty("sangam.backup.console.kubernetes.namespace");
            if (namespace == null || namespace.isBlank()) {
                namespace = org.sangam.kube.KubeHttp.podNamespace();
            }
            String selector = env.getProperty("sangam.backup.console.kubernetes.selector", "app.kubernetes.io/component=backup");
            return new org.sangam.backup.BackupConsole(new org.sangam.backup.KubernetesBackupTargets(
                    org.sangam.kube.KubeHttp.inCluster("sangam-server"), namespace, selector), java.time.Clock.systemUTC());
        }
    }

    /** M-092: GET /ops/schema-status for {@code sangam upgrade check --server}; read only, platform operators. */
    @Configuration(proxyBeanMethods = false)
    @Import(SchemaStatusController.class)
    static class SchemaStatus {
    }
}
