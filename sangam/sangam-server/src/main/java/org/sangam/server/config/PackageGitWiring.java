// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.git.PackageGitSync;
import org.sangam.rest.port.PackageGitRemote;
import org.sangam.server.bridge.PackageGitBridge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-100 (R69): binds package Git pull and push ({@code POST /packages/{id}/git/pull|push}) to sangam-integration's
 * {@link PackageGitSync}. Off unless {@code sangam.packages.git.enabled=true}, and only when JGit (optional in
 * sangam-integration) is on the class path; otherwise there is no {@link PackageGitRemote} bean and pull and push
 * answer {@code 501 CAPABILITY_NOT_INSTALLED}. Air-gapped by default (§1.9): with no {@code egress} rule every remote is
 * refused ({@code 422 GIT_INVALID}) before any connection.
 *
 * <pre>
 * sangam.packages.git.enabled=true
 * sangam.packages.git.egress[0]=https://git.example.org/       rule specs as for connectors (tenant "*")
 * sangam.packages.git.timeout=60s   .max-files=2000   .max-bytes=67108864   .work-dir=(system temp)
 * </pre>
 * Credentials are {@code credentialRef} names of the package's Git configuration, read through the platform
 * {@link SecretResolver} on every call.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.packages.git.enabled", havingValue = "true")
@ConditionalOnClass(name = PackageGitWiring.JGIT_CLASS)
public class PackageGitWiring {

    static final String JGIT_CLASS = "org.eclipse.jgit.api.Git";
    static final String P = "sangam.packages.git.";

    @Bean
    public PackageGitRemote sangamPackageGitRemote(Environment env, ObjectProvider<SecretResolver> secrets) {
        return new PackageGitBridge(sync(env, secrets.getIfAvailable(SecretResolver::fromEnvironment)));
    }

    static PackageGitSync sync(Environment env, SecretResolver secrets) {
        List<EgressPolicy.Rule> rules = Binder.get(env).bind(P + "egress", Bindable.listOf(String.class)).orElse(List.of())
                .stream().map(s -> EgressPolicy.Rule.parse("*", s, false)).toList();
        EgressPolicy egress = rules.isEmpty() ? EgressPolicy.denyAll() : EgressPolicy.of(rules, 0, 64L * 1024 * 1024);
        String workDir = env.getProperty(P + "work-dir");
        return new PackageGitSync(egress, secrets, workDir == null || workDir.isBlank() ? null : Path.of(workDir),
                env.getProperty(P + "timeout", Duration.class, Duration.ofSeconds(60)), null,
                env.getProperty(P + "max-files", Integer.class, 0), env.getProperty(P + "max-bytes", Long.class, 0L));
    }
}
