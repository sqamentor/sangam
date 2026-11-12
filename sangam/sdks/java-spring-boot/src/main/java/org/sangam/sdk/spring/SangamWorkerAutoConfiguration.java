// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.spring;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.sangam.sdk.SangamClient;
import org.sangam.sdk.auth.TokenProvider;
import org.sangam.sdk.worker.Saga;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TopicSubscription;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot adapter of the Java SDK (M-027). With {@code sangam.worker.base-url} set it builds a
 * {@link SangamClient}, a {@link SangamWorker} subscribed to every {@link TopicSubscription} bean and to both topics of
 * every {@link Saga} bean, and a {@link SangamWorkerLifecycle} that starts the worker with the context and stops it
 * gracefully. Every bean backs off when the application declares its own; {@code sangam.worker.enabled=false} turns
 * the adapter off.
 */
@AutoConfiguration
@ConditionalOnClass(SangamWorker.class)
@ConditionalOnProperty(prefix = "sangam.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SangamWorkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties("sangam.worker")
    public SangamWorkerProperties sangamWorkerProperties() {
        return new SangamWorkerProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public SangamClient sangamClient(SangamWorkerProperties props, ObjectProvider<TokenProvider> tokens) {
        return client(props, tokens.getIfAvailable());
    }

    /** No destroy method: {@link SangamWorkerLifecycle} stops the worker in its own (early) shutdown phase. */
    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public SangamWorker sangamWorker(SangamClient client, SangamWorkerProperties props,
                                     ObjectProvider<TopicSubscription> subscriptions, ObjectProvider<Saga> sagas) {
        return worker(client, props, subscriptions.orderedStream().toList(), sagas.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public SangamWorkerLifecycle sangamWorkerLifecycle(SangamWorker worker) {
        return new SangamWorkerLifecycle(worker);
    }

    static SangamClient client(SangamWorkerProperties props, TokenProvider tokenProvider) {
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            throw new IllegalStateException("sangam.worker.base-url is required (or set sangam.worker.enabled=false)");
        }
        SangamClient.Builder b = SangamClient.builder().baseUrl(props.getBaseUrl());
        if (tokenProvider != null) {
            b.tokenProvider(tokenProvider);
        } else if (props.getToken() != null && !props.getToken().isBlank()) {
            b.token(props.getToken());
        }
        return b.build();
    }

    /** The worker with every subscription; a topic registered twice (bean or saga) is a configuration error. */
    static SangamWorker worker(SangamClient client, SangamWorkerProperties props, List<TopicSubscription> subscriptions,
                               List<Saga> sagas) {
        SangamWorker.Builder b = SangamWorker.builder(client);
        if (props.getWorkerId() != null && !props.getWorkerId().isBlank()) {
            b.workerId(props.getWorkerId());
        }
        if (props.getMaxConcurrency() != null) {
            b.maxConcurrency(props.getMaxConcurrency());
        }
        if (props.getMaxTasksPerFetch() != null) {
            b.maxTasksPerFetch(props.getMaxTasksPerFetch());
        }
        if (props.getAsyncResponseTimeout() != null) {
            b.asyncResponseTimeout(props.getAsyncResponseTimeout());
        }
        if (props.getShutdownTimeout() != null) {
            b.shutdownTimeout(props.getShutdownTimeout());
        }
        Set<String> topics = new HashSet<>();
        for (TopicSubscription s : subscriptions) {
            b.subscribe(unique(topics, s));
        }
        for (Saga saga : sagas) {
            for (TopicSubscription s : saga.subscriptions()) {
                b.subscribe(unique(topics, s));
            }
        }
        return b.build();
    }

    private static TopicSubscription unique(Set<String> topics, TopicSubscription s) {
        if (!topics.add(s.topic())) {
            throw new IllegalStateException("topic " + s.topic() + " is registered twice (TopicSubscription or Saga beans)");
        }
        return s;
    }
}
