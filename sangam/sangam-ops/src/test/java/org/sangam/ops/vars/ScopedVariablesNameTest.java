// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.vars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;

/**
 * The name rules of {@link ScopedVariables#set}: the grammar (a letter, then letters, digits, {@code _ . -}, at most
 * 128 characters) and the refusal of secret-like names. Both are checked before the connection is used, with
 * precompiled patterns; the secret check searches the name ({@code find}) instead of matching {@code .*word.*}.
 */
class ScopedVariablesNameTest {

    private final ScopedVariables vars = new ScopedVariables(Dialect.POSTGRESQL, null, null, 0);

    private IllegalArgumentException refused(String name) {
        // a null connection: a refused name must be refused before any SQL
        return assertThrows(IllegalArgumentException.class,
                () -> vars.set((java.sql.Connection) null, ScopedVariables.CLUSTER, name, 1, "ops", "r"), String.valueOf(name));
    }

    @Test
    void aSecretLikeWordAnywhereInTheNameIsRefusedInAnyCase() {
        for (String name : List.of("password", "dbPassword", "DB_PASSWORD_2", "my.secret", "SecretKey", "apiToken",
                "token", "x-token-y", "apikey", "ApiKey1", "the-api-key", "API-KEY", "credential", "userCredentials",
                "a" + "b".repeat(100) + "token" + "c".repeat(22))) {
            assertEquals("secret-like names belong in the secret store", refused(name).getMessage(), name);
        }
    }

    @Test
    void theGrammarIsCheckedFirst() {
        for (String name : new String[]{null, "", "1limit", "_x", "a b", "a;b", "a\nb", "limit\n", "a".repeat(129),
                "password with space"}) {
            assertEquals("invalid variable name", refused(name).getMessage(), String.valueOf(name));
        }
    }

    /** An ordinary name passes both checks: the next step uses the connection, which is null here. */
    @Test
    void ordinaryNamesPassTheNameChecks() {
        for (String name : List.of("limit", "max.batch-size_2", "a", "A".repeat(128), "tokyo", "secre", "pass.word")) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> vars.set((java.sql.Connection) null, ScopedVariables.CLUSTER, name, 1, "ops", "r"), name);
            assertFalse(e instanceof IllegalArgumentException, () -> name + " was refused: " + e.getMessage());
        }
    }

    @Test
    void aLongRejectedInputIsRefusedQuickly() {
        String huge = "a".repeat(2_000_000) + "!";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> refused(huge));
    }
}
