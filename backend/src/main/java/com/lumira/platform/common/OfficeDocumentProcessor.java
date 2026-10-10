package com.lumira.platform.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** POI-backed Word extraction. This class is only called by the isolated parser child process. */
public final class OfficeDocumentProcessor {
    public static final int MAX_PREVIEW_CODE_POINTS = 200_000;
    public static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;

    private OfficeDocumentProcessor() {}

    public static OfficeWorkerProtocol.Preview preview(String filename, byte[] bytes)
            throws IOException {
        if (bytes.length > MAX_INPUT_BYTES)
            throw new OfficeArchiveBudget.Exceeded("Input too large");
        String extension = extension(filename);
        String text =
                switch (extension) {
                    case "doc" -> legacyWord(bytes);
                    case "docx" -> openXmlWord(bytes);
                    default -> throw new IOException("Unsupported Office document");
                };
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= MAX_PREVIEW_CODE_POINTS)
            return new OfficeWorkerProtocol.Preview(text, false);
        return new OfficeWorkerProtocol.Preview(
                text.substring(0, text.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS)), true);
    }

    private static String legacyWord(byte[] bytes) throws IOException {
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(bytes))) {
            return extractor.getText();
        }
    }

    private static String openXmlWord(byte[] bytes) throws IOException {
        OfficeArchiveBudget.verify(bytes);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
