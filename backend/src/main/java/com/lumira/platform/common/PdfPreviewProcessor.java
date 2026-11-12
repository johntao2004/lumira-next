package com.lumira.platform.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/** Invoked only by the resource-limited Office parser child. */
public final class PdfPreviewProcessor {
    private static final int MAX_TEXT_PAGES = 100;

    private PdfPreviewProcessor() {}

    public static OfficeWorkerProtocol.PdfPage page(byte[] bytes, int page) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            int count = document.getNumberOfPages();
            if (document.isEncrypted() || page < 1 || page > count)
                throw new IOException("PDF page unavailable");
            var box = document.getPage(page - 1).getCropBox();
            double edge = Math.max(box.getWidth(), box.getHeight());
            if (!Double.isFinite(edge) || box.getWidth() <= 0 || box.getHeight() <= 0)
                throw new IOException("Invalid PDF page bounds");
            var renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            var image =
                    renderer.renderImage(
                            page - 1,
                            (float) Math.min(2, OfficeWorkerProtocol.PDF_OUTPUT_EDGE / edge));
            try {
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "PNG", output))
                    throw new IOException("PNG encoder unavailable");
                if (output.size() > OfficeWorkerProtocol.MAX_PDF_PNG_BYTES)
                    throw new OfficeArchiveBudget.Exceeded("PDF page exceeds PNG byte budget");
                return new OfficeWorkerProtocol.PdfPage(page, count, output.toByteArray());
            } finally {
                image.flush();
            }
        }
    }

    public static OfficeWorkerProtocol.Preview text(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()
                    || !document.getCurrentAccessPermission().canExtractContent())
                throw new IOException("PDF text unavailable");
            int pageCount = document.getNumberOfPages();
            var stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(MAX_TEXT_PAGES, pageCount));
            var output = new BoundedTextWriter();
            stripper.writeText(document, output);
            return new OfficeWorkerProtocol.Preview(
                    output.text.toString(), output.truncated || pageCount > MAX_TEXT_PAGES);
        }
    }

    private static final class BoundedTextWriter extends Writer {
        final StringBuilder text = new StringBuilder();
        boolean truncated;

        @Override
        public void write(char[] chars, int offset, int length) {
            int count = Math.min(length, AiAttachmentPort.MAX_TEXT + 1 - text.length());
            if (count > 0) text.append(chars, offset, count);
            truncated |= count < length;
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
