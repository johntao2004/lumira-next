package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvWriterTest {
    @Test
    void quotesCommasQuotesAndLineBreaksAndAddsUtf8Bom() {
        var csv = new StringBuilder();
        CsvWriter.row(csv, List.of("id", "display\nname", "say \"hi\"", "a,b"));

        assertEquals("id,\"display\nname\",\"say \"\"hi\"\"\",\"a,b\"\r\n", csv.toString());
        assertEquals("\uFEFF" + csv, new String(CsvWriter.utf8(csv), StandardCharsets.UTF_8));
    }

    @Test
    void neutralizesSpreadsheetFormulaPrefixesButKeepsPlainNumbers() {
        var csv = new StringBuilder();
        CsvWriter.row(
                csv,
                java.util.Arrays.asList(
                        "=HYPERLINK(\"http://evil\")",
                        "@SUM(A1)",
                        "+1+1",
                        "-cmd|x",
                        "\tcmd",
                        -5,
                        "-5.25",
                        "ok",
                        null,
                        12L));

        assertEquals(
                "\"'=HYPERLINK(\"\"http://evil\"\")\",'@SUM(A1),'+1+1,'-cmd|x,'\tcmd,-5,-5.25,ok,,12\r\n",
                csv.toString());
    }
}
