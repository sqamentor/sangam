// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.function.Consumer;

/**
 * Injects the committed change of a peer node right after a skip-locked select (the tests' stand-in for Oracle, whose
 * skip-locked select locks rows chosen from the statement's snapshot and so can return a row a peer changed after it).
 * H2 cannot reproduce the snapshot; the peer's change runs on the same connection before the next statement of the
 * code under test is prepared, which is what that code sees on Oracle once it holds the row lock.
 */
final class SkipLockedRaceH2 {

    /** A validated development environment (decision 143): the only one in which a context runs without authorization. */
    static final org.sangam.core.security.EnvironmentPolicy.Resolution DEVELOPMENT =
            org.sangam.core.security.EnvironmentPolicy.resolve("false", "test",
                    org.sangam.core.security.EnvironmentPolicy.signals(java.util.List.of("test")));

    private boolean selected;
    private boolean raced;

    private SkipLockedRaceH2() {
    }

    /**
     * @param fromClause the select's {@code FROM <table> <alias>} text (H2's form of the skip-locked select ends in
     *                   {@code FOR UPDATE})
     * @param peer       the peer's change, run once on the real connection
     */
    static SkipLockedRaceH2 after(String fromClause, Consumer<Connection> peer, Connection real, Connection[] proxyOut) {
        SkipLockedRaceH2 race = new SkipLockedRaceH2();
        proxyOut[0] = (Connection) Proxy.newProxyInstance(SkipLockedRaceH2.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("prepareStatement") && !race.raced) {
                        String sql = String.valueOf(a[0]);
                        if (race.selected) {
                            race.raced = true;
                            peer.accept(real);
                        } else if (sql.contains(fromClause) && sql.trim().endsWith("FOR UPDATE")) {
                            race.selected = true;
                        }
                    }
                    try {
                        return m.invoke(real, a);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        return race;
    }

    boolean raced() {
        return raced;
    }
}
