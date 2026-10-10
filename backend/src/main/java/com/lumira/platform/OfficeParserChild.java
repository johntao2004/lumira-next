package com.lumira.platform;

import com.lumira.platform.common.ImportValidationException;
import com.lumira.platform.common.OfficeArchiveBudget;
import com.lumira.platform.common.OfficeDocumentProcessor;
import com.lumira.platform.common.OfficeWorkerProtocol;
import com.lumira.platform.dictionaries.DictionaryFileParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.web.server.ResponseStatusException;

/** One-request parser entry point. The Office worker kills this process at its deadline. */
public final class OfficeParserChild {
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private OfficeParserChild() {}

    public static int run(Path requestPath) {
        try {
            var request =
                    JSON.readValue(requestPath.toFile(), OfficeWorkerProtocol.ChildRequest.class);
            var responsePath = requestPath.resolveSibling(OfficeWorkerProtocol.CHILD_RESPONSE_FILE);
            if (OfficeWorkerProtocol.TEST_TIMEOUT.equals(request.operation())
                    && testModeEnabled()) {
                Thread.sleep(60_000);
                return 0;
            }
            if (OfficeWorkerProtocol.TEST_OOM.equals(request.operation()) && testModeEnabled()) {
                var pressure = new java.util.ArrayList<byte[]>();
                while (true) pressure.add(new byte[8 * 1024 * 1024]);
            }
            if (!OfficeWorkerProtocol.PREVIEW.equals(request.operation())
                    && !OfficeWorkerProtocol.DICTIONARY.equals(request.operation())) {
                write(
                        responsePath,
                        error(request.operation(), 404, "officeOperationUnsupported", List.of()));
                return 0;
            }
            var bytes = Files.readAllBytes(Path.of(request.inputFile()));
            if (OfficeWorkerProtocol.PREVIEW.equals(request.operation())) {
                try {
                    write(
                            responsePath,
                            OfficeWorkerProtocol.Response.preview(
                                    OfficeDocumentProcessor.preview(request.filename(), bytes)));
                } catch (OfficeArchiveBudget.Exceeded error) {
                    write(
                            responsePath,
                            error(request.operation(), 413, "filePreviewTooLarge", List.of()));
                } catch (Exception error) {
                    write(
                            responsePath,
                            error(request.operation(), 400, "filePreviewInvalid", List.of()));
                }
                return 0;
            }
            if (OfficeWorkerProtocol.DICTIONARY.equals(request.operation())) {
                try {
                    var parsed =
                            new DictionaryFileParser()
                                    .parseInWorker(bytes, request.filename(), request.structure());
                    var result =
                            new OfficeWorkerProtocol.Dictionary(
                                    parsed.sha256(),
                                    parsed.items().stream()
                                            .map(
                                                    item ->
                                                            new OfficeWorkerProtocol.DictionaryItem(
                                                                    item.value(),
                                                                    item.label(),
                                                                    item.parentValue(),
                                                                    item.sortOrder(),
                                                                    item.enabled(),
                                                                    item.remark()))
                                            .toList());
                    write(responsePath, OfficeWorkerProtocol.Response.dictionary(result));
                } catch (ImportValidationException error) {
                    write(
                            responsePath,
                            error(
                                    request.operation(),
                                    400,
                                    "invalidDictionaryDataset",
                                    error.issues().stream()
                                            .limit(100)
                                            .map(
                                                    issue ->
                                                            new OfficeWorkerProtocol.Issue(
                                                                    issue.row(),
                                                                    issue.field(),
                                                                    issue.reason()))
                                            .toList()));
                } catch (ResponseStatusException error) {
                    int status = error.getStatusCode().value();
                    String reason =
                            status == 413
                                    ? "dictionaryDatasetTooLarge"
                                    : "invalidDictionaryDataset";
                    write(responsePath, error(request.operation(), status, reason, List.of()));
                } catch (Exception error) {
                    write(
                            responsePath,
                            error(request.operation(), 400, "invalidDictionaryDataset", List.of()));
                }
                return 0;
            }
            return 0;
        } catch (Throwable error) {
            if ("true".equalsIgnoreCase(System.getenv("LUMIRA_OFFICE_WORKER_CHILD_DIAGNOSTICS"))) {
                try {
                    Files.writeString(
                            requestPath.resolveSibling("child-failure-class.txt"),
                            error.getClass().getName());
                } catch (Throwable ignored) {
                }
            }
            // A hard OOM kill intentionally leaves no response. Diagnostics contain only a Java
            // exception class name in test workers, never document content or exception text.
            return 1;
        }
    }

    private static OfficeWorkerProtocol.Response error(
            String operation, int status, String reason, List<OfficeWorkerProtocol.Issue> issues) {
        return OfficeWorkerProtocol.Response.error(operation, status, reason, issues);
    }

    private static boolean testModeEnabled() {
        return "true".equalsIgnoreCase(System.getenv("LUMIRA_OFFICE_WORKER_TEST_MODE"));
    }

    private static void write(Path outputPath, OfficeWorkerProtocol.Response response)
            throws Exception {
        byte[] bytes = JSON.writeValueAsBytes(response);
        if (bytes.length > OfficeWorkerProtocol.MAX_RESPONSE_BYTES) {
            String reason =
                    OfficeWorkerProtocol.PREVIEW.equals(response.kind())
                            ? "filePreviewTooLarge"
                            : "dictionaryDatasetTooLarge";
            bytes =
                    JSON.writeValueAsBytes(
                            OfficeWorkerProtocol.Response.error(
                                    response.kind(), 413, reason, List.of()));
        }
        Files.write(outputPath, bytes);
    }
}
