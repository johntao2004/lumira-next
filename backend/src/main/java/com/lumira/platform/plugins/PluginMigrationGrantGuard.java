package com.lumira.platform.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bundled plugin initialization may copy grants the account already holds. Any other migration is
 * rejected when it writes core grant tables, so an uploaded package cannot mint platform authority.
 */
public final class PluginMigrationGrantGuard {
    static final Set<String> BUNDLED_GRANT_SCRIPT_DIGESTS =
            Set.of(
                    "444b8d2194423d71e66b2aec6695bbc08e0a0a4c0fbf6c68171481bc35c6badf",
                    "181e616e3824cbedea74b0ea532e949c09f847af138b0f75f312503010b4cd5e",
                    "d5a7010481e18b1edc24c961a8bcf024798903ad1dfdc60b20b402a936167d6f",
                    "3bc8baff4629ee2c650f0e894c1b9315f348cc5c0fa8df182edd1539d9086f2e",
                    "0236e34ba123382c1bdd94664e9a37eae680581e03a4d7c9e8945927e1d6b7d3",
                    "c6d276ac0c534f05080dfaa4c915582d18a7c018c69a7c7588463827720486a4",
                    "c5176b5e64682449b00cea4a0fbdae060b8329172b91c8421b1e55d40d428167");

    private static final Pattern GRANT_TABLE =
            Pattern.compile("\\bplatform_(?:role_authority|authority|user_role)\\b");
    private static final Pattern WRITE =
            Pattern.compile(
                    "\\b(?:insert|update|delete|truncate|merge|alter|drop|grant|revoke|copy|execute|perform|call)\\b");

    private PluginMigrationGrantGuard() {}

    public static void rejectUntrustedMutations(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path sql : files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                byte[] bytes = Files.readAllBytes(sql);
                if (BUNDLED_GRANT_SCRIPT_DIGESTS.contains(sha256(bytes))) continue;
                if (mutatesCoreGrantTables(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
                    throw new IllegalStateException(
                            "pluginMigrationGrantMutationDenied:" + sql.getFileName());
            }
        } catch (IOException failure) {
            throw new IllegalStateException("pluginMigrationGrantMutationDenied", failure);
        }
    }

    static boolean mutatesCoreGrantTables(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String source = stripComments(sql);
        var statement = new StringBuilder();
        boolean quote = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '\'') {
                quote = !quote;
                statement.append(current);
                if (!quote && index + 1 < source.length() && source.charAt(index + 1) == '\'') {
                    statement.append('\'');
                    index++;
                    quote = true;
                }
                continue;
            }
            if (current == ';' && !quote) {
                if (statementMutates(statement.toString())) return true;
                statement.setLength(0);
                continue;
            }
            statement.append(current);
        }
        return statementMutates(statement.toString());
    }

    private static boolean statementMutates(String statement) {
        String normalized = statement.toLowerCase(Locale.ROOT);
        if (!GRANT_TABLE.matcher(normalized).find()) return false;
        if (stringLiteralMentionsGrantTable(statement)) return true;
        return WRITE.matcher(normalized).find();
    }

    private static boolean stringLiteralMentionsGrantTable(String statement) {
        var literal = new StringBuilder();
        boolean quote = false;
        for (int index = 0; index < statement.length(); index++) {
            char current = statement.charAt(index);
            if (current == '\'') {
                if (quote && index + 1 < statement.length() && statement.charAt(index + 1) == '\'') {
                    literal.append('\'');
                    index++;
                    continue;
                }
                if (quote && GRANT_TABLE.matcher(literal.toString().toLowerCase(Locale.ROOT)).find())
                    return true;
                quote = !quote;
                literal.setLength(0);
                continue;
            }
            if (quote) literal.append(current);
        }
        return false;
    }

    private static String stripComments(String sql) {
        var result = new StringBuilder(sql.length());
        boolean quote = false;
        boolean line = false;
        boolean block = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (line) {
                if (current == '\n') {
                    line = false;
                    result.append('\n');
                }
                continue;
            }
            if (block) {
                if (current == '*' && next == '/') {
                    block = false;
                    index++;
                }
                continue;
            }
            if (!quote && current == '-' && next == '-') {
                line = true;
                index++;
                continue;
            }
            if (!quote && current == '/' && next == '*') {
                block = true;
                index++;
                continue;
            }
            if (current == '\'') quote = !quote;
            result.append(current);
        }
        return result.toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
