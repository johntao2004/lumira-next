package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FilePreviewReaderTest {
    @Test
    void readsUtf8TextAndMarkdownWithoutInterpretingMarkup() {
        var text = FilePreviewReader.read("notes.TXT", "<script>alert(1)</script>\nhello".getBytes(StandardCharsets.UTF_8));
        var markdown = FilePreviewReader.read("notes.markdown", "# Notes\n\n**safe text**".getBytes(StandardCharsets.UTF_8));

        assertEquals("<script>alert(1)</script>\nhello", text.text());
        assertEquals("# Notes\n\n**safe text**", markdown.text());
        assertFalse(text.truncated());
    }

    @Test
    void extractsTextFromDocxLocally() throws Exception {
        byte[] documentBytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("local DOCX preview content");
            document.write(output);
            documentBytes = output.toByteArray();
        }

        var preview = FilePreviewReader.read("report.docx", documentBytes);

        assertTrue(preview.text().contains("local DOCX preview content"));
        assertFalse(preview.truncated());
    }

    @Test
    void extractsTextFromLegacyBinaryDoc() throws Exception {
        byte[] documentBytes;
        try (var input = getClass().getResourceAsStream("/com/lumira/platform/files/legacy-preview.doc")) {
            assertNotNull(input);
            documentBytes = input.readAllBytes();
        }

        var preview = FilePreviewReader.read("legacy.doc", documentBytes);

        assertTrue(preview.text().contains("SAMPLE-CONTENT-42"), preview.text());
        assertFalse(preview.truncated());
    }

    @Test
    void capsPreviewByUnicodeCodePointsWithoutSplittingSurrogatePairs() {
        var source = "😀".repeat(FilePreviewReader.MAX_PREVIEW_CODE_POINTS + 1);

        var preview = FilePreviewReader.read("long.txt", source.getBytes(StandardCharsets.UTF_8));

        assertTrue(preview.truncated());
        assertEquals(FilePreviewReader.MAX_PREVIEW_CODE_POINTS, preview.text().codePointCount(0, preview.text().length()));
        assertFalse(Character.isHighSurrogate(preview.text().charAt(preview.text().length() - 1)));
    }

    @Test
    void rejectsBadEncodingInvalidOfficeAndUnsupportedTypesWithStableErrors() {
        var badText = assertThrows(ResponseStatusException.class,
                () -> FilePreviewReader.read("broken.txt", new byte[]{(byte) 0xc3, 0x28}));
        var badDoc = assertThrows(ResponseStatusException.class,
                () -> FilePreviewReader.read("broken.doc", "not a Word binary".getBytes(StandardCharsets.UTF_8)));
        var unsupported = assertThrows(ResponseStatusException.class,
                () -> FilePreviewReader.read("archive.zip", new byte[]{1, 2, 3}));

        assertEquals(HttpStatus.BAD_REQUEST, badText.getStatusCode());
        assertEquals("filePreviewInvalidEncoding", badText.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, badDoc.getStatusCode());
        assertEquals("filePreviewInvalid", badDoc.getReason());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, unsupported.getStatusCode());
        assertEquals("filePreviewUnsupported", unsupported.getReason());
    }
}
