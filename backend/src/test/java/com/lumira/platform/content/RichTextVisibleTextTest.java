package com.lumira.platform.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class RichTextVisibleTextTest {
    @Test
    void inlineFormattingDoesNotSplitAWordButBlocksDo() {
        assertEquals(
                "bad word",
                RichText.visibleText("<p>b<b>a</b>d <i>wor</i><span>d</span></p>").strip());
        String blocks = RichText.visibleText("<p>ab</p><p>cd</p>");
        assertFalse(blocks.contains("abcd"), blocks);
        assertEquals("a b", RichText.visibleText("a<br>b").replaceAll("\\s+", " ").strip());
    }

    @Test
    void entitiesAreDecodedAndMarkupIsNotReadAsText() {
        assertEquals("qa-x", RichText.visibleText("<p>q&#97;&#x2d;x</p>").strip());
        assertEquals("", RichText.visibleText(null));
        assertFalse(RichText.visibleText("<p onclick=\"evil()\">hi</p>").contains("evil"));
    }
}
