// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import java.util.List;

/** Runtime state of the tenant's {@code conversation-channels} registry entries. */
public interface ChannelStatusSource {

    /** {@code state}: ACTIVE, NO_ADAPTER or INVALID. */
    record ChannelStatus(String key, String channel, String state, String detail) {
    }

    List<ChannelStatus> status(String tenantId);
}
