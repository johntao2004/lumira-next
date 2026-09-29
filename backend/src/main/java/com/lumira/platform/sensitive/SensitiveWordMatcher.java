package com.lumira.platform.sensitive;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Immutable Aho-Corasick matcher.  A single compiled instance is shared by
 * request threads; dictionary writes publish a replacement instance instead of
 * taking a lock on the hot path.
 */
public final class SensitiveWordMatcher {
    public static final int DEFAULT_MAX_MATCHES = 20;

    public record Entry(String word, String normalizedWord, String severity, String action) {}
    public record Match(String word, String severity, String action) {}
    public record ScanResult(List<Match> matches, boolean blocked, int totalMatchCount,
                             boolean blockingMatchDisplayed) {
        public ScanResult {
            matches = List.copyOf(matches == null ? List.of() : matches);
        }
    }

    private final Node root = new Node();

    public SensitiveWordMatcher(List<Entry> entries) {
        if (entries != null) {
            for (Entry entry : entries) add(entry);
        }
        buildFailureLinks();
    }

    public List<Match> find(String text) {
        return find(text, DEFAULT_MAX_MATCHES);
    }

    public List<Match> find(String text, int maxMatches) {
        return scan(text, maxMatches).matches();
    }

    /**
     * Keeps the response list bounded while scanning the entire normalized text
     * for BLOCK entries. A display limit must never decide whether submission is
     * allowed.
     */
    public ScanResult scan(String text) {
        return scan(text, DEFAULT_MAX_MATCHES);
    }

    private ScanResult scan(String text, int maxMatches) {
        if (text == null || text.isBlank()) return new ScanResult(List.of(), false, 0, false);
        String normalized = normalizeForMatch(text);
        if (normalized.isBlank()) return new ScanResult(List.of(), false, 0, false);
        Map<String, Match> matches = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        boolean blocked = false;
        Node current = root;
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            while (current != root && !current.next.containsKey(ch)) current = current.fail;
            current = current.next.getOrDefault(ch, root);
            for (Entry entry : current.outputs) {
                if ("BLOCK".equals(entry.action())) blocked = true;
                if (seen.add(entry.normalizedWord()) && matches.size() < maxMatches) {
                    matches.putIfAbsent(entry.normalizedWord(), new Match(entry.word(), entry.severity(), entry.action()));
                }
            }
        }
        boolean blockingMatchDisplayed = matches.values().stream().anyMatch(match -> "BLOCK".equals(match.action()));
        return new ScanResult(List.copyOf(matches.values()), blocked, seen.size(), blockingMatchDisplayed);
    }

    /** NFKC + lowercase conversion gives the same match semantics as the legacy service. */
    public static String normalizeForMatch(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).strip();
        StringBuilder compacted = new StringBuilder(normalized.length());
        boolean whitespace = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (!whitespace) compacted.append(' ');
                whitespace = true;
            } else {
                compacted.append(ch);
                whitespace = false;
            }
        }
        return compacted.toString().strip();
    }

    private void add(Entry entry) {
        if (entry == null || entry.normalizedWord() == null || entry.normalizedWord().isBlank()) return;
        Node node = root;
        for (int i = 0; i < entry.normalizedWord().length(); i++) {
            node = node.next.computeIfAbsent(entry.normalizedWord().charAt(i), ignored -> new Node());
        }
        node.outputs.add(entry);
    }

    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        for (Node child : root.next.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node current = queue.remove();
            for (Map.Entry<Character, Node> transition : current.next.entrySet()) {
                Node fallback = current.fail;
                char ch = transition.getKey();
                Node target = transition.getValue();
                while (fallback != root && !fallback.next.containsKey(ch)) fallback = fallback.fail;
                target.fail = fallback.next.getOrDefault(ch, root);
                target.outputs.addAll(target.fail.outputs);
                queue.add(target);
            }
        }
    }

    private static final class Node {
        private final Map<Character, Node> next = new HashMap<>();
        private final List<Entry> outputs = new ArrayList<>();
        private Node fail;
    }
}
