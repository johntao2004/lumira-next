package com.lumira.platform.identity.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityPolicyPortTest {
    private static SecurityPolicyPort.Policy policy(boolean allowConsecutive) {
        return new SecurityPolicyPort.Policy(
                8, true, true, true, allowConsecutive, 30, 120, 10, 60, 600, 60, 10, 10, 5);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Q!r7abc9", "Q!r7cba9", "Q!r71239", "Q!r73219", "Q!r7aBc9", "Q!r7CbA9",
                "Q!r7aa9x", "Q!r7119x", "abcQ!r79", "Q!r79xyz", "Q!r7１２３9", "Q!r7αβγ9",
                "Q!r7\uD801\uDC28\uD801\uDC29\uD801\uDC2A9"
            })
    void disabledPolicyRejectsSequencesAndExistingRepeats(String password) {
        assertFalse(policy(false).passwordValid(password));
        assertTrue(policy(true).passwordValid(password));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Q!r7abx9",
                "Q!r7ab9x",
                "Q!r7a-b-c9",
                "Q!r71-2-39",
                "Q!r7zab9",
                "Q!r7aAq9",
                "Q!r7!\"#9",
                "Q!r689:9",
                "Q!r7😀😁😂9"
            })
    void disabledPolicyDoesNotRejectShortSeparatedWrappedOrNonAlphanumericRuns(String password) {
        assertTrue(policy(false).passwordValid(password));
    }
}
