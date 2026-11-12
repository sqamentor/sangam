// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.example.sangamhost.SampleHostApplication;
import org.junit.jupiter.api.Test;
import org.sangam.server.SangamServerApplication;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * M-014 proof that the starter changes nothing for the standalone server and gives a host the server's wiring.
 *
 * <ol>
 *   <li><b>Server without the starter</b> ({@link SangamAutoConfiguration} excluded) and <b>server with the starter</b>
 *       on its class path: the same bean set. The starter backs off because the server's own scan registered the
 *       wiring, and its defaults are not applied to the server.</li>
 *   <li><b>A host with the starter</b> ({@link SampleHostApplication}) and the server's configuration: the server's
 *       bean set. The host gets the same {@code *Wiring} classes (imported, not copied) in the same position relative
 *       to the other auto-configurations, so every {@code @ConditionalOnMissingBean} decides as in the server.</li>
 * </ol>
 * A bean is compared by name when a {@code @Bean} method declares it, and by class when it is a scanned or imported
 * component (an imported class is registered under its fully qualified name, a scanned one under its short name).
 * The application class itself and the starter's own configuration class are the only beans left out.
 *
 * <p>Each context is a full start on its own H2 database with the server's application.yaml and h2 profile, one after
 * the other (one engine per JVM, D-10).
 */
class ServerBeanSetEqualityTest {

    /** The server's configuration files, read from the source tree so every context has exactly the same settings. */
    static final Path SERVER_CONFIG = Path.of("..", "sangam-server", "src", "main", "resources").toAbsolutePath().normalize();

    static String[] properties(String db, String... extra) {
        String[] base = {
                "spring.config.location=optional:file:" + SERVER_CONFIG + "/",
                "spring.datasource.url=jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL",
                "server.port=0", "management.server.port=0",
                "sangam.engine.allow-multiple-engines-per-jvm=false"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    static Set<String> beanSet(Class<?> application, String... properties) {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(application).profiles("h2")
                .properties(properties).run()) {
            return describe(ctx.getBeanFactory(), application);
        }
    }

    /** {@code bean:<name>} for {@code @Bean} methods, {@code class:<type>} for components. */
    static Set<String> describe(ConfigurableListableBeanFactory bf, Class<?> application) {
        Set<String> out = new TreeSet<>();
        for (String name : bf.getBeanDefinitionNames()) {
            BeanDefinition d = bf.getBeanDefinition(name);
            String type = d.getBeanClassName();
            if (application.getName().equals(type) || SangamAutoConfiguration.class.getName().equals(type)) {
                continue;
            }
            out.add(d.getFactoryMethodName() != null ? "bean:" + name : "class:" + (type != null ? type : name));
        }
        return out;
    }

    @Test
    void theServerConfigurationIsTheSourceTree() {
        assertTrue(Files.isRegularFile(SERVER_CONFIG.resolve("application.yaml")), SERVER_CONFIG.toString());
    }

    @Test
    void theStarterChangesNothingForTheServerAndGivesAHostTheServersBeans() {
        Set<String> without = beanSet(SangamServerApplication.class, properties("beanset-without-" + UUID.randomUUID(),
                "spring.autoconfigure.exclude=" + SangamAutoConfiguration.class.getName()));
        Set<String> with = beanSet(SangamServerApplication.class, properties("beanset-with-" + UUID.randomUUID()));
        assertFalse(without.isEmpty());
        assertTrue(without.contains("class:org.sangam.server.config.ModuleWiring"), "the server's wiring is loaded");
        assertEquals(without, with, "the server's bean set does not change with the starter on its class path");

        Set<String> host = beanSet(SampleHostApplication.class, properties("beanset-host-" + UUID.randomUUID()));
        assertEquals(without, host, "a host with the starter and the server's settings gets the server's bean set");
    }
}
