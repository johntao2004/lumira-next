package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FilePreviewMediaTest {
    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "jpeg", "gif", "bmp"})
    void rendersRasterPixelsWithoutReturningUploadedMarkup(String extension) throws Exception {
        var image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 80, 40);
        graphics.dispose();
        var source = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, extension.equals("jpg") ? "jpeg" : extension, source));

        var preview =
                FilePreviewReader.read("sample." + extension.toUpperCase(), source.toByteArray());
        var pixels = pixels(preview);

        assertEquals(80, pixels.getWidth());
        assertEquals(40, pixels.getHeight());
        assertTrue(new Color(pixels.getRGB(20, 20)).getRed() > 240);
        assertEquals("", preview.text());
        assertEquals(1, preview.pageCount());
    }

    @Test
    void rejectsImageWithForgedExtensionOrInvalidPixels() throws Exception {
        var source = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", source);
        for (byte[] bytes :
                new byte[][] {
                    source.toByteArray(),
                    "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8)
                }) {
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> FilePreviewReader.read("fake.png", bytes));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
            assertEquals("filePreviewInvalid", error.getReason());
        }
    }

    @Test
    void rendersScannedPdfPagesAndRejectsOutOfRangePages() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument();
                var output = new ByteArrayOutputStream()) {
            for (Color color : new Color[] {Color.RED, Color.BLUE}) {
                var page = new PDPage(new PDRectangle(80, 40));
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.setNonStrokingColor(color);
                    stream.addRect(0, 0, 80, 40);
                    stream.fill();
                }
            }
            document.save(output);
            bytes = output.toByteArray();
        }

        var first = FilePreviewReader.read("scanned.pdf", bytes, 1);
        var second = FilePreviewReader.read("scanned.pdf", bytes, 2);
        assertEquals(2, first.pageCount());
        assertEquals(2, second.page());
        assertEquals(Color.RED.getRGB(), pixels(first).getRGB(20, 20));
        assertEquals(Color.BLUE.getRGB(), pixels(second).getRGB(20, 20));
        assertThrows(
                ResponseStatusException.class,
                () -> FilePreviewReader.read("scanned.pdf", bytes, 0));
        assertThrows(
                ResponseStatusException.class,
                () -> FilePreviewReader.read("scanned.pdf", bytes, 3));
    }

    @Test
    void downSamplesLargeImageToBoundedPreview() throws Exception {
        var source = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4096, 1024, BufferedImage.TYPE_INT_RGB), "png", source);
        var preview = FilePreviewReader.read("large.png", source.toByteArray());
        assertTrue(pixels(preview).getWidth() <= 2048);
        assertEquals(4, pixels(preview).getWidth() / pixels(preview).getHeight());
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "json", "log", "xml", "yaml", "yml"})
    void readsAdditionalTextFormatsAsText(String extension) {
        var source = "中文\n<script>never execute this</script>";
        var preview =
                FilePreviewReader.read(
                        "sample." + extension, source.getBytes(StandardCharsets.UTF_8));
        assertEquals(source, preview.text());
        assertNull(preview.imageDataUrl());
    }

    private static BufferedImage pixels(FilePreview preview) throws Exception {
        assertTrue(preview.imageDataUrl().startsWith("data:image/png;base64,"));
        return ImageIO.read(
                new ByteArrayInputStream(
                        Base64.getDecoder().decode(preview.imageDataUrl().split(",", 2)[1])));
    }
}
