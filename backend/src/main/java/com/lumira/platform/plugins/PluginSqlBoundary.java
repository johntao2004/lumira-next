package com.lumira.platform.plugins;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.postgresql.core.Parser;

/** Transaction boundaries belong to JDBC/Spring, never to a plugin SQL string. */
final class PluginSqlBoundary {
    private static final Set<String> CONTROL =
            Set.of("BEGIN", "START", "COMMIT", "END", "ABORT", "ROLLBACK", "SAVEPOINT", "RELEASE");
    private static final ValidationCache VALIDATED = new ValidationCache(512, 4096);

    static void validate(String sql) throws SQLException {
        if (sql == null) return;
        if (VALIDATED.contains(sql)) return;
        validateUncached(sql);
        VALIDATED.remember(sql);
    }

    static void validateUncached(String sql) throws SQLException {
        var statements = Parser.parseJdbcSql(sql, true, false, true, false, false);
        for (var query : statements) {
            var first = leadingWord(query.nativeSql, 0);
            if (CONTROL.contains(first.value())
                    || first.value().equals("PREPARE")
                            && leadingWord(query.nativeSql, first.end())
                                    .value()
                                    .equals("TRANSACTION")) throw rejected();
        }
    }

    /** Only deterministic SQL syntax is cached; admission and transaction fences always run. */
    static final class ValidationCache {
        private final AtomicReferenceArray<String> entries;
        private final int maximumLength;

        ValidationCache(int capacity, int maximumLength) {
            if (capacity < 1 || maximumLength < 1) throw new IllegalArgumentException();
            entries = new AtomicReferenceArray<>(capacity);
            this.maximumLength = maximumLength;
        }

        boolean contains(String sql) {
            return sql.length() <= maximumLength && sql.equals(entries.get(index(sql)));
        }

        void remember(String sql) {
            if (sql.length() <= maximumLength) entries.set(index(sql), sql);
        }

        private int index(String sql) {
            return (sql.hashCode() & Integer.MAX_VALUE) % entries.length();
        }
    }

    private record Word(String value, int end) {}

    private static Word leadingWord(String statement, int position) {
        while (position < statement.length()) {
            char c = statement.charAt(position);
            if (Character.isWhitespace(c)) {
                position++;
                continue;
            }
            if (statement.startsWith("--", position)) {
                while (position < statement.length()
                        && statement.charAt(position) != '\n'
                        && statement.charAt(position) != '\r') position++;
                continue;
            }
            if (statement.startsWith("/*", position)) {
                int depth = 1;
                position += 2;
                while (position < statement.length() && depth > 0) {
                    if (statement.startsWith("/*", position)) {
                        depth++;
                        position += 2;
                    } else if (statement.startsWith("*/", position)) {
                        depth--;
                        position += 2;
                    } else position++;
                }
                continue;
            }
            break;
        }
        int start = position;
        while (position < statement.length() && Character.isLetter(statement.charAt(position)))
            position++;
        return new Word(statement.substring(start, position).toUpperCase(Locale.ROOT), position);
    }

    private static SQLException rejected() {
        return new com.lumira.platform.common.PluginWorkFencedException();
    }
}
