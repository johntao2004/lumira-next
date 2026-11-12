package com.lumira.platform.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Immutable per-artifact patterns. Manifest order remains the authorization precedence. */
final class PluginHttpRuleIndex {
    private record Entry(PluginManifest.Rule rule, PathPattern pattern) {}

    private final Map<String, List<Entry>> methods;

    PluginHttpRuleIndex(List<PluginManifest.Rule> rules) {
        var grouped = new HashMap<String, List<Entry>>();
        for (var rule : rules) {
            try {
                var pattern = PathPatternParser.defaultInstance.parse(rule.path());
                grouped.computeIfAbsent(rule.method(), ignored -> new ArrayList<>())
                        .add(new Entry(rule, pattern));
            } catch (org.springframework.web.util.pattern.PatternParseException invalid) {
                // Preserve the previous matcher: malformed declarations never match a request.
            }
        }
        var immutable = new HashMap<String, List<Entry>>();
        grouped.forEach((method, entries) -> immutable.put(method, List.copyOf(entries)));
        methods = Map.copyOf(immutable);
    }

    static PathContainer requestPath(String path) {
        if (path == null) return null;
        try {
            return PathContainer.parsePath(path);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    PluginManifest.Rule first(String method, PathContainer path) {
        if (path == null) return null;
        for (var entry : methods.getOrDefault(method, List.of())) {
            if (entry.pattern().matches(path)) return entry.rule();
        }
        return null;
    }

    boolean any(String method, PathContainer path, Predicate<PluginManifest.Rule> accepted) {
        if (path == null) return false;
        for (var entry : methods.getOrDefault(method, List.of())) {
            if (entry.pattern().matches(path) && accepted.test(entry.rule())) return true;
        }
        return false;
    }
}
