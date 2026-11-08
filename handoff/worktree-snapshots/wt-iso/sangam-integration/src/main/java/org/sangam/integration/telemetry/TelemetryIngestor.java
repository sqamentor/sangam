// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.core.layer.Inbox;
import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;
import org.sangam.integration.inbox.InboxService;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Shared ingestion path for every telemetry transport (HTTP, MQTT): validates a reading, checks the device against the
 * registry and stores the reading in the inbox ({@link InboxService#receive}, which applies the R40 back-pressure gate
 * and the R38 tenant rule) with
 * <ul>
 *   <li>source {@code telemetry:<channelKey>} (handled by {@link TelemetryRuleHandler}),</li>
 *   <li>message id {@code deviceId:sensor:observedAt-epoch-ms} unless the transport supplies a stable id (dedup key),</li>
 *   <li>event type {@value TelemetryRuleHandler#EVENT_TYPE}, correlation key = device id.</li>
 * </ul>
 * The caller acknowledges its transport only after {@link #ingest} returned; {@code BackpressureGate.BackpressureException}
 * and infrastructure errors propagate (do not ack: retry later). The tenant is the binding's; a claimed tenant (topic
 * segment, header) is only checked against it ({@link SecurityException} on mismatch).
 */
public final class TelemetryIngestor {

    /** What to do with a reading from a device that is not in the registry. */
    public enum UnknownDevicePolicy { REJECT, AUTO_REGISTER }

    /**
     * One telemetry channel: its key (inbox source {@code telemetry:<key>}), the authenticated binding and the
     * unknown-device policy. Auto-registered devices are pinned to this channel.
     */
    public record Channel(String channelKey, ChannelBinding binding, UnknownDevicePolicy unknownDevices) {
        public Channel {
            Objects.requireNonNull(channelKey, "channelKey");
            Objects.requireNonNull(binding, "binding");
            if (!binding.source().equals(TelemetryRuleHandler.SOURCE_PREFIX + channelKey)) {
                throw new IllegalArgumentException("binding source must be " + TelemetryRuleHandler.SOURCE_PREFIX + channelKey
                        + ", was " + binding.source());
            }
            unknownDevices = unknownDevices == null ? UnknownDevicePolicy.REJECT : unknownDevices;
        }

        public static Channel of(String channelKey, String tenant, String principal, UnknownDevicePolicy policy) {
            return new Channel(channelKey, ChannelBinding.of(TelemetryRuleHandler.SOURCE_PREFIX + channelKey, tenant, principal), policy);
        }
    }

    public enum Status { ACCEPTED, DUPLICATE, UNKNOWN_DEVICE, DEVICE_DISABLED, WRONG_CHANNEL, INVALID }

    public record Outcome(Status status, String tenantId, String messageId, String detail) {
        public boolean stored() {
            return status == Status.ACCEPTED || status == Status.DUPLICATE;
        }
    }

    private final InboxService inbox;
    private final DeviceRegistry devices;
    private final Duration maxFutureSkew;

    /**
     * @param maxFutureSkew how far {@code observedAt} may be ahead of this node's clock (device clock drift); later
     *                      readings are {@code INVALID}. Default 5 minutes.
     */
    public TelemetryIngestor(InboxService inbox, DeviceRegistry devices, Duration maxFutureSkew) {
        this.inbox = inbox;
        this.devices = devices;
        this.maxFutureSkew = maxFutureSkew == null ? Duration.ofMinutes(5) : maxFutureSkew;
    }

    /** Device admission for {@code tenant} on {@code channel} (auto-registers when the policy allows); null when admitted. */
    public Outcome admit(Channel channel, String tenant, String deviceId) {
        var device = devices.find(tenant, deviceId);
        if (device.isEmpty()) {
            if (channel.unknownDevices() == UnknownDevicePolicy.AUTO_REGISTER) {
                devices.register(tenant, deviceId, null, null, channel.channelKey());
                device = devices.find(tenant, deviceId);
            }
            if (device.isEmpty()) {
                return new Outcome(Status.UNKNOWN_DEVICE, tenant, null, "device " + deviceId + " is not registered");
            }
        }
        if (!device.get().enabled()) {
            return new Outcome(Status.DEVICE_DISABLED, tenant, null, "device " + deviceId + " is disabled");
        }
        if (!device.get().allowsChannel(channel.channelKey())) {
            return new Outcome(Status.WRONG_CHANNEL, tenant, null, "device " + deviceId + " is not allowed on channel " + channel.channelKey());
        }
        return null;
    }

    /**
     * Validates and stores one reading.
     *
     * @param suppliedMessageId stable transport/event id, or null for the default {@code deviceId:sensor:epochMs}
     * @param claimedTenant     tenant claimed by the transport (header, topic segment), or null
     */
    public Outcome ingest(Channel channel, TelemetryReading reading, String suppliedMessageId, String claimedTenant) {
        String tenant = channel.binding().tenantFor(claimedTenant);
        if (reading.observedAt().isAfter(Instant.now().plus(maxFutureSkew))) {
            return new Outcome(Status.INVALID, tenant, null, "observedAt " + reading.observedAt() + " is in the future");
        }
        String messageId = suppliedMessageId != null && !suppliedMessageId.isBlank() ? suppliedMessageId : defaultMessageId(reading);
        if (messageId.length() > 255) {
            return new Outcome(Status.INVALID, tenant, null, "message id longer than 255 characters");
        }
        Outcome refused = admit(channel, tenant, reading.deviceId());
        if (refused != null) {
            return new Outcome(refused.status(), tenant, messageId, refused.detail());
        }
        Inbox.ReceiveResult r = inbox.receive(channel.binding(),
                new InboundMessage(messageId, TelemetryRuleHandler.EVENT_TYPE, reading.deviceId(), tenant, Json.write(reading.toMap())));
        return new Outcome(r == Inbox.ReceiveResult.DUPLICATE ? Status.DUPLICATE : Status.ACCEPTED, tenant, messageId, null);
    }

    public static String defaultMessageId(TelemetryReading r) {
        return r.deviceId() + ":" + r.sensor() + ":" + r.observedAt().toEpochMilli();
    }
}
