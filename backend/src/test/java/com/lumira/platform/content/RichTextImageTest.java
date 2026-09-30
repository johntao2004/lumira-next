package com.lumira.platform.content;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RichTextImageTest {
    @Test
    void keepsOnlyApplicationImagePaths() {
        var own =
                RichText.clean(
                                "<img src=\"/api/feedback/images/6f1c2a52-0a0a-4f4b-8c39-1d3b7f5a9e10\" alt=\"x\">")
                        .html();
        assertTrue(own.contains("/api/feedback/images/"), own);
        for (String src :
                new String[] {
                    "https://evil.example/t.png",
                    "http://evil.example/t.png",
                    "//evil.example/t.png",
                    "data:image/png;base64,AAAA",
                    "javascript:alert(1)",
                    "/other/x.png",
                    "/api/x?y=1"
                }) {
            assertFalse(RichText.clean("<img src=\"" + src + "\">").html().contains("src="), src);
        }
    }
}
