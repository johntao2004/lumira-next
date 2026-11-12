package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class PluginSqlBoundaryTest {
    @Test
    void repeatedCachedSqlStillRejectsEveryTransactionBoundary() throws Exception {
        for (String sql :
                List.of(
                        "SELECT 1; COMMIT",
                        "/* outer /* inner */ comment */ BEGIN",
                        "-- comment\nROLLBACK TO SAVEPOINT sample",
                        "SELECT 1; PREPARE /* comment */ TRANSACTION 'sample'",
                        "START TRANSACTION",
                        "END",
                        "ABORT",
                        "SAVEPOINT sample",
                        "RELEASE SAVEPOINT sample")) {
            for (int i = 0; i < 3; i++) {
                SQLException error =
                        assertThrows(SQLException.class, () -> PluginSqlBoundary.validate(sql));
                assertEquals("40001", error.getSQLState());
            }
        }
    }

    @Test
    void quotedWordsAndNestedCommentsKeepTheirSqlMeaning() throws Exception {
        for (String sql :
                List.of(
                        "SELECT 'COMMIT; BEGIN'",
                        "SELECT $$ROLLBACK; END$$",
                        "SELECT 1 /* outer /* COMMIT */ ROLLBACK */; SELECT 2",
                        "SELECT \"COMMIT\" FROM example",
                        "SELECT ?::text, 'it''s COMMIT'")) {
            PluginSqlBoundary.validateUncached(sql);
            PluginSqlBoundary.validate(sql);
            PluginSqlBoundary.validate(new String(sql));
        }
        PluginSqlBoundary.validate(null);
    }

    @Test
    void boundedCacheUsesExactSqlEvenForHashCollisions() {
        var cache = new PluginSqlBoundary.ValidationCache(1, 20);
        String first = "SELECT 'Aa'";
        String second = "SELECT 'BB'";
        assertEquals(first.hashCode(), second.hashCode());
        cache.remember(first);
        assertTrue(cache.contains(new String(first)));
        assertFalse(cache.contains(second));
        cache.remember(second);
        assertFalse(cache.contains(first));
        assertTrue(cache.contains(second));
        String oversized = "SELECT '" + "x".repeat(30) + "'";
        cache.remember(oversized);
        assertFalse(cache.contains(oversized));
        assertTrue(cache.contains(second));
    }

    @Test
    void concurrentEvictionCannotAdmitAControlStatement() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks =
                    java.util.stream.IntStream.range(0, 64)
                            .<java.util.concurrent.Callable<Boolean>>mapToObj(
                                    worker ->
                                            () -> {
                                                for (int i = 0; i < 100; i++) {
                                                    PluginSqlBoundary.validate(
                                                            "SELECT " + (worker * 100 + i));
                                                    assertThrows(
                                                            SQLException.class,
                                                            () ->
                                                                    PluginSqlBoundary.validate(
                                                                            "SELECT 1; COMMIT"));
                                                }
                                                return true;
                                            })
                            .toList();
            for (var result : executor.invokeAll(tasks)) assertTrue(result.get());
        }
    }
}
