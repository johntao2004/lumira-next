package com.lumira.platform.plugins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Immutable segment-radix-trie HTTP rule index. Evaluates candidates in O(path depth) while
 * strictly preserving manifest and catalog declaration precedence.
 */
final class PluginHttpRuleIndex {
    private static final int[] EMPTY_ORDERS = new int[0];

    record OwnedIndex(String owner, PluginHttpRuleIndex index) {}

    record CompiledRule(int order, String owner, PluginManifest.Rule rule, PathPattern pattern) {}

    private record ParsedRule(PluginManifest.Rule rule, PathPattern pattern) {}

    private final List<ParsedRule> parsedRules;
    private final Map<String, MethodTrie> methods;
    private final Map<String, List<String>> signedPostCallbacks;

    PluginHttpRuleIndex(List<PluginManifest.Rule> rules) {
        var parsed = new ArrayList<ParsedRule>(rules.size());
        var grouped = new HashMap<String, List<CompiledRule>>();
        var callbacks = new HashMap<String, Set<String>>();
        for (var rule : rules) {
            if (rule.signedCallback() && "POST".equals(rule.method()) && rule.path() != null) {
                callbacks.computeIfAbsent(rule.path(), ignored -> new LinkedHashSet<>()).add("");
            }
            try {
                var pattern = PathPatternParser.defaultInstance.parse(rule.path());
                parsed.add(new ParsedRule(rule, pattern));
                var list = grouped.computeIfAbsent(rule.method(), ignored -> new ArrayList<>());
                list.add(new CompiledRule(list.size(), "", rule, pattern));
            } catch (org.springframework.web.util.pattern.PatternParseException invalid) {
                // Preserve the previous matcher: malformed declarations never match a request.
            }
        }
        this.parsedRules = List.copyOf(parsed);
        this.methods = freezeMethods(grouped);
        this.signedPostCallbacks = freezeCallbacks(callbacks);
    }

    private PluginHttpRuleIndex(
            Map<String, MethodTrie> methods, Map<String, List<String>> signedPostCallbacks) {
        this.parsedRules = List.of();
        this.methods = methods;
        this.signedPostCallbacks = signedPostCallbacks;
    }

    static PluginHttpRuleIndex composite(
            List<OwnedIndex> sources, Predicate<PluginManifest.Rule> filter) {
        var grouped = new HashMap<String, List<CompiledRule>>();
        var callbacks = new HashMap<String, Set<String>>();
        for (var source : sources) {
            String owner = source.owner();
            PluginHttpRuleIndex idx = source.index();
            if (filter == null) {
                idx.signedPostCallbacks
                        .keySet()
                        .forEach(
                                path ->
                                        callbacks
                                                .computeIfAbsent(
                                                        path, ignored -> new LinkedHashSet<>())
                                                .add(owner));
            }
            for (var parsed : idx.parsedRules) {
                if (filter != null && !filter.test(parsed.rule())) continue;
                var list =
                        grouped.computeIfAbsent(
                                parsed.rule().method(), ignored -> new ArrayList<>());
                list.add(new CompiledRule(list.size(), owner, parsed.rule(), parsed.pattern()));
            }
        }
        return new PluginHttpRuleIndex(freezeMethods(grouped), freezeCallbacks(callbacks));
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
        PluginManifest.Rule[] matched = new PluginManifest.Rule[1];
        forEachMatching(
                method,
                path,
                compiled -> {
                    matched[0] = compiled.rule();
                    return true;
                });
        return matched[0];
    }

    boolean any(String method, PathContainer path, Predicate<PluginManifest.Rule> accepted) {
        return forEachMatching(method, path, compiled -> accepted.test(compiled.rule()));
    }

    boolean forEachMatching(
            String method, PathContainer path, Predicate<CompiledRule> stopWhenTrue) {
        if (path == null) return false;
        MethodTrie trie = methods.get(method);
        return trie != null && trie.forEachMatching(path, stopWhenTrue);
    }

    List<String> signedCallbackOwners(String path) {
        if (path == null) return List.of();
        return signedPostCallbacks.getOrDefault(path, List.of());
    }

    private static Map<String, MethodTrie> freezeMethods(Map<String, List<CompiledRule>> grouped) {
        if (grouped.isEmpty()) return Map.of();
        var out = new HashMap<String, MethodTrie>(grouped.size());
        grouped.forEach((method, entries) -> out.put(method, MethodTrie.build(entries)));
        return Map.copyOf(out);
    }

    private static Map<String, List<String>> freezeCallbacks(Map<String, Set<String>> callbacks) {
        if (callbacks.isEmpty()) return Map.of();
        var out = new HashMap<String, List<String>>(callbacks.size());
        callbacks.forEach((path, owners) -> out.put(path, List.copyOf(owners)));
        return Map.copyOf(out);
    }

    private static final class MethodTrie {
        private final CompiledRule[] rules;
        private final TrieNode root;

        private MethodTrie(CompiledRule[] rules, TrieNode root) {
            this.rules = rules;
            this.root = root;
        }

        static MethodTrie build(List<CompiledRule> entries) {
            CompiledRule[] compiled = entries.toArray(CompiledRule[]::new);
            BuildNode buildRoot = new BuildNode();
            for (CompiledRule entry : compiled) {
                insert(buildRoot, entry.rule().path(), entry.order());
            }
            return new MethodTrie(compiled, buildRoot.freeze());
        }

        boolean forEachMatching(PathContainer path, Predicate<CompiledRule> stopWhenTrue) {
            List<PathContainer.Element> elements = path.elements();
            Object candidates = collect(root, elements, nextSegmentIndex(elements, 0), rules, null);
            if (candidates == null) return false;
            if (candidates instanceof CompiledRule single) {
                return single.pattern().matches(path) && stopWhenTrue.test(single);
            }
            int[] buffer = (int[]) candidates;
            int count = buffer[0];
            for (int i = 1; i <= count; i++) {
                CompiledRule candidate = rules[buffer[i]];
                if (candidate.pattern().matches(path) && stopWhenTrue.test(candidate)) {
                    return true;
                }
            }
            return false;
        }

        private static void insert(BuildNode root, String rawPath, int order) {
            BuildNode cursor = root;
            int length = rawPath.length();
            int idx = 0;
            while (idx < length) {
                while (idx < length && rawPath.charAt(idx) == '/') idx++;
                if (idx >= length) break;
                int end = rawPath.indexOf('/', idx);
                if (end < 0) end = length;
                String segment = rawPath.substring(idx, end);
                if (isMultiSegmentWildcard(segment)) {
                    cursor.prefixWildcards.add(order);
                    return;
                }
                if (isLiteralSegment(segment)) {
                    cursor = cursor.literals.computeIfAbsent(segment, ignored -> new BuildNode());
                } else {
                    if (cursor.wildcard == null) cursor.wildcard = new BuildNode();
                    cursor = cursor.wildcard;
                }
                idx = end + 1;
            }
            cursor.terminals.add(order);
        }

        private static boolean isMultiSegmentWildcard(String segment) {
            return segment.contains("**") || segment.contains("{*");
        }

        private static boolean isLiteralSegment(String segment) {
            for (int i = 0, len = segment.length(); i < len; i++) {
                char ch = segment.charAt(i);
                if (ch == '{' || ch == '}' || ch == '*' || ch == '?' || ch == ';') {
                    return false;
                }
            }
            return true;
        }

        private static int nextSegmentIndex(List<PathContainer.Element> elements, int from) {
            for (int i = from, size = elements.size(); i < size; i++) {
                if (elements.get(i) instanceof PathContainer.PathSegment) return i;
            }
            return -1;
        }

        private static Object collect(
                TrieNode node,
                List<PathContainer.Element> elements,
                int segIndex,
                CompiledRule[] rules,
                Object acc) {
            acc = appendOrders(acc, node.prefixWildcardOrders, rules);
            if (segIndex < 0) {
                return appendOrders(acc, node.terminalOrders, rules);
            }
            String value = ((PathContainer.PathSegment) elements.get(segIndex)).valueToMatch();
            int nextSeg = nextSegmentIndex(elements, segIndex + 1);
            TrieNode literal = node.literals.get(value);
            if (literal != null) {
                acc = collect(literal, elements, nextSeg, rules, acc);
            }
            if (node.wildcard != null) {
                acc = collect(node.wildcard, elements, nextSeg, rules, acc);
            }
            return acc;
        }

        private static Object appendOrders(Object acc, int[] orders, CompiledRule[] rules) {
            for (int order : orders) {
                acc = appendOrder(acc, order, rules);
            }
            return acc;
        }

        private static Object appendOrder(Object acc, int order, CompiledRule[] rules) {
            if (acc == null) return rules[order];
            if (acc instanceof CompiledRule single) {
                int first = single.order();
                if (first == order) return single;
                int[] buf = new int[8];
                buf[0] = 2;
                if (first < order) {
                    buf[1] = first;
                    buf[2] = order;
                } else {
                    buf[1] = order;
                    buf[2] = first;
                }
                return buf;
            }
            int[] buf = (int[]) acc;
            int count = buf[0];
            int pos = Arrays.binarySearch(buf, 1, count + 1, order);
            if (pos >= 0) return buf;
            int insertAt = -pos - 1;
            if (count + 1 >= buf.length) {
                buf = Arrays.copyOf(buf, buf.length << 1);
            }
            System.arraycopy(buf, insertAt, buf, insertAt + 1, count + 1 - insertAt);
            buf[insertAt] = order;
            buf[0] = count + 1;
            return buf;
        }
    }

    private record TrieNode(
            Map<String, TrieNode> literals,
            TrieNode wildcard,
            int[] prefixWildcardOrders,
            int[] terminalOrders) {}

    private static final class BuildNode {
        private final Map<String, BuildNode> literals = new HashMap<>();
        private BuildNode wildcard;
        private final List<Integer> prefixWildcards = new ArrayList<>();
        private final List<Integer> terminals = new ArrayList<>();

        TrieNode freeze() {
            Map<String, TrieNode> frozenLiterals;
            if (literals.isEmpty()) {
                frozenLiterals = Map.of();
            } else {
                var map = new HashMap<String, TrieNode>(literals.size());
                literals.forEach((k, v) -> map.put(k, v.freeze()));
                frozenLiterals = Map.copyOf(map);
            }
            return new TrieNode(
                    frozenLiterals,
                    wildcard == null ? null : wildcard.freeze(),
                    toIntArray(prefixWildcards),
                    toIntArray(terminals));
        }

        private static int[] toIntArray(List<Integer> values) {
            if (values.isEmpty()) return EMPTY_ORDERS;
            int[] arr = new int[values.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = values.get(i);
            return arr;
        }
    }
}
