package com.lumira.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts small, current-schema fixtures from the single global initialization baseline. */
public final class TestSchema {
    private static final String BASELINE_RESOURCE =
            "/db/migration/B52__platform_initialization.sql";
    private static final Pattern TABLE_PATTERN =
            Pattern.compile(
                    "(?ms)^CREATE TABLE public\\.([A-Za-z_][A-Za-z0-9_$]*) \\((.*?)^\\);\\s*");
    private static final Pattern CONSTRAINT_PATTERN =
            Pattern.compile(
                    "(?ms)^ALTER TABLE ONLY public\\.([A-Za-z_][A-Za-z0-9_$]*)\\s+ADD"
                            + " (CONSTRAINT\\s+.*?);\\s*$");
    private static final Pattern INDEX_PATTERN =
            Pattern.compile(
                    "(?ms)^CREATE (?:UNIQUE )?INDEX [^\\s]+ ON"
                            + " public\\.([A-Za-z_][A-Za-z0-9_$]*)\\b.*?;\\s*$");
    private static final Pattern LOCAL_CONSTRAINT_PATTERN =
            Pattern.compile("(?is)^CONSTRAINT\\s+[^\\s]+\\s+(?:PRIMARY KEY|UNIQUE|CHECK)\\b.*");
    private static final Pattern FOREIGN_KEY_CONSTRAINT_PATTERN =
            Pattern.compile("(?is)^CONSTRAINT\\s+[^\\s]+\\s+FOREIGN KEY\\b.*");

    private static final Map<String, TableDefinition> TABLES = loadDefinitions();

    private TestSchema() {}

    /**
     * Returns one current CREATE TABLE statement without a schema qualifier. Primary keys, unique
     * constraints, and checks are folded into the statement; foreign keys are omitted so tests can
     * create only the tables they need in isolated schemas.
     */
    public static String table(String name) {
        return definition(name).createTable();
    }

    /** Returns only the baseline's standalone indexes for one table, without a schema qualifier. */
    public static List<String> indexes(String name) {
        return definition(name).indexes();
    }

    /** Returns baseline foreign-key ALTER statements for one table, without schema qualifiers. */
    public static List<String> foreignKeys(String name) {
        return definition(name).foreignKeys();
    }

    /** Returns only baseline seed INSERT statements for one table, without a schema qualifier. */
    public static List<String> seeds(String name) {
        return seedStatements(name, readBaseline());
    }

    static List<String> seedStatements(String name, String source) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_$]*"))
            throw new IllegalArgumentException("Invalid platform baseline table name: " + name);
        if (source == null) throw new IllegalArgumentException("Baseline SQL must not be null");

        List<String> statements = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            int start = skipLeadingTrivia(source, cursor);
            if (start >= source.length()) break;

            int end = findStatementEnd(source, start);
            String statement = source.substring(start, end).strip();
            if (isSeedInsert(statement, name)) statements.add(stripPublicQualifier(statement));
            cursor = end;
        }
        return List.copyOf(statements);
    }

    private static boolean isSeedInsert(String statement, String name) {
        Pattern insertPattern =
                Pattern.compile(
                        "(?is)^INSERT\\s+INTO\\s+public\\s*\\.\\s*"
                                + Pattern.quote(name)
                                + "(?=\\s|\\()");
        return insertPattern.matcher(statement).find();
    }

    private static int skipLeadingTrivia(String source, int start) {
        int cursor = start;
        while (cursor < source.length()) {
            if (Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            } else if (source.startsWith("--", cursor)) {
                int newline = source.indexOf('\n', cursor + 2);
                cursor = newline < 0 ? source.length() : newline + 1;
            } else if (source.startsWith("/*", cursor)) {
                cursor = skipBlockComment(source, cursor);
            } else {
                break;
            }
        }
        return cursor;
    }

    private static int findStatementEnd(String source, int start) {
        int cursor = start;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (current == '\'') {
                cursor = skipSingleQuotedString(source, cursor);
            } else if (current == '"') {
                cursor = skipQuotedIdentifier(source, cursor);
            } else if (source.startsWith("--", cursor)) {
                int newline = source.indexOf('\n', cursor + 2);
                cursor = newline < 0 ? source.length() : newline + 1;
            } else if (source.startsWith("/*", cursor)) {
                cursor = skipBlockComment(source, cursor);
            } else if (current == '$') {
                String delimiter = dollarQuoteDelimiter(source, cursor);
                if (delimiter == null) {
                    cursor++;
                } else {
                    int closing = source.indexOf(delimiter, cursor + delimiter.length());
                    cursor = closing < 0 ? source.length() : closing + delimiter.length();
                }
            } else if (current == ';') {
                return cursor + 1;
            } else {
                cursor++;
            }
        }
        return source.length();
    }

    private static int skipSingleQuotedString(String source, int quote) {
        boolean escapeString =
                quote > 0
                        && (source.charAt(quote - 1) == 'E' || source.charAt(quote - 1) == 'e')
                        && (quote < 2 || !isIdentifierPart(source.charAt(quote - 2)));
        int cursor = quote + 1;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (escapeString && current == '\\' && cursor + 1 < source.length()) {
                cursor += 2;
            } else if (current == '\'') {
                if (cursor + 1 < source.length() && source.charAt(cursor + 1) == '\'') cursor += 2;
                else return cursor + 1;
            } else {
                cursor++;
            }
        }
        return source.length();
    }

    private static int skipQuotedIdentifier(String source, int quote) {
        int cursor = quote + 1;
        while (cursor < source.length()) {
            if (source.charAt(cursor) == '"') {
                if (cursor + 1 < source.length() && source.charAt(cursor + 1) == '"') cursor += 2;
                else return cursor + 1;
            } else {
                cursor++;
            }
        }
        return source.length();
    }

    private static int skipBlockComment(String source, int start) {
        int cursor = start + 2;
        int depth = 1;
        while (cursor < source.length() && depth > 0) {
            if (source.startsWith("/*", cursor)) {
                depth++;
                cursor += 2;
            } else if (source.startsWith("*/", cursor)) {
                depth--;
                cursor += 2;
            } else {
                cursor++;
            }
        }
        return cursor;
    }

    private static String dollarQuoteDelimiter(String source, int start) {
        int cursor = start + 1;
        if (cursor < source.length() && source.charAt(cursor) == '$') return "$$";
        if (cursor >= source.length() || !isIdentifierStart(source.charAt(cursor))) return null;
        cursor++;
        while (cursor < source.length() && isIdentifierPart(source.charAt(cursor))) cursor++;
        return cursor < source.length() && source.charAt(cursor) == '$'
                ? source.substring(start, cursor + 1)
                : null;
    }

    private static boolean isIdentifierStart(char character) {
        return Character.isLetter(character) || character == '_';
    }

    private static boolean isIdentifierPart(char character) {
        return isIdentifierStart(character) || Character.isDigit(character) || character == '$';
    }

    private static TableDefinition definition(String name) {
        TableDefinition definition = TABLES.get(name);
        if (definition == null)
            throw new IllegalArgumentException("Unknown table in platform baseline: " + name);
        return definition;
    }

    private static Map<String, TableDefinition> loadDefinitions() {
        String source = readBaseline();
        Map<String, String> tableBodies = new LinkedHashMap<>();
        Matcher tableMatcher = TABLE_PATTERN.matcher(source);
        while (tableMatcher.find()) tableBodies.put(tableMatcher.group(1), tableMatcher.group(2));
        if (tableBodies.isEmpty())
            throw new IllegalStateException("No table definitions found in platform baseline");

        Map<String, List<String>> localConstraints = new LinkedHashMap<>();
        Map<String, List<String>> foreignKeys = new LinkedHashMap<>();
        Matcher constraintMatcher = CONSTRAINT_PATTERN.matcher(source);
        while (constraintMatcher.find()) {
            String constraint = stripPublicQualifier(constraintMatcher.group(2).strip());
            if (LOCAL_CONSTRAINT_PATTERN.matcher(constraint).matches())
                localConstraints
                        .computeIfAbsent(constraintMatcher.group(1), ignored -> new ArrayList<>())
                        .add(constraint);
            else if (FOREIGN_KEY_CONSTRAINT_PATTERN.matcher(constraint).matches())
                foreignKeys
                        .computeIfAbsent(constraintMatcher.group(1), ignored -> new ArrayList<>())
                        .add(
                                "ALTER TABLE ONLY "
                                        + constraintMatcher.group(1)
                                        + "\n    ADD "
                                        + constraint
                                        + ";");
        }

        Map<String, List<String>> indexes = new LinkedHashMap<>();
        Matcher indexMatcher = INDEX_PATTERN.matcher(source);
        while (indexMatcher.find()) {
            String statement = stripPublicQualifier(indexMatcher.group().strip());
            indexes.computeIfAbsent(indexMatcher.group(1), ignored -> new ArrayList<>())
                    .add(statement);
        }

        Map<String, TableDefinition> definitions = new LinkedHashMap<>();
        tableBodies.forEach(
                (name, body) -> {
                    StringBuilder createTable =
                            new StringBuilder("CREATE TABLE ")
                                    .append(name)
                                    .append(" (")
                                    .append(stripPublicQualifier(body).stripTrailing());
                    for (String constraint : localConstraints.getOrDefault(name, List.of()))
                        createTable.append(",\n    ").append(constraint);
                    createTable.append("\n);");
                    definitions.put(
                            name,
                            new TableDefinition(
                                    createTable.toString(),
                                    List.copyOf(indexes.getOrDefault(name, List.of())),
                                    List.copyOf(foreignKeys.getOrDefault(name, List.of()))));
                });
        return Map.copyOf(definitions);
    }

    private static String readBaseline() {
        try (var stream = TestSchema.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (stream == null)
                throw new IllegalStateException("Missing platform initialization baseline");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read platform initialization baseline", error);
        }
    }

    private static String stripPublicQualifier(String statement) {
        return statement.replaceAll("(?i)(?<![A-Za-z0-9_$])public\\.", "");
    }

    private record TableDefinition(
            String createTable, List<String> indexes, List<String> foreignKeys) {}
}
