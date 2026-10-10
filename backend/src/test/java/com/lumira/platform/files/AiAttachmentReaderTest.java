package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AiAttachmentPort;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** DOCX attachment examples use the configured real Office worker; image/PDF tests stay local. */
class AiAttachmentReaderTest {
    private final UUID id = UUID.randomUUID();

    @Test
    void readsDocumentsAndBoundsUnicodeWithoutSplittingSurrogates() throws Exception {
        for (String extension : new String[] {"txt", "md", "csv", "json", "log"}) {
            var read =
                    AiAttachmentReader.read(
                            id, "report." + extension, "文档内容 42".getBytes(StandardCharsets.UTF_8));
            assertEquals("文档内容 42", read.text());
            assertFalse(read.image());
        }
        var longText =
                AiAttachmentReader.read(
                        id,
                        "long.txt",
                        ("a".repeat(19999) + "😀more").getBytes(StandardCharsets.UTF_8));
        assertTrue(longText.truncated());
        assertEquals(19999, longText.text().length());
        try (var doc = new XWPFDocument();
                var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Word contract sample");
            doc.write(out);
            assertTrue(
                    AiAttachmentReader.read(id, "合同附件-摘要.docx", out.toByteArray())
                            .text()
                            .contains("Word contract sample"));
        }
    }

    @Test
    void reportsTemporaryOfficeWorkerFailureWithoutCallingTheAttachmentInvalid() throws Exception {
        String oldUrl = System.getProperty("lumira.office.worker.url");
        String oldToken = System.getProperty("lumira.office.worker.token");
        try {
            System.setProperty("lumira.office.worker.url", "http://127.0.0.1:1");
            System.setProperty("lumira.office.worker.token", "a".repeat(96));
            byte[] bytes;
            try (var doc = new XWPFDocument();
                    var out = new ByteArrayOutputStream()) {
                doc.createParagraph().createRun().setText("temporary worker error case");
                doc.write(out);
                bytes = out.toByteArray();
            }

            var unavailable =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> AiAttachmentReader.read(id, "合同附件.docx", bytes));

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
            assertEquals("unavailable", unavailable.getReason());
        } finally {
            restoreProperty("lumira.office.worker.url", oldUrl);
            restoreProperty("lumira.office.worker.token", oldToken);
        }
    }

    @Test
    void extractsPdfTextAndRejectsScannedOnlyPdf() throws Exception {
        try (var doc = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(20, 700);
                stream.showText("PDF attachment total 726");
                stream.endText();
            }
            doc.save(out);
            assertTrue(
                    AiAttachmentReader.read(id, "report.pdf", out.toByteArray())
                            .text()
                            .contains("total 726"));
        }
        try (var doc = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            assertEquals(
                    "aiAttachmentEmpty",
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            AiAttachmentReader.read(
                                                    id, "scan.pdf", out.toByteArray()))
                            .getReason());
        }
    }

    @Test
    void normalizesImagesButRejectsSpoofedImagesAndOversizeFiles() throws Exception {
        var image = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        var result = AiAttachmentReader.read(id, "image.png", out.toByteArray());
        assertTrue(result.image());
        assertTrue(result.imageDataUrl().startsWith("data:image/png;base64,"));
        var png =
                java.util.Base64.getDecoder()
                        .decode(result.imageDataUrl().substring("data:image/png;base64,".length()));
        assertEquals(1536, ImageIO.read(new ByteArrayInputStream(png)).getWidth());
        assertEquals(
                "aiAttachmentInvalid",
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        AiAttachmentReader.read(
                                                id, "fake.png", "not an image".getBytes()))
                        .getReason());
        assertEquals(
                "aiAttachmentTooLarge",
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        AiAttachmentReader.read(
                                                id,
                                                "large.txt",
                                                new byte[AiAttachmentPort.MAX_BYTES + 1]))
                        .getReason());
        assertEquals(
                "aiAttachmentUnsupported",
                assertThrows(
                                ResponseStatusException.class,
                                () -> AiAttachmentReader.read(id, "archive.zip", new byte[] {1}))
                        .getReason());
    }

    @Test
    void adapterResolvesOnlyOwnedReadyFilesBeforeReading() {
        var files = mock(FileService.class);
        when(files.find("other-user", id))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound"));
        assertThrows(
                ResponseStatusException.class,
                () -> new AiAttachmentAdapter(files).read("other-user", id));
        verify(files).find("other-user", id);
        verifyNoMoreInteractions(files);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
