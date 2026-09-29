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
}
