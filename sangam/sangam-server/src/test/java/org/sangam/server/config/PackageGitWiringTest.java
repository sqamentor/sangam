// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackageGitRemote;
import org.sangam.server.bridge.PackageGitBridge;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-100: the server binds sangam-rest's {@link PackageGitRemote} to {@link PackageGitSync} only when
 * {@code sangam.packages.git.enabled=true} and JGit is on the class path (it is optional in sangam-integration); the
 * bound transport is air-gapped by default (no egress rule: every remote refused before any connection), and every
 * Git failure is a problem with its stable code. Before the binding no bean existed and pull and push were always 501.
 */
class PackageGitWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PackageGitWiring.class);

    /** An address literal (TEST-NET-3), so the egress check resolves nothing over DNS. */
    private static final PackageGitRemote.Remote ORDERS = new PackageGitRemote.Remote("https://203.0.113.10/orders.git", "main",
            null, "generic");

    @Test
    void offByDefaultAndWithoutJgit() {
        runner.run(ctx -> assertTrue(ctx.getBeansOfType(PackageGitRemote.class).isEmpty()));
        runner.withPropertyValues("sangam.packages.git.enabled=false")
                .run(ctx -> assertTrue(ctx.getBeansOfType(PackageGitRemote.class).isEmpty()));
        runner.withPropertyValues("sangam.packages.git.enabled=true").withClassLoader(new FilteredClassLoader("org.eclipse.jgit"))
                .run(ctx -> assertTrue(ctx.getBeansOfType(PackageGitRemote.class).isEmpty(), "no JGit, no binding"));
    }

    @Test
    void enabledBindsTheJgitTransport() {
        runner.withPropertyValues("sangam.packages.git.enabled=true")
                .run(ctx -> assertInstanceOf(PackageGitBridge.class, ctx.getBean(PackageGitRemote.class)));
    }

    @Test
    void withoutAnEgressRuleEveryRemoteIsRefusedBeforeAnyConnection() {
        PackageGitRemote git = new PackageGitBridge(PackageGitWiring.sync(new MockEnvironment(), SecretResolver.of(Map.of())));
        ApiProblem p = assertThrows(ApiProblem.class, () -> git.pull("t1", ORDERS));
        assertEquals(422, p.status());
        assertEquals("GIT_INVALID", p.code());
    }

    @Test
    void anAllowedHostThatIsPrivateIsStillRefused() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.packages.git.egress[0]", "https://127.0.0.1/");
        PackageGitRemote git = new PackageGitBridge(PackageGitWiring.sync(env, SecretResolver.of(Map.of())));
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> git.pull("t1", new PackageGitRemote.Remote("https://127.0.0.1/orders.git", "main", null, null)));
        assertEquals("GIT_INVALID", p.code());
    }

    @Test
    void remotesAndCredentialsAreCheckedByName() {
        PackageGitRemote git = new PackageGitBridge(PackageGitWiring.sync(new MockEnvironment()
                .withProperty("sangam.packages.git.egress[0]", "https://git.example.org/"), SecretResolver.of(Map.of())));
        assertEquals("GIT_INVALID", assertThrows(ApiProblem.class,
                () -> git.pull("t1", new PackageGitRemote.Remote("http://git.example.org/orders.git", "main", null, null))).code(),
                "only https");
        assertEquals("GIT_INVALID", assertThrows(ApiProblem.class,
                () -> git.pull("t1", new PackageGitRemote.Remote("https://u:p@git.example.org/orders.git", "main", null, null))).code(),
                "no credentials in the URL");
        assertEquals("GIT_INVALID", assertThrows(ApiProblem.class,
                () -> git.pull("t1", new PackageGitRemote.Remote("https://git.example.org/a b.git", "main", null, null))).code(),
                "not a URI");
        assertEquals("GIT_INVALID", assertThrows(ApiProblem.class,
                () -> git.push("t1", ORDERS, Map.of("../x.bpmn", new byte[0]), "m", "a", null)).code(), "unsafe path");
    }
}
