// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

/**
 * The server's product wiring, found exactly as {@code SangamServerApplication} finds it: the same base packages, in
 * the same order, with {@code @ComponentScan}'s default filters and both {@code @SpringBootApplication} exclude filters
 * (type exclude, auto-configuration exclude). The classes are not copied or forked; they are imported from sangam-server's library jar.
 *
 * <p>Import, not {@code @ComponentScan}: a scan runs while the configuration is parsed, before the importing class's
 * bean conditions are checked, so a scan could not back off when the server itself already scanned these packages.
 * Imported classes are processed in this order, ahead of the other auto-configurations (see
 * {@link SangamAutoConfiguration}), which is where the server's own scan puts them. The one visible difference: a
 * class imported this way is registered under its fully qualified name instead of its short bean name; the beans its
 * {@code @Bean} methods declare keep their names.
 */
public class SangamProductImportSelector implements ImportSelector, EnvironmentAware, ResourceLoaderAware,
        BeanFactoryAware, BeanClassLoaderAware {

    /** {@code SangamServerApplication}'s {@code scanBasePackages}, in its order. */
    public static final List<String> BASE_PACKAGES = List.of("org.sangam.server", "org.sangam.rest.web");

    /** The standalone server's entry point: a host never imports it (it would start a second application setup). */
    public static final String SERVER_APPLICATION = "org.sangam.server.SangamServerApplication";

    private Environment environment;
    private ResourceLoader resourceLoader;
    private BeanFactory beanFactory;
    private ClassLoader classLoader;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        return candidates().toArray(String[]::new);
    }

    /** The component classes of {@link #BASE_PACKAGES}, in scan order, without the server's entry point. */
    public List<String> candidates() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true, environment);
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }
        if (beanFactory != null) {
            TypeExcludeFilter typeExclude = new TypeExcludeFilter();
            typeExclude.setBeanFactory(beanFactory);
            scanner.addExcludeFilter(typeExclude);
        }
        // @SpringBootApplication's second exclude filter: auto-configuration classes are not scanned
        org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter autoConfigExclude =
                new org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter();
        autoConfigExclude.setBeanClassLoader(classLoader != null ? classLoader : getClass().getClassLoader());
        scanner.addExcludeFilter(autoConfigExclude);
        Set<String> out = new LinkedHashSet<>();
        for (String pkg : BASE_PACKAGES) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(pkg)) {
                String name = candidate.getBeanClassName();
                if (name != null && !SERVER_APPLICATION.equals(name)) {
                    out.add(name);
                }
            }
        }
        return new ArrayList<>(out);
    }

}
