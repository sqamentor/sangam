// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Decision 334: {@value SangamDefaultsEnvironmentPostProcessor#SOURCE_NAME} is the lowest-precedence property source of
 * a host, below Spring Boot's {@code defaultProperties} as well.
 *
 * <p>Why more than the post-processor: Spring Boot 4.1 moves {@code defaultProperties} to the end three times after
 * the environment post-processors have run ({@link SangamDefaultsEnvironmentPostProcessor} adds the source last):
 * in {@code SpringApplication.prepareEnvironment} ({@code DefaultPropertiesPropertySource.moveToEnd}, after
 * {@code ApplicationInfoPropertySource.moveToEnd}), and again when the context refreshes, through
 * {@code SpringApplication$PropertySourceOrderingBeanFactoryPostProcessor}, which {@code prepareContext} adds to the
 * context after the initializers have run. A host setting given through {@code SpringApplicationBuilder.properties}
 * or {@code setDefaultProperties} then sat above Sangam's default and lost to it.
 *
 * <p>So this initializer moves the source to the end at once and also registers {@link MoveToEnd} as a bean-defined
 * factory post-processor. Spring invokes every post-processor added to the context (Boot's among them) before any
 * bean-defined one ({@code PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors}), and
 * {@link PriorityOrdered} puts it first among the bean-defined ones, so the source is last again before any other
 * post-processor or bean reads a property.
 *
 * <p>The management child context (a separate management port) takes the parent's sources in the parent's order
 * ({@code AbstractApplicationContext.setParent}, {@code StandardEnvironment.merge}), so the order holds there too.
 * Sources Spring adds while the context refreshes are not host settings and may come after it, for example the
 * {@code Management Server} source with only the {@code local.management.port} alias
 * ({@code ManagementContextAutoConfiguration} when management shares the server port). Without the source (the
 * standalone server, or {@code sangam.starter.defaults.enabled=false}) nothing changes.
 */
public class SangamDefaultsOrder implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    /** Bean name of {@link MoveToEnd}. */
    static final String BEAN_NAME = "org.sangam.spring.boot.autoconfigure.SangamDefaultsOrder.MoveToEnd";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        moveToEnd(environment);
        if (!(context.getBeanFactory() instanceof BeanDefinitionRegistry registry)) {
            // Every Spring Boot context (GenericApplicationContext) has a DefaultListableBeanFactory; refuse rather
            // than leave the host's settings below Sangam's defaults without saying so.
            throw new IllegalStateException("Sangam defaults ordering needs a BeanDefinitionRegistry bean factory, got "
                    + context.getBeanFactory().getClass().getName());
        }
        if (!registry.containsBeanDefinition(BEAN_NAME)) {
            RootBeanDefinition definition = new RootBeanDefinition(MoveToEnd.class, () -> new MoveToEnd(environment));
            definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            registry.registerBeanDefinition(BEAN_NAME, definition);
        }
    }

    /** Moves {@value SangamDefaultsEnvironmentPostProcessor#SOURCE_NAME} to the end, when present. */
    static void moveToEnd(ConfigurableEnvironment environment) {
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> defaults = sources.remove(SangamDefaultsEnvironmentPostProcessor.SOURCE_NAME);
        if (defaults != null) {
            sources.addLast(defaults);
        }
    }

    /** Puts the source last again after Spring Boot's own reordering at refresh. */
    static final class MoveToEnd implements BeanFactoryPostProcessor, PriorityOrdered {

        private final ConfigurableEnvironment environment;

        MoveToEnd(ConfigurableEnvironment environment) {
            this.environment = environment;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            moveToEnd(environment);
        }
    }
}