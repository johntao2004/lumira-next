package com.lumira.platform.common;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Host-side, bounded client for the cgroup-limited Office parser service. */
public final class OfficeWorkerClient {
    private static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Semaphore IN_FLIGHT = new Semaphore(1);
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();
    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    private OfficeWorkerClient() {}

    public static OfficeWorkerProtocol.Preview preview(String filename, byte[] bytes) {
        var response = invoke(OfficeWorkerProtocol.PREVIEW, filename, null, bytes);
        var preview = response.preview();
        if (!OfficeWorkerProtocol.PREVIEW.equals(response.kind())
                || preview == null
                || preview.text() == null
                || preview.text().codePointCount(0, preview.text().length())
                        > OfficeDocumentProcessor.MAX_PREVIEW_CODE_POINTS) {
            throw unavailable();
        }
        return preview;
    }

    public static OfficeWorkerProtocol.PdfPage pdfPage(String filename, byte[] bytes, int page) {
        if (page < 1) throw invalidInput(OfficeWorkerProtocol.PDF_PAGE);
        var response =
                invoke(OfficeWorkerProtocol.PDF_PAGE, filename, Integer.toString(page), bytes);
        var result = response.pdfPage();
        if (result == null
                || result.page() != page
                || result.pageCount() < page
                || !validPngHeader(result.png())) throw unavailable();
        return result;
    }

    private static boolean validPngHeader(byte[] bytes) {
        if (bytes == null
                || bytes.length < 33
                || bytes.length > OfficeWorkerProtocol.MAX_PDF_PNG_BYTES) return false;
        var header = java.nio.ByteBuffer.wrap(bytes);
        if (header.getLong() != 0x89504e470d0a1a0aL
                || header.getInt() != 13
                || header.getInt() != 0x49484452) return false;
        int width = header.getInt();
        int height = header.getInt();
        return width > 0
                && width <= OfficeWorkerProtocol.PDF_OUTPUT_EDGE
                && height > 0
                && height <= OfficeWorkerProtocol.PDF_OUTPUT_EDGE;
    }

    public static OfficeWorkerProtocol.Dictionary dictionary(
            String filename, String structure, byte[] bytes) {
        var response = invoke(OfficeWorkerProtocol.DICTIONARY, filename, structure, bytes);
        var dictionary = response.dictionary();
        if (!OfficeWorkerProtocol.DICTIONARY.equals(response.kind())
                || dictionary == null
                || dictionary.items() == null
                || dictionary.items().size() > 20_000
                || dictionary.sha256() == null
                || !dictionary.sha256().matches("[0-9a-f]{64}")) {
            throw unavailable();
        }
        return dictionary;
    }

    static OfficeWorkerProtocol.Inspection inspectDocument(String filename, byte[] bytes) {
        var response = invoke(OfficeWorkerProtocol.DOCUMENT_INSPECT, filename, null, bytes, null);
        var inspection = response.inspection();
        if (!OfficeWorkerProtocol.DOCUMENT_INSPECT.equals(response.kind())
                || inspection == null
                || inspection.slots() == null
                || inspection.placeholders() == null
                || inspection.slots().size() > DocumentTemplateProcessor.MAX_SLOTS
                || inspection.placeholders().size() > DocumentTemplateProcessor.MAX_PLACEHOLDERS)
            throw unavailable();
        return inspection;
    }

    static byte[] documentPdf(
            String filename,
            byte[] bytes,
            java.util.Map<String, String> bindings,
            java.util.Map<String, String> values) {
        var response =
                invoke(
                        OfficeWorkerProtocol.DOCUMENT_PDF,
                        filename,
                        null,
                        bytes,
                        new OfficeWorkerProtocol.DocumentMetadata(bindings, values));
        byte[] pdf = response.documentPdf();
        if (!OfficeWorkerProtocol.DOCUMENT_PDF.equals(response.kind())
                || pdf == null
                || pdf.length < 8
                || pdf.length > DocumentTemplateProcessor.MAX_RESPONSE_PDF_BYTES
                || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF-"))
            throw unavailable();
        return pdf;
    }

    static OfficeWorkerProtocol.WorkbookData readWorkbook(byte[] bytes) {
        var response =
                invoke(OfficeWorkerProtocol.WORKBOOK_READ, "workbook.xlsx", null, bytes, null);
        var workbook = response.workbook();
        if (!OfficeWorkerProtocol.WORKBOOK_READ.equals(response.kind())
                || workbook == null
                || workbook.rows() == null
                || workbook.rows().size() > DocumentTemplateProcessor.MAX_WORKBOOK_ROWS)
            throw unavailable();
        return workbook;
    }

    static byte[] writeWorkbook(java.util.List<java.util.List<String>> rows) {
        var response =
                invoke(
                        OfficeWorkerProtocol.WORKBOOK_WRITE,
                        "workbook.xlsx",
                        null,
                        null,
                        new OfficeWorkerProtocol.WorkbookWriteMetadata(rows));
        byte[] bytes = response.workbookFile();
        if (!OfficeWorkerProtocol.WORKBOOK_WRITE.equals(response.kind())
                || bytes == null
                || bytes.length < 4
                || bytes.length > MAX_INPUT_BYTES
                || bytes[0] != 'P'
                || bytes[1] != 'K') throw unavailable();
        return bytes;
    }

    static ResponseStatusException workerUnavailable() {
        return unavailable();
    }

    private static OfficeWorkerProtocol.Response invoke(
            String operation, String filename, String structure, byte[] bytes) {
        return invoke(operation, filename, structure, bytes, null);
    }

    private static OfficeWorkerProtocol.Response invoke(
            String operation, String filename, String structure, byte[] bytes, Object metadata) {
        boolean noFilePayload = OfficeWorkerProtocol.WORKBOOK_WRITE.equals(operation);
        if ((!noFilePayload && (bytes == null || bytes.length == 0))
                || (noFilePayload && bytes != null && bytes.length != 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidReason(operation));
        if (bytes != null && bytes.length > MAX_INPUT_BYTES) throw tooLarge(operation);
        String baseUrl = setting("LUMIRA_OFFICE_WORKER_URL", "lumira.office.worker.url");
        String token = setting("LUMIRA_OFFICE_WORKER_TOKEN", "lumira.office.worker.token");
        if (baseUrl == null || token == null || token.length() < 32) throw unavailable();
        if (!IN_FLIGHT.tryAcquire()) throw unavailable();

        CompletableFuture<HttpResponse<byte[]>> request = null;
        int responseLimit =
                OfficeWorkerProtocol.PDF_PAGE.equals(operation)
                        ? (OfficeWorkerProtocol.MAX_PDF_PNG_BYTES * 4 / 3) + 1024
                        : MAX_RESPONSE_BYTES;
        var bodyHandler = new BoundedBodyHandler(responseLimit);
        try {
            byte[] requestBody = requestBody(operation, bytes, metadata);
            URI endpoint = endpoint(baseUrl);
            if (filename == null
                    || filename.isBlank()
                    || filename.getBytes(StandardCharsets.UTF_8).length > 1024)
                throw invalidInput(operation);
            String encodedName =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(filename.getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(REQUEST_TIMEOUT)
                            .header(OfficeWorkerProtocol.TOKEN_HEADER, token)
                            .header(OfficeWorkerProtocol.OPERATION_HEADER, operation)
                            .header(OfficeWorkerProtocol.FILENAME_HEADER, encodedName)
                            .header("Content-Type", "application/octet-stream")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
            if (structure != null) builder.header(OfficeWorkerProtocol.STRUCTURE_HEADER, structure);
            request = HTTP.sendAsync(builder.build(), bodyHandler);
            HttpResponse<byte[]> response =
                    request.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200
                    && response.statusCode() != 400
                    && response.statusCode() != 413) throw unavailable();
            OfficeWorkerResponseGuard.validate(response.body(), operation);
            OfficeWorkerProtocol.Response body =
                    JSON.readValue(response.body(), OfficeWorkerProtocol.Response.class);
            if (response.statusCode() != 200 && body.status() != response.statusCode())
                throw unavailable();
            if (response.statusCode() == 413) throw remoteFailure(operation, body);
            if (response.statusCode() == 400) throw remoteFailure(operation, body);
            if (body.status() != 0 || body.reason() != null) throw unavailable();
            return body;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            if (request != null) request.cancel(true);
            throw unavailable();
        } catch (InterruptedException exception) {
            if (request != null) request.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException exception) {
            if (request != null) request.cancel(true);
            if (bodyHandler.exceededLimit() || containsTooLarge(exception))
                throw tooLarge(operation);
            throw unavailable();
        } catch (Exception exception) {
            if (request != null) request.cancel(true);
            if (bodyHandler.exceededLimit()) throw tooLarge(operation);
            throw unavailable();
        } finally {
            if (request != null && !request.isDone()) request.cancel(true);
            if (request != null) bodyHandler.cancel();
            IN_FLIGHT.release();
        }
    }

    private static URI endpoint(String baseUrl) {
        try {
            URI base = URI.create(baseUrl);
            if (!List.of("http", "https").contains(base.getScheme())
                    || base.getHost() == null
                    || base.getUserInfo() != null
                    || base.getQuery() != null
                    || base.getFragment() != null) throw new IllegalArgumentException();
            String path = base.getPath() == null ? "" : base.getPath();
            return URI.create(
                    base.getScheme()
                            + "://"
                            + base.getRawAuthority()
                            + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path)
                            + "/v1/office");
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static RuntimeException remoteFailure(
            String operation, OfficeWorkerProtocol.Response response) {
        String reason = response.reason();
        if (response.status() == 413) {
            String expectedTooLargeReason = tooLargeReason(operation);
            if (expectedTooLargeReason.equals(reason)) throw tooLarge(operation);
            throw unavailable();
        }
        if (response.status() != 400) throw unavailable();
        if (OfficeWorkerProtocol.DICTIONARY.equals(operation)
                && "invalidDictionaryDataset".equals(reason)
                && response.issues() != null
                && !response.issues().isEmpty()) {
            throw new ImportValidationException(
                    response.issues().stream()
                            .limit(100)
                            .map(
                                    issue ->
                                            new ImportValidationException.Issue(
                                                    issue.row(), issue.field(), issue.reason()))
                            .toList());
        }
        String expectedReason = invalidReason(operation);
        if (expectedReason.equals(reason)
                || (OfficeWorkerProtocol.PREVIEW.equals(operation)
                        && "officeInputInvalid".equals(reason)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, expectedReason);
        throw unavailable();
    }

    private static boolean containsTooLarge(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause())
            if (cause instanceof ResponseTooLargeException) return true;
        return false;
    }

    private static ResponseStatusException tooLarge(String operation) {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, tooLargeReason(operation));
    }

    private static ResponseStatusException invalidInput(String operation) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidReason(operation));
    }

    private static String tooLargeReason(String operation) {
        return OfficeWorkerFailureReasons.tooLarge(operation);
    }

    private static String invalidReason(String operation) {
        return OfficeWorkerFailureReasons.invalid(operation);
    }

    private static byte[] requestBody(String operation, byte[] bytes, Object metadata)
            throws IOException {
        if (metadata == null) return bytes;
        validateLocalMetadata(
                operation,
                metadata instanceof OfficeWorkerProtocol.DocumentMetadata document
                        ? document.bindings()
                        : null,
                metadata instanceof OfficeWorkerProtocol.DocumentMetadata document
                        ? document.values()
                        : null,
                metadata instanceof OfficeWorkerProtocol.WorkbookWriteMetadata workbook
                        ? workbook.rows()
                        : null);
        byte[] metadataBytes = JSON.writeValueAsBytes(metadata);
        if (metadataBytes.length == 0 || metadataBytes.length > MAX_METADATA_BYTES)
            throw tooLarge(operation);
        int inputSize = bytes == null ? 0 : bytes.length;
        int expectedSize =
                OfficeWorkerProtocol.REQUEST_FRAME_MAGIC.length()
                        + 4
                        + metadataBytes.length
                        + inputSize;
        var stream = new ByteArrayOutputStream(expectedSize);
        try (var data = new DataOutputStream(stream)) {
            data.write(
                    OfficeWorkerProtocol.REQUEST_FRAME_MAGIC.getBytes(StandardCharsets.US_ASCII));
            data.writeInt(metadataBytes.length);
            data.write(metadataBytes);
            if (bytes != null) data.write(bytes);
        }
        byte[] body = stream.toByteArray();
        if (body.length != expectedSize) throw new IOException("Invalid document request frame");
        return body;
    }

    private static void validateLocalMetadata(
            String operation,
            java.util.Map<String, String> bindings,
            java.util.Map<String, String> values,
            java.util.List<java.util.List<String>> rows) {
        try {
            if (OfficeWorkerProtocol.DOCUMENT_PDF.equals(operation))
                DocumentWorkerRequestGuard.validateValues(bindings, values);
            else if (OfficeWorkerProtocol.WORKBOOK_WRITE.equals(operation))
                DocumentWorkerRequestGuard.validateRows(rows);
        } catch (IOException invalid) {
            throw tooLarge(operation);
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
    }

    private static String setting(String environment, String systemProperty) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? null : value;
    }

    private static final class BoundedBodyHandler implements HttpResponse.BodyHandler<byte[]> {
        private final int maximumBytes;
        private volatile BoundedBodySubscriber subscriber;
        private volatile boolean exceededLimit;

        private BoundedBodyHandler(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
            var created =
                    new BoundedBodySubscriber(
                            maximumBytes, info.headers().firstValueAsLong("Content-Length"), this);
            subscriber = created;
            return created;
        }

        private boolean exceededLimit() {
            return exceededLimit;
        }

        private void cancel() {
            BoundedBodySubscriber current = subscriber;
            if (current != null) current.cancel();
        }
    }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final java.util.OptionalLong contentLength;
        private final BoundedBodyHandler owner;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        private int count;

        private BoundedBodySubscriber(
                int maximumBytes, java.util.OptionalLong contentLength, BoundedBodyHandler owner) {
            this.maximumBytes = maximumBytes;
            this.contentLength = contentLength;
            this.owner = owner;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (body.isDone()) {
                subscription.cancel();
                return;
            }
            if (contentLength.isPresent() && contentLength.getAsLong() > maximumBytes) {
                owner.exceededLimit = true;
                subscription.cancel();
                body.completeExceptionally(new ResponseTooLargeException());
                return;
            }
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int remaining = buffer.remaining();
                    if (remaining > maximumBytes - count) throw new ResponseTooLargeException();
                    byte[] chunk = new byte[remaining];
                    buffer.get(chunk);
                    output.write(chunk, 0, chunk.length);
                    count += remaining;
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                if (exception instanceof ResponseTooLargeException) owner.exceededLimit = true;
                subscription.cancel();
                body.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }

        private void cancel() {
            Flow.Subscription current = subscription;
            if (current != null) current.cancel();
            body.completeExceptionally(
                    new CancellationException("Office worker response cancelled"));
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
