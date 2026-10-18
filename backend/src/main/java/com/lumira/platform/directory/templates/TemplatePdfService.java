package com.lumira.platform.directory.templates;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformOperation;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TemplatePdfService {
    public static final int MAX_PRINT_USERS = 10;
    private static final int MAX_OUTPUT_BYTES = 100 * 1024 * 1024;

    public record Request(
            UUID templateId,
            int templateVersion,
            List<UUID> userIds,
            String format,
            boolean merge) {}

    public record Output(String contentType, String filename, byte[] bytes) {}

    private final AdministrationPort guard;
    private final TemplateService templates;

    public TemplatePdfService(AdministrationPort guard, TemplateService templates) {
        this.guard = guard;
        this.templates = templates;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Output print(Request input, String actor) {
        if (input == null
                || input.templateId() == null
                || input.userIds() == null
                || input.userIds().isEmpty()
                || input.userIds().size() > MAX_PRINT_USERS
                || input.userIds().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(input.userIds()).size() != input.userIds().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batchLimitExceeded");
        if (!("pdf".equals(input.format()) || "zip".equals(input.format()))
                || ("zip".equals(input.format()) && input.merge())
                || ("pdf".equals(input.format()) && input.userIds().size() > 1 && !input.merge()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPrintFormat");
        var requestContext =
                com.lumira.platform.identity.api.AuthenticationRequestContext.current();
        if (requestContext != null
                && requestContext.authorization() != null
                && requestContext.authorization().previewActive())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        List<byte[]> pdfs = new ArrayList<>(input.userIds().size());
        for (UUID userId : input.userIds()) {
            guard.requireOperationUser(actor, userId, PlatformOperation.USER_PRINT);
            pdfs.add(
                    templates.renderPublished(
                            input.templateId(), input.templateVersion(), userId, actor));
            if (pdfs.stream().mapToLong(bytes -> bytes.length).sum() > MAX_OUTPUT_BYTES)
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "printOutputTooLarge");
        }
        if ("zip".equals(input.format()))
            return new Output("application/zip", "user-templates.zip", zip(pdfs));
        return new Output(
                "application/pdf",
                "user-templates.pdf",
                pdfs.size() == 1 ? pdfs.getFirst() : merge(pdfs));
    }

    private static byte[] merge(List<byte[]> pdfs) {
        try (var output = new ByteArrayOutputStream()) {
            var merger = new PDFMergerUtility();
            for (byte[] pdf : pdfs) merger.addSource(new RandomAccessReadBuffer(pdf));
            merger.setDestinationStream(output);
            merger.mergeDocuments(IOUtils.createMemoryOnlyStreamCache());
            byte[] merged = output.toByteArray();
            if (merged.length > MAX_OUTPUT_BYTES)
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "printOutputTooLarge");
            return merged;
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "pdfMergeFailed", failure);
        }
    }

    private static byte[] zip(List<byte[]> pdfs) {
        try (var output = new ByteArrayOutputStream();
                var zip = new ZipOutputStream(output)) {
            for (int index = 0; index < pdfs.size(); index++) {
                zip.putNextEntry(new ZipEntry("user-" + (index + 1) + ".pdf"));
                zip.write(pdfs.get(index));
                zip.closeEntry();
            }
            zip.finish();
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_OUTPUT_BYTES)
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "printOutputTooLarge");
            return bytes;
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "zipFailed", failure);
        }
    }
}
