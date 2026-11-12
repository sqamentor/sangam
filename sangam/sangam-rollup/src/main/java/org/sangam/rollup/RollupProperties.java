// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sangam.rollup.*}.
 *
 * @param tokens          {@code <token>:<ROLE>[,<ROLE>]}: CELL (ingest), TIER (ingest, including explicit metric
 *                        events of a lower roll-up tier), DASHBOARD (queries), ADMIN
 * @param shards          counter shards per (node, metric, day); 16 spreads a nation-level row over 16 rows so the
 *                        ~3,000 events/s national peak never serialises on one row (about 190 updates/s per row)
 * @param dedupRetention  how long event ids are remembered; must exceed the longest relay redelivery window
 *                        (outbox retention 7 d + restore scenarios), default 30 d
 * @param perDefinitionMetrics also count {@code <metric>#<processDefinitionKey>} (bounded by the deployed models)
 * @param maxBatch        maximum events per ingest call
 * @param preAggregate    M-063 (SCALE-ARCHITECTURE 8.4 b): an ingest array is projected in one transaction with its
 *                        counter deltas summed per (node, metric, day) before they are written; default true. False
 *                        projects every event in its own transaction (the 8.2 path)
 * @param scope           M-063 (8.4 a): the hierarchy node this instance serves, for example {@code IN/RJ} for a
 *                        state roll-up; events whose tenant path lies outside it are rejected. Empty = everything
 * @param upstream        M-063 (8.4 a): forwarding to the next roll-up tier; absent or without a url = top tier
 */
@ConfigurationProperties(prefix = "sangam.rollup")
public record RollupProperties(List<String> tokens, Integer shards, Duration dedupRetention, Boolean perDefinitionMetrics,
                               Integer maxBatch, Boolean preAggregate, String scope, Upstream upstream) {

    public RollupProperties {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
        shards = shards == null || shards < 1 ? 16 : shards;
        dedupRetention = dedupRetention == null ? Duration.ofDays(30) : dedupRetention;
        perDefinitionMetrics = perDefinitionMetrics == null || perDefinitionMetrics;
        maxBatch = maxBatch == null ? 500 : maxBatch;
        preAggregate = preAggregate == null || preAggregate;
        List<String> scopePrefixes = RollupProjector.prefixes(scope == null ? "" : scope);
        scope = scopePrefixes.isEmpty() ? "" : scopePrefixes.getLast();   // normalised: no blank or padded segments
        upstream = upstream == null ? new Upstream(null, null, null, null, null, null, null, null) : upstream;
    }

    /** Never prints the tokens. */
    @Override
    public String toString() {
        return "RollupProperties[tokens=" + tokens.size() + ", shards=" + shards + ", preAggregate=" + preAggregate
                + ", scope=" + scope + ", upstream=" + upstream + "]";
    }

    /**
     * {@code sangam.rollup.upstream.*}: this instance forwards its counts to the next tier as explicit metric events
     * ({@value RollupEvents#METRIC_TYPE} with {@code data.metric}, {@code data.day}, {@code data.delta}).
     *
     * @param url        ingest URL of the upper roll-up ({@code https://national-rollup/api/v1/ingest}); empty = off
     * @param token      bearer token of this instance at the upper roll-up (role TIER there)
     * @param depth      deepest hierarchy level forwarded, 0 = the root ({@code IN}); a deeper event is forwarded as
     *                   its ancestor at this level. Default: one level below {@link #scope()} (the scope node's
     *                   children), or 1 without a scope
     * @param interval   how often pending deltas become events (8.4: per-minute aggregates), default 1 min
     * @param instanceId sent as {@code sangamcell} (at most 16 characters), so the upper tier shows this instance's
     *                   feed lag; required when forwarding
     * @param maxBatch   events per call to the upper tier, default 200
     * @param timeout    per call, default 10 s
     * @param sendInterval how often queued events are sent, default 5 s
     */
    public record Upstream(String url, String token, Integer depth, Duration interval, String instanceId,
                           Integer maxBatch, Duration timeout, Duration sendInterval) {

        public Upstream {
            url = url == null ? "" : url.trim();
            interval = interval == null ? Duration.ofMinutes(1) : interval;
            maxBatch = maxBatch == null || maxBatch < 1 ? 200 : maxBatch;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
            sendInterval = sendInterval == null ? Duration.ofSeconds(5) : sendInterval;
            if (!url.isEmpty()) {
                if (instanceId == null || instanceId.isBlank() || instanceId.length() > 16) {
                    throw new IllegalArgumentException(
                            "sangam.rollup.upstream.instance-id is required (1-16 characters) when upstream.url is set");
                }
                if (depth != null && depth < 0) {
                    throw new IllegalArgumentException("sangam.rollup.upstream.depth must be 0 or more");
                }
            }
        }

        public boolean enabled() {
            return !url.isEmpty();
        }

        /** Never prints the token. */
        @Override
        public String toString() {
            return "Upstream[url=" + url + ", instanceId=" + instanceId + ", depth=" + depth + ", interval=" + interval
                    + ", token=" + (token == null || token.isEmpty() ? "none" : "(set)") + "]";
        }
    }
}
