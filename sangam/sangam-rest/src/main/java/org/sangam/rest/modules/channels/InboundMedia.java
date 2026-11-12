// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

/**
 * The asynchronous media path of the channel webhooks (integrator decision on W3-C 13:02:49Z, contract W3-M 14:01:57Z): the
 * webhook verifies, writes the inbox row with the pending media ({@code InboundMediaQueue.enqueue}, same transaction) and
 * answers within the provider's limit (Slack: 3 s); after that commit it calls this as a wake-up. The host's worker claims
 * the row, downloads the media with the tenant's channel entry, scans it (M-082), stores it and passes the stored document
 * key to the bot. The row is already durable, so a lost wake-up only delays the media until the worker's next poll. Must
 * return at once and never throw: the webhook has already answered.
 */
@FunctionalInterface
public interface InboundMedia {

    /**
     * @param channel     TEAMS, WHATSAPP, WECHAT, MATRIX or SLACK
     * @param externalId  the sender's id on the channel
     * @param principalId the opted-in principal the sender maps to
     * @param messageId   the provider's message id (the inbox key)
     * @param mediaRef    the provider's media reference (media id, file id or {@code mxc://} URL)
     */
    void received(String tenantId, String channel, String externalId, String principalId, String messageId, String mediaRef);
}
