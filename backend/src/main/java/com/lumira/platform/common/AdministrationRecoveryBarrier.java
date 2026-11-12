package com.lumira.platform.common;

import java.sql.Connection;
import java.sql.SQLException;

/** The existing cross-version barrier shared by ordinary business and administrative writers. */
public final class AdministrationRecoveryBarrier {
    // Preserve AdministrationGuard's published one-bigint lock identity.
    public static final long LOCK_KEY = 1_000_481_027L;

    private AdministrationRecoveryBarrier() {}

    public static void exclusive(Connection connection) throws SQLException {
        if (connection.getAutoCommit())
            throw new IllegalStateException("administrationRecoveryBarrierRequiresTransaction");
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery()) {
                if (!result.next())
                    throw new SQLException("administrationRecoveryBarrierUnavailable");
            }
        }
    }
}
