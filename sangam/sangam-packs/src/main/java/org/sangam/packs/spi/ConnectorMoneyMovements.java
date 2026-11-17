// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Owner decision A1: the declared {@link MoneyMovement} of every pack connector on the class path, filled by
 * {@link PackRegistry#add}. Two packs that declare one connector id with different directions are refused (a rule
 * conflict is never resolved silently, A4). A connector nobody declared is treated as {@link MoneyMovement#OUT} by the
 * callers, so an undeclared connector can only ever be stricter.
 */
public final class ConnectorMoneyMovements {

    private static final Map<String, MoneyMovement> DECLARED = new HashMap<>();
    private static final Map<String, String> DECLARED_BY = new HashMap<>();

    private ConnectorMoneyMovements() {
    }

    /** Records the pack's connectors; a conflicting earlier declaration throws. */
    public static synchronized void registerDeclared(PackDescriptor d) {
        for (PackDescriptor.ConnectorUse u : d.connectors()) {
            if (u.moneyMovement() == null) {
                throw new IllegalStateException("pack " + d.id() + " connector " + u.id() + ": moneyMovement is required");
            }
            MoneyMovement old = DECLARED.putIfAbsent(u.id(), u.moneyMovement());
            if (old == null) {
                DECLARED_BY.put(u.id(), d.id());
            } else if (old != u.moneyMovement()) {
                throw new IllegalStateException("connector " + u.id() + " is " + old + " in pack " + DECLARED_BY.get(u.id())
                        + " but " + u.moneyMovement() + " in pack " + d.id());
            }
        }
    }

    /** The declared direction, or empty when no pack declared the connector. */
    public static synchronized Optional<MoneyMovement> declared(String connectorId) {
        return Optional.ofNullable(DECLARED.get(connectorId));
    }

    /** The direction used for enforcement: undeclared counts as {@link MoneyMovement#OUT}. */
    public static MoneyMovement enforced(String connectorId) {
        return declared(connectorId).orElse(MoneyMovement.OUT);
    }
}
