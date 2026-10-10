package com.lumira.platform.plugins;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Declarative row deletion; never drops tables, migration history, files or host configuration. */
public final class PluginDataRemoval {
    public record Count(String label, long count) {}

    public static void validate(String id, List<PluginManifest.DataSet> sets) {
        var seen = new HashSet<String>();
        for (var set : sets) {
            if (set == null
                    || set.table() == null
                    || !set.table().matches("[a-z][a-z0-9_]{0,62}")
                    || set.label() == null
                    || set.label().isBlank()
                    || set.column() != null && !set.column().matches("[a-z][a-z0-9_]{0,62}")
                    || (set.column() == null) != (set.value() == null)
                    || !seen.add(set.table() + ":" + set.column() + ":" + set.value()))
                throw new IllegalArgumentException("invalidPluginDataScope");
            boolean owned = set.table().startsWith("plugin_" + id.replace('-', '_') + "_");
            owned |=
                    switch (id) {
                        case "feedback" ->
                                Set.of("platform_feedback", "platform_feedback_file")
                                                .contains(set.table())
                                        || set.table().equals("platform_account_draft")
                                                && "draft_key".equals(set.column())
                                                && "feedback.create".equals(set.value());
                        case "localization" ->
                                Set.of(
                                                        "platform_localization_entry",
                                                        "platform_localization_release")
                                                .contains(set.table())
                                        || set.table().equals("platform_localization_locale")
                                                && "built_in".equals(set.column())
                                                && "false".equals(set.value());
                        case "sensitive-words" ->
                                Set.of(
                                                "platform_sensitive_word",
                                                "platform_sensitive_role_policy",
                                                "platform_content_policy_hit")
                                        .contains(set.table());
                        default -> false;
                    };
            if (!owned || set.table().endsWith("_schema_history"))
                throw new IllegalArgumentException("invalidPluginDataScope");
        }
    }

    private static JdbcClient.StatementSpec statement(
            JdbcClient jdbc, String verb, PluginManifest.DataSet set) {
        var query =
                jdbc.sql(
                        verb
                                + " "
                                + set.table()
                                + (set.column() == null
                                        ? ""
                                        : " WHERE CAST(" + set.column() + " AS text)=:value"));
        return set.column() == null ? query : query.param("value", set.value());
    }

    public static List<Count> counts(JdbcClient jdbc, List<PluginManifest.DataSet> sets) {
        return sets.stream()
                .map(
                        s ->
                                new Count(
                                        s.label(),
                                        statement(jdbc, "SELECT count(*) FROM", s)
                                                .query(Long.class)
                                                .single()))
                .toList();
    }

    /** Caller supplies one transaction for all sets. */
    public static void delete(JdbcClient jdbc, List<PluginManifest.DataSet> sets) {
        for (var set : sets) statement(jdbc, "DELETE FROM", set).update();
    }

    private PluginDataRemoval() {}
}
