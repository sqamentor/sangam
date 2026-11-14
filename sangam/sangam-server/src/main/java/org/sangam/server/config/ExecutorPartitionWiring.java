// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineComponents;
import org.sangam.engine.SangamProcessEnginePlugin;
import org.sangam.engine.job.PartitionResize;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ExecutorPartitionPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.ExecutorPartitionBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Decision 146 (M-056): the executor partition port over the {@link PartitionResize} every Sangam engine builds
 * ({@link SangamEngineComponents#partitionResize()}, W4-E DONE 2026-11-13T03:29:48Z). A core route, always present.
 * The engine's resize is looked up per call, so the port never holds a stale reference; an engine without the
 * {@link SangamProcessEnginePlugin} (or before the plugin installed its resize) answers 501
 * {@code CAPABILITY_NOT_INSTALLED}, the same answer the controller gives without a port. Every rule (counts, one active
 * resize, epochs, the resize audit under the system audit scope) stays the engine's; a resize request runs on the host
 * transaction's connection so the R41 idempotency record commits with it.
 */
@Configuration(proxyBeanMethods = false)
public class ExecutorPartitionWiring {

    @Bean
    @ConditionalOnMissingBean(ExecutorPartitionPort.class)
    public ExecutorPartitionPort sangamExecutorPartitionPort(OperatonEngineAdapter sangamEngineAdapter, TxSupport tx,
                                                             RestProperties props) {
        return new ExecutorPartitionBridge(resizes(() -> partitionResize(sangamEngineAdapter.processEngine()), tx),
                props.getCountCap());
    }

    /** The engine's resize of {@code engine}, or null when it is not a Sangam engine or none is installed yet. */
    static PartitionResize partitionResize(ProcessEngine engine) {
        if (engine == null || !(engine.getProcessEngineConfiguration() instanceof ProcessEngineConfigurationImpl cfg)
                || cfg.getProcessEnginePlugins() == null) {
            return null;
        }
        return cfg.getProcessEnginePlugins().stream().filter(SangamProcessEnginePlugin.class::isInstance)
                .map(p -> ((SangamProcessEnginePlugin) p).components()).filter(c -> c != null)
                .map(SangamEngineComponents::partitionResize).filter(r -> r != null).findFirst().orElse(null);
    }

    /** {@link ExecutorPartitionBridge.Resizes#of} over the resize {@code current} gives, 501 while it gives none. */
    static ExecutorPartitionBridge.Resizes resizes(Supplier<PartitionResize> current, TxSupport tx) {
        return new ExecutorPartitionBridge.Resizes() {
            private ExecutorPartitionBridge.Resizes engine() {
                PartitionResize r = current.get();
                if (r == null) {
                    throw ApiProblem.notInstalled("executor partition administration",
                            "sangam-engine (SangamProcessEnginePlugin)");
                }
                return ExecutorPartitionBridge.Resizes.of(r, tx);
            }

            @Override
            public PartitionResize.PartitionMap map() {
                return engine().map();
            }

            @Override
            public PartitionResize.Resize request(String resizeId, String actor, String reason, int partitionCount,
                                                  Long expectedMapEpoch) {
                return engine().request(resizeId, actor, reason, partitionCount, expectedMapEpoch);
            }

            @Override
            public Optional<PartitionResize.Resize> get(String resizeId) {
                return engine().get(resizeId);
            }

            @Override
            public List<PartitionResize.Resize> list(PartitionResize.State state) {
                return engine().list(state);
            }
        };
    }
}
