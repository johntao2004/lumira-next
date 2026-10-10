package com.lumira.platform.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SensitiveWordMatcherTest {
    @Test
    void normalizesCompatibilityFormsAndCaseBeforeMatching() {
        var word = SensitiveWordMatcher.normalizeForMatch("Ｂａｄ");
        var matcher =
                new SensitiveWordMatcher(
                        List.of(new SensitiveWordMatcher.Entry("Bad", word, "HIGH", "BLOCK")));

        var matches = matcher.find("prefix ＢＡＤ suffix");

        assertEquals(1, matches.size());
        assertEquals("Bad", matches.getFirst().word());
        assertEquals("HIGH", matches.getFirst().severity());
    }

    @Test
    void invisibleFormatCharactersCannotHideAWord() {
        var matcher =
                new SensitiveWordMatcher(
                        List.of(
                                new SensitiveWordMatcher.Entry(
                                        "bad",
                                        SensitiveWordMatcher.normalizeForMatch("bad"),
                                        "HIGH",
                                        "BLOCK")));

        for (String hidden :
                List.of("b\u200Bad", "ba\u200Dd", "b\u00ADad", "\uFEFFbad", "b\u2060a\u200Cd")) {
            assertEquals(
                    1,
                    matcher.find("x " + hidden + " y").size(),
                    () -> "not detected: " + hidden.codePoints().boxed().toList());
        }
        // A stored word that itself contains an invisible character still matches its plain form.
        var stored =
                new SensitiveWordMatcher(
                        List.of(
                                new SensitiveWordMatcher.Entry(
                                        "b\u200Bad", "b\u200Bad", "HIGH", "BLOCK")));
        assertEquals(1, stored.find("bad").size());
        assertEquals("bad", SensitiveWordMatcher.normalizeForMatch("B\u200BAD"));
    }

    @Test
    void emitsOverlappingPatternsOnceAndHonorsMaximum() {
        var matcher =
                new SensitiveWordMatcher(
                        List.of(
                                entry("ab", "LOW", "LOG_ONLY"),
                                entry("abc", "HIGH", "BLOCK"),
                                entry("bc", "MEDIUM", "BLOCK")));

        var matches = matcher.find("abc", 10);

        assertEquals(
                List.of("ab", "abc", "bc"),
                matches.stream().map(SensitiveWordMatcher.Match::word).toList());
        assertEquals(2, matcher.find("abc", 2).size());
        assertTrue(matches.stream().anyMatch(match -> "BLOCK".equals(match.action())));
    }

    @Test
    void scansPastDisplayCapAndKeepsOverlappingMatchesFromHidingTailBlock() {
        var entries = new ArrayList<SensitiveWordMatcher.Entry>();
        entries.add(entry("DEMO_ONLY_F01", "LOW", "LOG_ONLY"));
        var text = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            String word = "DEMO_ONLY_F01_20260928_LOG_%02d".formatted(i);
            entries.add(entry(word, "LOW", "LOG_ONLY"));
            text.append(word).append(' ');
        }
        entries.add(entry("DEMO_ONLY_TAIL_BLOCK_20260928", "HIGH", "BLOCK"));
        text.append("DEMO_ONLY_TAIL_BLOCK_20260928");

        var scan = new SensitiveWordMatcher(entries).scan(text.toString());

        assertEquals(SensitiveWordMatcher.DEFAULT_MAX_MATCHES, scan.matches().size());
        assertEquals(22, scan.totalMatchCount());
        assertTrue(scan.matches().stream().allMatch(match -> "LOG_ONLY".equals(match.action())));
        assertTrue(scan.blocked());
        assertFalse(scan.blockingMatchDisplayed());
    }

    @Test
    void collapsesWhitespaceAndReturnsEmptyForBlankInput() {
        assertEquals("foo bar", SensitiveWordMatcher.normalizeForMatch("  FOO\u00a0\nbar  "));
        var matcher = new SensitiveWordMatcher(List.of(entry("foo bar", "MEDIUM", "BLOCK")));
        assertEquals(1, matcher.find("foo\tbar").size());
        assertTrue(matcher.find(null).isEmpty());
        assertTrue(matcher.find("   ").isEmpty());
        assertTrue(matcher.find("foo bar", 0).isEmpty());
    }

    private static SensitiveWordMatcher.Entry entry(String word, String severity, String action) {
        return new SensitiveWordMatcher.Entry(
                word, SensitiveWordMatcher.normalizeForMatch(word), severity, action);
    }
}
