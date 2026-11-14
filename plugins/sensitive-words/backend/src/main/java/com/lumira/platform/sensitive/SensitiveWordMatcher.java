package com.lumira.platform.sensitive;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable Aho-Corasick matcher compiled into flat primitive transition tables. A single compiled
 * instance is shared by request threads; dictionary writes publish a replacement instance instead
 * of taking a lock on the hot path.
 */
public final class SensitiveWordMatcher {
    public static final int DEFAULT_MAX_MATCHES = 20;
    private static final int ASCII_LIMIT = 128;
    private static final char[] EMPTY_CHARS = new char[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final ScanResult EMPTY_SCAN_RESULT = new ScanResult(List.of(), false, 0, false);

    public record Entry(String word, String normalizedWord, String severity, String action) {}

    public record Match(String word, String severity, String action) {}

    public record ScanResult(
            List<Match> matches,
            boolean blocked,
            int totalMatchCount,
            boolean blockingMatchDisplayed) {
        public ScanResult {
            matches = List.copyOf(matches == null ? List.of() : matches);
        }
    }

    private final int stateCount;
    private final int[] asciiNext;
    private final char[][] nonAsciiKeys;
    private final int[][] nonAsciiTargets;
    private final int[] fail;
    private final int[] outputLink;
    private final int[][] directPatternIds;
    private final boolean[] stateBlocks;
    private final Match[] patternMatches;
    private final boolean[] patternBlocks;

    public SensitiveWordMatcher(List<Entry> entries) {
        List<BuildNode> nodes = new ArrayList<>();
        nodes.add(new BuildNode());
        Map<String, Integer> patternIds = new LinkedHashMap<>();
        List<Match> compiledMatches = new ArrayList<>();
        List<Boolean> compiledBlocks = new ArrayList<>();
        if (entries != null) {
            for (Entry entry : entries) {
                addPattern(entry, nodes, patternIds, compiledMatches, compiledBlocks);
            }
        }
        int count = nodes.size();
        this.stateCount = count;
        this.asciiNext = new int[count * ASCII_LIMIT];
        this.nonAsciiKeys = new char[count][];
        this.nonAsciiTargets = new int[count][];
        this.fail = new int[count];
        this.outputLink = new int[count];
        this.directPatternIds = new int[count][];
        this.stateBlocks = new boolean[count];
        this.patternMatches = compiledMatches.toArray(Match[]::new);
        this.patternBlocks = new boolean[compiledBlocks.size()];
        for (int i = 0; i < compiledBlocks.size(); i++) {
            this.patternBlocks[i] = compiledBlocks.get(i);
        }
        compileAutomaton(nodes);
    }

    public List<Match> find(String text) {
        return find(text, DEFAULT_MAX_MATCHES);
    }

    public List<Match> find(String text, int maxMatches) {
        return scan(text, maxMatches).matches();
    }

    /**
     * Keeps the response list bounded while scanning the entire normalized text for BLOCK entries.
     * A display limit must never decide whether submission is allowed.
     */
    public ScanResult scan(String text) {
        return scan(text, DEFAULT_MAX_MATCHES);
    }

    private ScanResult scan(String text, int maxMatches) {
        if (text == null || text.isEmpty() || patternMatches.length == 0 || stateCount <= 1) {
            return EMPTY_SCAN_RESULT;
        }
        String normalized = normalizeForMatch(text);
        int length = normalized.length();
        if (length == 0) return EMPTY_SCAN_RESULT;

        long[] seenBits = null;
        List<Match> matches = null;
        int totalMatchCount = 0;
        boolean blocked = false;
        boolean blockingMatchDisplayed = false;
        int state = 0;

        for (int index = 0; index < length; index++) {
            char ch = normalized.charAt(index);
            state = ch < ASCII_LIMIT ? asciiNext[(state << 7) | ch] : stepNonAscii(state, ch);
            if (stateBlocks[state]) blocked = true;
            if (directPatternIds[state].length == 0 && outputLink[state] == -1) continue;

            if (seenBits == null) {
                seenBits = new long[(patternMatches.length + 63) >>> 6];
                if (maxMatches > 0) {
                    matches = new ArrayList<>(Math.min(maxMatches, DEFAULT_MAX_MATCHES));
                }
            }
            for (int outState = state; outState != -1; outState = outputLink[outState]) {
                for (int patternId : directPatternIds[outState]) {
                    int wordIndex = patternId >>> 6;
                    long mask = 1L << patternId;
                    if ((seenBits[wordIndex] & mask) == 0L) {
                        seenBits[wordIndex] |= mask;
                        totalMatchCount++;
                        if (matches != null && matches.size() < maxMatches) {
                            Match match = patternMatches[patternId];
                            matches.add(match);
                            if (patternBlocks[patternId]) blockingMatchDisplayed = true;
                        }
                    }
                }
            }
        }
        if (totalMatchCount == 0) return EMPTY_SCAN_RESULT;
        return new ScanResult(
                matches == null ? List.of() : matches,
                blocked,
                totalMatchCount,
                blockingMatchDisplayed);
    }

    private int stepNonAscii(int state, char ch) {
        int cursor = state;
        while (true) {
            char[] keys = nonAsciiKeys[cursor];
            if (keys.length > 0) {
                int pos = Arrays.binarySearch(keys, ch);
                if (pos >= 0) return nonAsciiTargets[cursor][pos];
            }
            if (cursor == 0) return 0;
            cursor = fail[cursor];
        }
    }

    /**
     * NFKC + lowercase gives the same match semantics as the legacy service. Format characters
     * (zero-width space/joiners, BOM, soft hyphen, bidi marks) are invisible, so they are dropped
     * on both the stored word and the scanned text; otherwise one inserted between letters hides a
     * word.
     */
    public static String normalizeForMatch(String value) {
        if (value == null || value.isEmpty()) return "";
        int length = value.length();
        boolean asciiOnly = true;
        boolean alreadyCanonical = true;
        boolean prevSpace = true;
        for (int i = 0; i < length; i++) {
            char ch = value.charAt(i);
            if (ch >= ASCII_LIMIT) {
                asciiOnly = false;
                alreadyCanonical = false;
                break;
            }
            if (ch == ' ') {
                if (prevSpace || i == length - 1) alreadyCanonical = false;
                prevSpace = true;
            } else if (ch > ' ' && ch <= '~' && !(ch >= 'A' && ch <= 'Z')) {
                prevSpace = false;
            } else {
                alreadyCanonical = false;
                prevSpace = Character.isWhitespace(ch);
            }
        }
        if (alreadyCanonical) return value;
        if (asciiOnly) return compactAscii(value);

        String normalized =
                stripFormatCharacters(Normalizer.normalize(value, Normalizer.Form.NFKC))
                        .toLowerCase(Locale.ROOT);
        return compactUnicode(normalized);
    }

    private static String compactAscii(String value) {
        int length = value.length();
        StringBuilder compacted = new StringBuilder(length);
        boolean pendingSpace = false;
        for (int i = 0; i < length; i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch)) {
                pendingSpace = compacted.length() > 0;
            } else {
                if (pendingSpace) {
                    compacted.append(' ');
                    pendingSpace = false;
                }
                compacted.append(ch >= 'A' && ch <= 'Z' ? (char) (ch + ('a' - 'A')) : ch);
            }
        }
        return compacted.toString();
    }

    private static String compactUnicode(String value) {
        int length = value.length();
        StringBuilder compacted = new StringBuilder(length);
        boolean pendingSpace = false;
        for (int i = 0; i < length; i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch)) {
                pendingSpace = compacted.length() > 0;
            } else {
                if (pendingSpace) {
                    compacted.append(' ');
                    pendingSpace = false;
                }
                compacted.append(ch);
            }
        }
        return compacted.toString();
    }

    private static String stripFormatCharacters(String value) {
        int length = value.length();
        int firstFormat = -1;
        for (int i = 0; i < length; ) {
            int cp = value.codePointAt(i);
            if (Character.getType(cp) == Character.FORMAT) {
                firstFormat = i;
                break;
            }
            i += Character.charCount(cp);
        }
        if (firstFormat < 0) return value;
        StringBuilder kept = new StringBuilder(length);
        kept.append(value, 0, firstFormat);
        for (int i = firstFormat; i < length; ) {
            int cp = value.codePointAt(i);
            if (Character.getType(cp) != Character.FORMAT) kept.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return kept.toString();
    }

    private static void addPattern(
            Entry entry,
            List<BuildNode> nodes,
            Map<String, Integer> patternIds,
            List<Match> compiledMatches,
            List<Boolean> compiledBlocks) {
        if (entry == null || entry.normalizedWord() == null || entry.normalizedWord().isBlank()) {
            return;
        }
        String key = normalizeForMatch(entry.normalizedWord());
        if (key.isEmpty()) return;
        int state = 0;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            BuildNode current = nodes.get(state);
            Integer nextState = current.next.get(ch);
            if (nextState == null) {
                nextState = nodes.size();
                nodes.add(new BuildNode());
                current.next.put(ch, nextState);
            }
            state = nextState;
        }
        boolean blocks = "BLOCK".equals(entry.action());
        Integer patternId = patternIds.get(entry.normalizedWord());
        if (patternId == null) {
            patternId = compiledMatches.size();
            patternIds.put(entry.normalizedWord(), patternId);
            compiledMatches.add(new Match(entry.word(), entry.severity(), entry.action()));
            compiledBlocks.add(blocks);
        }
        BuildNode terminal = nodes.get(state);
        terminal.blocks |= blocks;
        if (!terminal.patternIds.contains(patternId)) {
            terminal.patternIds.add(patternId);
        }
    }

    private void compileAutomaton(List<BuildNode> nodes) {
        Arrays.fill(outputLink, -1);
        for (int state = 0; state < stateCount; state++) {
            BuildNode build = nodes.get(state);
            stateBlocks[state] = build.blocks;
            if (build.patternIds.isEmpty()) {
                directPatternIds[state] = EMPTY_INTS;
            } else {
                int[] ids = new int[build.patternIds.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = build.patternIds.get(i);
                directPatternIds[state] = ids;
            }
            int nonAsciiCount = 0;
            for (var edge : build.next.entrySet()) {
                char ch = edge.getKey();
                if (ch < ASCII_LIMIT) {
                    asciiNext[(state << 7) | ch] = edge.getValue();
                } else {
                    nonAsciiCount++;
                }
            }
            if (nonAsciiCount == 0) {
                nonAsciiKeys[state] = EMPTY_CHARS;
                nonAsciiTargets[state] = EMPTY_INTS;
            } else {
                char[] keys = new char[nonAsciiCount];
                int idx = 0;
                for (char ch : build.next.keySet()) {
                    if (ch >= ASCII_LIMIT) keys[idx++] = ch;
                }
                Arrays.sort(keys);
                int[] targets = new int[nonAsciiCount];
                for (int i = 0; i < nonAsciiCount; i++) {
                    targets[i] = build.next.get(keys[i]);
                }
                nonAsciiKeys[state] = keys;
                nonAsciiTargets[state] = targets;
            }
        }

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        BuildNode root = nodes.get(0);
        for (int child : root.next.values()) {
            fail[child] = 0;
            outputLink[child] = -1;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            int current = queue.remove();
            int fallbackState = fail[current];
            int baseOffset = current << 7;
            int failOffset = fallbackState << 7;
            for (int ch = 0; ch < ASCII_LIMIT; ch++) {
                int target = asciiNext[baseOffset | ch];
                if (target != 0) {
                    int f = asciiNext[failOffset | ch];
                    fail[target] = f;
                    stateBlocks[target] |= stateBlocks[f];
                    outputLink[target] = directPatternIds[f].length > 0 ? f : outputLink[f];
                    queue.add(target);
                } else {
                    asciiNext[baseOffset | ch] = asciiNext[failOffset | ch];
                }
            }
            char[] keys = nonAsciiKeys[current];
            int[] targets = nonAsciiTargets[current];
            for (int i = 0; i < keys.length; i++) {
                char ch = keys[i];
                int target = targets[i];
                int f = stepNonAscii(fallbackState, ch);
                fail[target] = f;
                stateBlocks[target] |= stateBlocks[f];
                outputLink[target] = directPatternIds[f].length > 0 ? f : outputLink[f];
                queue.add(target);
            }
        }
    }

    private static final class BuildNode {
        private final Map<Character, Integer> next = new HashMap<>();
        private final List<Integer> patternIds = new ArrayList<>();
        private boolean blocks;
    }
}
