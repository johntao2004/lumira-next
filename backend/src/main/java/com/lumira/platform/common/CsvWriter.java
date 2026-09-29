package com.lumira.platform.common;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Small, dependency-free CSV writer shared by platform exports. */
public final class CsvWriter {
    private CsvWriter() {}

    public static void row(StringBuilder output, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) output.append(',');
            output.append(escape(values.get(index)));
        }
        output.append("\r\n");
    }

    public static byte[] utf8(StringBuilder output) {
        // The BOM makes the UTF-8 encoding unambiguous to spreadsheet clients.
        return ("\uFEFF" + output).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Spreadsheet clients evaluate cells that start with = + - @ (or a tab / line break they strip)
     * as formulas, so user-controlled text such as a display name could run in an administrator's
     * Excel. A leading apostrophe forces text. Plain signed numbers are left untouched.
     */
    private static String neutralizeFormula(String text) {
        if (text.isEmpty()) return text;
        char first = text.charAt(0);
        if (first == '=' || first == '@' || first == '\t' || first == '\r' || first == '\n')
            return "'" + text;
        if ((first == '+' || first == '-') && !text.matches("[+-]?\\d+(?:\\.\\d+)?"))
            return "'" + text;
        return text;
    }

    private static String escape(Object value) {
        if (value == null) return "";
        String text = neutralizeFormula(String.valueOf(value));
        if (text.indexOf('"') >= 0
                || text.indexOf(',') >= 0
                || text.indexOf('\r') >= 0
                || text.indexOf('\n') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
