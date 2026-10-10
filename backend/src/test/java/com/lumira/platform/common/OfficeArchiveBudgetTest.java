package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class OfficeArchiveBudgetTest {
    @Test
    void allowsSmallOfficeXmlPartsWithinTheInputBudget() throws Exception {
        byte[] archive =
                archive(
                        new Part("[Content_Types].xml", "<Types><Default/></Types>"),
                        new Part(
                                "word/document.xml",
                                "<document><body><p>hello</p></body></document>"),
                        new Part("word/_rels/document.xml.rels", "<Relationships/>"));

        assertDoesNotThrow(() -> OfficeArchiveBudget.verify(archive));
    }

    @Test
    void rejectsExcessiveEntryCountBeforeAnyOfficeParserRuns() throws Exception {
        byte[] archive =
                archive(
                        new Part("one.xml", "<a/>"),
                        new Part("two.xml", "<b/>"),
                        new Part("three.xml", "<c/>"));
        var limits = new OfficeArchiveBudget.Limits(2, 1024, 2048, 200, 1024, 100, 16);

        assertThrows(
                OfficeArchiveBudget.Exceeded.class,
                () -> OfficeArchiveBudget.verify(archive, limits));
    }

    @Test
    void limitsActualInflatedPayloadAndCompressionRatio() throws Exception {
        byte[] inflated = new byte[1024 * 1024 + 64];
        java.util.Arrays.fill(inflated, (byte) 'x');
        byte[] archive =
                archive(
                        new Part(
                                "word/media.bin",
                                new String(inflated, StandardCharsets.ISO_8859_1)));
        var limits = new OfficeArchiveBudget.Limits(4, 2_000_000, 2_000_000, 2, 1_000_000, 100, 16);

        assertThrows(
                OfficeArchiveBudget.Exceeded.class,
                () -> OfficeArchiveBudget.verify(archive, limits));
    }

    @Test
    void limitsMeasuredInflatedBytesEvenWhenAnEntryIsSmallAndUncompressed() throws Exception {
        byte[] archive = archive(new Part("word/media.bin", "x".repeat(101)));
        var limits = new OfficeArchiveBudget.Limits(4, 100, 200, 200, 100, 100, 16);

        assertThrows(
                OfficeArchiveBudget.Exceeded.class,
                () -> OfficeArchiveBudget.verify(archive, limits));
    }

    @Test
    void enforcesXmlByteBudgetWhileThePartIsBeingParsed() throws Exception {
        byte[] archive = archive(new Part("word/document.xml", "<a>" + "x".repeat(100) + "</a>"));
        var limits = new OfficeArchiveBudget.Limits(4, 1024, 2048, 200, 64, 100, 16);

        assertThrows(
                OfficeArchiveBudget.Exceeded.class,
                () -> OfficeArchiveBudget.verify(archive, limits));
    }

    @Test
    void rejectsDtdAndExternalEntitiesBeforeOfficeObjectsAreConstructed() throws Exception {
        String xml =
                "<!DOCTYPE x [<!ENTITY ext SYSTEM \"file:///lumira-preflight-secret\">]>"
                        + "<x>&ext;</x>";
        byte[] archive = archive(new Part("word/document.xml", xml));

        assertThrows(IOException.class, () -> OfficeArchiveBudget.verify(archive));
    }

    @Test
    void countsXmlEventsAndAttributesWithinTheNodeBudget() throws Exception {
        byte[] archive = archive(new Part("word/document.xml", "<a flag=\"x\"><b/></a>"));
        var limits = new OfficeArchiveBudget.Limits(4, 1024, 2048, 200, 1024, 4, 16);

        assertThrows(
                OfficeArchiveBudget.Exceeded.class,
                () -> OfficeArchiveBudget.verify(archive, limits));
    }

    private static byte[] archive(Part... parts) throws Exception {
        try (var output = new ByteArrayOutputStream();
                var zip = new ZipOutputStream(output)) {
            for (Part part : parts) {
                zip.putNextEntry(new ZipEntry(part.name()));
                zip.write(part.bytes());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private record Part(String name, byte[] bytes) {
        private Part(String name, String content) {
            this(name, content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
