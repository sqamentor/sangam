// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PermissionPort;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The catalogue controllers have a public Spring constructor and a package-private test
 * constructor. Without a marked constructor Spring looks for a default one and every server
 * context fails ("No default constructor found", ci22). This test builds both controllers the
 * way {@code ModellerRestConfiguration} does: imported by class into a configuration, so the
 * annotation-driven constructor resolution of the server decides which constructor runs.
 */
class CatalogueControllersSpringWiringTest {

    @Configuration(proxyBeanMethods = false)
    @Import({ExtensionCatalogueController.class, CatalogueHostController.class})
    static class Imported {
        @Bean
        PackagePort packagePort() {
            return refuse(PackagePort.class);
        }

        @Bean
        PermissionPort permissionPort() {
            return refuse(PermissionPort.class);
        }
    }

    @Test
    void springBuildsBothCatalogueControllersWhenImportedByClass() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Imported.class)) {
            assertNotNull(ctx.getBean(ExtensionCatalogueController.class));
            assertNotNull(ctx.getBean(CatalogueHostController.class));
        }
    }

    private static <T> T refuse(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + " (refusing test double)";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
                }));
    }
}
