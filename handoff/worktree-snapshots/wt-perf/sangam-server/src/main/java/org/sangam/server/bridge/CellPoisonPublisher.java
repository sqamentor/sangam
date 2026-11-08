// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.cell.rollup.RollupPublisher;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.relay.PermanentPublishException;

/**
 * IR-3 {@code wrapPoison}: a {@link RollupPublisher.PoisonEventException} (an event the receiver can never accept)
 * becomes a {@link PermanentPublishException}, so the relay parks/skips it at once instead of retrying.
 */
public final class CellPoisonPublisher implements Publisher {

    private final Publisher delegate;

    public CellPoisonPublisher(Publisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        try {
            delegate.publish(destination, event);
        } catch (RollupPublisher.PoisonEventException e) {
            throw new PermanentPublishException(e.getMessage(), e);
        }
    }
}
