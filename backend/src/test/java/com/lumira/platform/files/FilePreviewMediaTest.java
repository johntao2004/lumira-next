package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
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
    void rendersUnembeddedAdobeGb1CidTextWithSystemChineseFallback() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument();
                var output = new ByteArrayOutputStream()) {
            var page = new PDPage(new PDRectangle(240, 80));
            page.setResources(new PDResources());
            document.addPage(page);
            var font = new PDType0Font(unembeddedAdobeGb1Font());
            page.getResources().put(COSName.getPDFName("F1"), font);
            try (var stream = new PDPageContentStream(document, page)) {
                String utf16be =
                        HexFormat.of().formatHex("中文金额".getBytes(StandardCharsets.UTF_16BE));
                stream.appendRawCommands("BT /F1 22 Tf 24 30 Td <" + utf16be + "> Tj ET\n");
            }
            document.save(output);
            bytes = output.toByteArray();
        }

        try (var document = Loader.loadPDF(bytes)) {
            var font =
                    (PDType0Font)
                            document.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            var descendant = (PDCIDFontType0) font.getDescendantFont();
            int code =
                    font.readCode(
                            new ByteArrayInputStream("中".getBytes(StandardCharsets.UTF_16BE)));
            assertTrue(descendant.hasGlyph(code), "Expected a mapped Chinese character glyph");
            var outlines = descendant.getPath(code).getPathIterator(null);
            int segments = 0;
            while (!outlines.isDone()) {
                segments++;
                outlines.next();
            }
            assertTrue(
                    segments > 10, "Expected Chinese character outlines, not a missing-glyph box");
        }

        var preview = FilePreviewReader.read("unembedded-adobe-gb1.pdf", bytes, 1);
        var image = pixels(preview);
        long darkPixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                var color = new Color(image.getRGB(x, y));
                if (color.getRed() < 150 && color.getGreen() < 150 && color.getBlue() < 150) {
                    darkPixels++;
                }
            }
        }
        assertTrue(darkPixels > 100, "Expected visible Chinese glyph outlines");
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

    private static COSDictionary unembeddedAdobeGb1Font() {
        var cidSystemInfo = new COSDictionary();
        cidSystemInfo.setString("Registry", "Adobe");
        cidSystemInfo.setString("Ordering", "GB1");
        cidSystemInfo.setInt("Supplement", 4);

        var descriptor = new COSDictionary();
        descriptor.setName("Type", "FontDescriptor");
        descriptor.setName("FontName", "AdobeSongStd-Light");
        descriptor.setInt("Flags", 2);
        descriptor.setItem("FontBBox", cosFloats(-1000, -300, 2000, 1000));
        descriptor.setFloat("ItalicAngle", 0);
        descriptor.setFloat("Ascent", 900);
        descriptor.setFloat("Descent", -200);
        descriptor.setFloat("CapHeight", 700);
        descriptor.setFloat("StemV", 80);

        var descendant = new COSDictionary();
        descendant.setName("Type", "Font");
        descendant.setName("Subtype", "CIDFontType0");
        descendant.setName("BaseFont", "AdobeSongStd-Light");
        descendant.setItem("CIDSystemInfo", cidSystemInfo);
        descendant.setItem("FontDescriptor", descriptor);

        var descendants = new COSArray();
        descendants.add(descendant);
        var type0 = new COSDictionary();
        type0.setName("Type", "Font");
        type0.setName("Subtype", "Type0");
        type0.setName("BaseFont", "AdobeSongStd-Light");
        type0.setName("Encoding", "UniGB-UTF16-H");
        type0.setItem("DescendantFonts", descendants);
        return type0;
    }

    private static COSArray cosFloats(float... values) {
        var array = new COSArray();
        for (float value : values) array.add(new COSFloat(value));
        return array;
    }
}
