// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.sangam.backup.BackupConsole;
import org.sangam.backup.ScriptBackupTargets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Owner decision 220 (completing decision 155): the backup console on VM and docker compose installs, over
 * {@link ScriptBackupTargets}. Off by default; {@code sangam.backup.console.script.enabled=true} creates it. Imported
 * by {@code OpsRestConfiguration.Backup}, so it exists only with sangam-backup on the class path. The Kubernetes and the
 * script console exclude each other: enabling both stops the start-up instead of silently picking one.
 */
@Configuration(proxyBeanMethods = false)
public class ScriptBackupWiring {

    public static final String PREFIX = "sangam.backup.console.script";

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = PREFIX + ".enabled", havingValue = "true")
    BackupConsole sangamScriptBackupConsole(Environment env) throws IOException {
        return new BackupConsole(targets(env), Clock.systemUTC());
    }

    static ScriptBackupTargets targets(Environment env) throws IOException {
        if (env.getProperty("sangam.backup.console.kubernetes.enabled", Boolean.class, false)) {
            throw new IllegalStateException("enable either sangam.backup.console.kubernetes or " + PREFIX + ", not both");
        }
        String dir = env.getProperty(PREFIX + ".history-dir");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException(PREFIX + ".history-dir is required (an absolute directory shared by the servers)");
        }
        Set<String> passthrough = new LinkedHashSet<>();
        Arrays.stream(env.getProperty(PREFIX + ".env-passthrough", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).forEach(passthrough::add);
        return new ScriptBackupTargets(ScriptBackupTargets.targetsFrom(PREFIX, env::getProperty), Path.of(dir.trim()),
                passthrough, System.getenv(), ScriptBackupTargets.PROCESS,
                r -> Thread.ofVirtual().name("sangam-backup-run").start(r), Clock.systemUTC());
    }
}
