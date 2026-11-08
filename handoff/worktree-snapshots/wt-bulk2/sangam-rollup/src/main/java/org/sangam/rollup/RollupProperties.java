// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sangam.rollup.*}.
 *
 * @param tokens          {@code <token>:<ROLE>[,<ROLE>]}: CELL (ingest), DASHBOARD (queries), ADMIN
 * @param shards          counter shards per (node, metric, day); 16 spreads a nation-level row over 16 rows so the
 *                        ~3,000 events/s national peak never serialises on one row (about 190 updates/s per row)
 * @param dedupRetention  how long event ids are remembered; must exceed the longest relay redelivery window
 *                        (outbox retention 7 d + restore scenarios), default 30 d
 * @param perDefinitionMetrics also count {@code <metric>#<processDefinitionKey>} (bounded by the deployed models)
 * @param maxBatch        maximum events per ingest call
 */
@ConfigurationProperties(prefix = "sangam.rollup")
public record RollupProperties(List<String> tokens, Integer shards, Duration dedupRetention, Boolean perDefinitionMetrics,
                               Integer maxBatch) {

    public RollupProperties {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
        shards = shards == null || shards < 1 ? 16 : shards;
        dedupRetention = dedupRetention == null ? Duration.ofDays(30) : dedupRetention;
        perDefinitionMetrics = perDefinitionMetrics == null || perDefinitionMetrics;
        maxBatch = maxBatch == null ? 500 : maxBatch;
    }
}
