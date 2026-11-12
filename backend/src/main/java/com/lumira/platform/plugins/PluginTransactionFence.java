package com.lumira.platform.plugins;

import com.lumira.platform.common.PluginWorkFencedException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.zip.CRC32;

/** Database-owned locks survive stopped application threads and end with the actual transaction. */
final class PluginTransactionFence {
    // Two-int advisory keys occupy a different namespace from the existing one-bigint locks.
    private static final int NAMESPACE = 0x4c504c47;

    private PluginTransactionFence() {}

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> KEY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int key(String id) {
        Integer cached = KEY_CACHE.get(id);
        if (cached != null) return cached;
        var crc = new CRC32();
        crc.update(id.getBytes(StandardCharsets.UTF_8));
        int value =
                (int) crc.getValue(); // Collisions conservatively serialize, never weaken fencing.
        if (KEY_CACHE.size() < 256) KEY_CACHE.put(id, value);
        return value;
    }

    static void enter(Connection connection, PluginInvocationContext.Owner owner)
            throws SQLException {
        boolean fixedWritableSnapshot = lock(connection, owner.moduleId(), true);
        // Writable repeatable-read transactions must lock the current tuple. PostgreSQL rejects
        // a tuple changed since their snapshot, rather than accepting an obsolete generation.
        // Read committed gets a fresh snapshot in this SECOND statement, after the advisory lock.
        // Its reads need no tuple locks or metadata WAL; every supported graph writer is exclusive.
        // Read-only transactions cannot write; their advisory lock still protects in-flight reads.
        String locking = fixedWritableSnapshot ? " FOR SHARE OF i,s NOWAIT" : "";
        try (var statement =
                connection.prepareStatement(
                        """
                SELECT s.version FROM platform_plugin_installation i
                JOIN platform_module_state s ON s.module_id=i.module_id
                WHERE i.module_id=? AND i.installed AND i.digest=? AND s.enabled AND s.version=?
                AND EXISTS (SELECT 1 FROM platform_plugin_runtime_node n WHERE n.node_id=?
                  AND n.heartbeat_at>clock_timestamp()-interval '30 seconds')
                """
                                + locking)) {
            statement.setString(1, owner.moduleId());
            statement.setString(2, owner.digest());
            statement.setLong(3, owner.generation());
            statement.setObject(4, owner.nodeId());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new PluginWorkFencedException();
            }
        } catch (SQLException failure) {
            if (failure instanceof PluginWorkFencedException) throw failure;
            if ("40001".equals(failure.getSQLState()) || "55P03".equals(failure.getSQLState()))
                throw new PluginWorkFencedException(failure);
            throw failure;
        }
    }

    static void exclusive(Connection connection, Collection<String> owners) throws SQLException {
        for (String owner : owners.stream().sorted().distinct().toList())
            lock(connection, owner, false);
    }

    private static boolean lock(Connection connection, String owner, boolean shared)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        shared
                                ? "SELECT pg_try_advisory_xact_lock_shared(?,?),current_setting('transaction_read_only')::boolean,current_setting('transaction_isolation')"
                                : "SELECT pg_try_advisory_xact_lock(?,?),current_setting('transaction_read_only')::boolean,current_setting('transaction_isolation')")) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, key(owner));
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) throw new PluginWorkFencedException();
                String isolation = result.getString(3);
                return !result.getBoolean(2)
                        && !"read committed".equals(isolation)
                        && !"read uncommitted".equals(isolation);
            }
        }
    }
}
