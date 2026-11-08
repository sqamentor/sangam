// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code sangam.rest.*} settings. Bound by the server ({@code @ConfigurationProperties("sangam.rest")}).
 */
public class RestProperties {

    /** Capped count (A-16): above this, X-Total-Count is the cap and X-Total-Count-Capped is true. */
    private long countCap = 10_000;

    /** Government profile (X.2.2.1): Idempotency-Key is required for start, correlate, task decisions, cancel, delete, deploy. */
    private boolean governmentProfile = false;

    /** Long-poll ceiling for fetch-and-lock. */
    private Duration maxAsyncResponseTimeout = Duration.ofSeconds(30);

    /** Default lease length per topic (VI.3 rule 3: default 5 min, maximum 24 h). */
    private Duration defaultLockDuration = Duration.ofMinutes(5);

    private Duration maxLockDuration = Duration.ofHours(24);

    /** Topics marked {@code effect=irreversible} (VI.3 rule 5): expiry goes to UNKNOWN_OUTCOME, never re-offered. */
    private List<String> irreversibleTopics = new ArrayList<>();

    /** Batches above this item count need a second approver (R64 four-eyes). */
    private long batchFourEyesThreshold = 100;

    /** Outbox budget (VI.2.4): rows pending before REFUSE_NEW at 80 %. */
    private long outboxBudget = 100_000;

    /** Node id used in idempotency claims and relay ownership. */
    private String node = "sangam-node";

    public long getCountCap() {
        return countCap;
    }

    public void setCountCap(long countCap) {
        this.countCap = countCap;
    }

    public boolean isGovernmentProfile() {
        return governmentProfile;
    }

    public void setGovernmentProfile(boolean governmentProfile) {
        this.governmentProfile = governmentProfile;
    }

    public Duration getMaxAsyncResponseTimeout() {
        return maxAsyncResponseTimeout;
    }

    public void setMaxAsyncResponseTimeout(Duration maxAsyncResponseTimeout) {
        this.maxAsyncResponseTimeout = maxAsyncResponseTimeout;
    }

    public Duration getDefaultLockDuration() {
        return defaultLockDuration;
    }

    public void setDefaultLockDuration(Duration defaultLockDuration) {
        this.defaultLockDuration = defaultLockDuration;
    }

    public Duration getMaxLockDuration() {
        return maxLockDuration;
    }

    public void setMaxLockDuration(Duration maxLockDuration) {
        this.maxLockDuration = maxLockDuration;
    }

    public List<String> getIrreversibleTopics() {
        return irreversibleTopics;
    }

    public void setIrreversibleTopics(List<String> irreversibleTopics) {
        this.irreversibleTopics = irreversibleTopics;
    }

    public long getBatchFourEyesThreshold() {
        return batchFourEyesThreshold;
    }

    public void setBatchFourEyesThreshold(long batchFourEyesThreshold) {
        this.batchFourEyesThreshold = batchFourEyesThreshold;
    }

    public long getOutboxBudget() {
        return outboxBudget;
    }

    public void setOutboxBudget(long outboxBudget) {
        this.outboxBudget = outboxBudget;
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }
}
