/**
 * 验证令牌和容器资源限制后执行独立 Office 处理，为每次操作启动可回收子进程，并限制请求大小、并发与执行时间。 Runs isolated Office operations after
 * validating tokens and container limits, using a disposable child process per operation with
 * bounded request size, concurrency and execution time.
 */
package com.lumira.platform;

import com.lumira.platform.common.DocumentWorkerRequestGuard;
import com.lumira.platform.common.OfficeWorkerFailureReasons;
import com.lumira.platform.common.OfficeWorkerProtocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 轻量 HTTP 进程；每次 Office 操作均启动一个用完即销毁的解析器 JVM。 Small HTTP process which launches one disposable
 * parser JVM per Office operation.
 */
public final class OfficeWorkerServer {
    public static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FRAMED_REQUEST_BYTES = MAX_INPUT_BYTES + MAX_METADATA_BYTES + 8;
    public static final int MAX_RESPONSE_BYTES = OfficeWorkerProtocol.MAX_RESPONSE_BYTES;
    private static final int PORT = 18091;
    private static final long HARD_MEMORY_LIMIT_BYTES = 1024L * 1024 * 1024;
    static final long MAX_PROCESS_LIMIT = 128;
    private static final long DEFAULT_DEADLINE_MILLIS = 20_000;
    private static final long MAX_REQUEST_MILLIS = 35_000;
    private static final Semaphore PARSER = new Semaphore(1);
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();
    private static final ScheduledExecutorService CLOSER =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        var thread = new Thread(task, "office-worker-request-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });

    private OfficeWorkerServer() {}

    public static int healthCheck() {
        try {
            int port = integerSetting("LUMIRA_OFFICE_WORKER_PORT", PORT, 1, 65_535);
            var client =
                    java.net.http.HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .build();
            var request =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build();
            return client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                                    .statusCode()
                            == 200
                    ? 0
                    : 1;
        } catch (Exception error) {
            return 1;
        }
    }

    public static void run() throws IOException {
        verifyHardLimits();
        String token = System.getenv("LUMIRA_OFFICE_WORKER_TOKEN");
        if (token == null || token.length() < 32)
            throw new IllegalStateException(
                    "Office worker token must contain at least 32 characters");
        String jar = System.getenv("LUMIRA_OFFICE_WORKER_JAR");
        if (jar == null || !Files.isRegularFile(Path.of(jar)))
            throw new IllegalStateException("Office worker application JAR is unavailable");
        int port = integerSetting("LUMIRA_OFFICE_WORKER_PORT", PORT, 1, 65_535);
        long deadline =
                longSetting(
                        "LUMIRA_OFFICE_WORKER_DEADLINE_MS", DEFAULT_DEADLINE_MILLIS, 500, 30_000);
        byte[] tokenDigest = sha256(token.getBytes(StandardCharsets.UTF_8));
        boolean testMode = "true".equalsIgnoreCase(System.getenv("LUMIRA_OFFICE_WORKER_TEST_MODE"));

        // 关闭尚有未读请求体的交换时，直接断开连接，避免继续排空攻击者控制的字节；同时限制已接收连接数。
        // Closing an exchange with an unread body must close the connection without draining
        // attacker-controlled bytes from the socket. Cap accepted connections as well.
        System.setProperty("sun.net.httpserver.drainAmount", "0");
        System.setProperty("jdk.httpserver.maxConnections", "32");
        System.setProperty("sun.net.httpserver.maxReqTime", "45");
        System.setProperty("sun.net.httpserver.maxRspTime", "30");
        System.setProperty("sun.net.httpserver.timerMillis", "250");
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 8);
        var executor =
                new ThreadPoolExecutor(
                        4,
                        4,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(8),
                        Executors.defaultThreadFactory(),
                        new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/health", exchange -> health(exchange));
        server.createContext(
                "/v1/office", exchange -> handleOffice(exchange, tokenDigest, jar, deadline));
        if (testMode) {
            server.createContext(
                    "/__test/timeout",
                    exchange ->
                            handleProbe(
                                    exchange,
                                    tokenDigest,
                                    jar,
                                    deadline,
                                    OfficeWorkerProtocol.TEST_TIMEOUT));
            server.createContext(
                    "/__test/oom",
                    exchange ->
                            handleProbe(
                                    exchange,
                                    tokenDigest,
                                    jar,
                                    deadline,
                                    OfficeWorkerProtocol.TEST_OOM));
        }
        server.start();
    }

    private static void verifyHardLimits() throws IOException {
        Path root = Path.of("/sys/fs/cgroup");
        Path memoryMax = cgroupV2File(root, "memory.max");
        Path swapMax = cgroupV2File(root, "memory.swap.max");
        Path pidsMax = cgroupV2File(root, "pids.max");
        Path cpuMax = cgroupV2File(root, "cpu.max");
        long memory;
        long swap;
        long pids;
        if (memoryMax != null && swapMax != null && pidsMax != null && cpuMax != null) {
            memory = finiteLimit(memoryMax);
            swap = finiteNonnegativeLimit(swapMax);
            pids = finiteLimit(pidsMax);
            String[] cpu = Files.readString(cpuMax).strip().split("\\s+");
            if (cpu.length != 2)
                throw new IllegalStateException("Office worker CPU cap is unavailable");
            long quota = finiteLimit(cpu[0]);
            long period = finiteLimit(cpu[1]);
            if (period == 0 || quota > period)
                throw new IllegalStateException("Office worker CPU cap exceeds one core");
        } else {
            Path memoryLimit = cgroupV1File("memory", "memory.limit_in_bytes");
            Path memorySwapLimit = cgroupV1File("memory", "memory.memsw.limit_in_bytes");
            Path pidsLimit = cgroupV1File("pids", "pids.max");
            Path cpuQuota = cgroupV1File("cpu", "cpu.cfs_quota_us");
            Path cpuPeriod = cgroupV1File("cpu", "cpu.cfs_period_us");
            if (memoryLimit == null
                    || memorySwapLimit == null
                    || pidsLimit == null
                    || cpuQuota == null
                    || cpuPeriod == null)
                throw new IllegalStateException(
                        "Office worker requires finite Linux cgroup limits");
            memory = finiteLimit(memoryLimit);
            long combined = finiteLimit(memorySwapLimit);
            if (combined < memory)
                throw new IllegalStateException("Office worker swap cap is invalid");
            swap = combined - memory;
            pids = finiteLimit(pidsLimit);
            long quota = finiteLimit(cpuQuota);
            long period = finiteLimit(cpuPeriod);
            if (period == 0 || quota <= 0 || quota > period)
                throw new IllegalStateException("Office worker CPU cap exceeds one core");
        }
        validateResourceLimits(memory, swap, pids);
    }

    static void validateResourceLimits(long memory, long swap, long pids) {
        if (memory != HARD_MEMORY_LIMIT_BYTES || swap != 0 || pids > MAX_PROCESS_LIMIT || pids < 16)
            throw new IllegalStateException(
                    "Office worker cgroup memory, swap, or process cap is invalid");
    }

    private static Path cgroupV2File(Path root, String name) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                if (line.startsWith("0::")) {
                    String relative = line.substring(3);
                    Path path =
                            root.resolve(
                                            relative.startsWith("/")
                                                    ? relative.substring(1)
                                                    : relative)
                                    .resolve(name);
                    if (Files.isRegularFile(path)) return path;
                }
            }
            Path path = root.resolve(name);
            return Files.isRegularFile(path) ? path : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static Path cgroupV1File(String controller, String name) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                String[] values = line.split(":", 3);
                if (values.length != 3) continue;
                boolean matching =
                        java.util.Arrays.asList(values[1].split(",")).contains(controller);
                if (!matching) continue;
                String relative = values[2];
                for (Path mount :
                        List.of(Path.of("/sys/fs/cgroup", controller), Path.of("/sys/fs/cgroup"))) {
                    Path path =
                            mount.resolve(
                                            relative.startsWith("/")
                                                    ? relative.substring(1)
                                                    : relative)
                                    .resolve(name);
                    if (Files.isRegularFile(path)) return path;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static long finiteLimit(Path path) throws IOException {
        return finiteLimit(Files.readString(path).strip());
    }

    private static long finiteNonnegativeLimit(Path path) throws IOException {
        String value = Files.readString(path).strip();
        try {
            long limit = Long.parseLong(value);
            if (limit < 0) throw new NumberFormatException();
            return limit;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Office worker cgroup limit is unbounded or invalid");
        }
    }

    static long finiteLimit(String value) {
        try {
            long limit = Long.parseLong(value);
            if (limit <= 0) throw new NumberFormatException();
            return limit;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Office worker cgroup limit is unbounded or invalid");
        }
    }

    private static void health(HttpExchange exchange) throws IOException {
        if (!"/health".equals(exchange.getRequestURI().getPath())) {
            reply(exchange, 404, "notFound");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            reply(exchange, 405, "methodNotAllowed");
            return;
        }
        reply(exchange, 200, "ok");
    }

    private static void handleOffice(
            HttpExchange exchange, byte[] tokenDigest, String jar, long deadlineMillis)
            throws IOException {
        String operation =
                exchange.getRequestHeaders().getFirst(OfficeWorkerProtocol.OPERATION_HEADER);
        if (!authorized(exchange, tokenDigest)) {
            reply(exchange, 401, "officeWorkerUnauthorized");
            return;
        }
        if (!supportedOperation(operation)) {
            reply(exchange, 404, "officeOperationUnsupported");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            reply(exchange, 405, "methodNotAllowed");
            return;
        }
        if (!"/v1/office".equals(exchange.getRequestURI().getPath())) {
            reply(exchange, 404, "notFound");
            return;
        }
        if (!PARSER.tryAcquire()) {
            reply(exchange, 503, "officeWorkerBusy");
            return;
        }
        try {
            String filename;
            try {
                filename =
                        filename(
                                exchange.getRequestHeaders()
                                        .getFirst(OfficeWorkerProtocol.FILENAME_HEADER));
            } catch (IOException exception) {
                reply(exchange, operation, 400, "officeInputInvalid");
                return;
            }
            String structure =
                    exchange.getRequestHeaders().getFirst(OfficeWorkerProtocol.STRUCTURE_HEADER);
            if (!supported(operation, filename)
                    || (OfficeWorkerProtocol.DICTIONARY.equals(operation)
                            && !"TREE".equals(structure)
                            && !"FLAT".equals(structure))) {
                reply(exchange, operation, 400, OfficeWorkerFailureReasons.invalid(operation));
                return;
            }
            if (tooManyBytes(exchange, operation)) {
                reply(exchange, operation, 413, OfficeWorkerFailureReasons.tooLarge(operation));
                return;
            }
            ScheduledExecutorService timer = CLOSER;
            long requestDeadlineNanos =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_REQUEST_MILLIS);
            var closeRequest =
                    timer.schedule(exchange::close, MAX_REQUEST_MILLIS, TimeUnit.MILLISECONDS);
            try {
                OfficeWorkerProtocol.Response response;
                Path directory = null;
                try {
                    directory = privateDirectory();
                    Path input;
                    Path metadata = null;
                    if (requiresMetadata(operation)) {
                        var payload =
                                writeFramedRequest(exchange.getRequestBody(), directory, operation);
                        input = payload.input();
                        metadata = payload.metadata();
                    } else {
                        input = writeInput(exchange.getRequestBody(), directory);
                    }
                    var childRequest =
                            new OfficeWorkerProtocol.ChildRequest(
                                    operation,
                                    filename,
                                    structure,
                                    input == null ? "" : input.toString(),
                                    metadata == null ? "" : metadata.toString());
                    var requestFile = privateFile(directory.resolve("request.json"));
                    Files.write(requestFile, JSON.writeValueAsBytes(childRequest));
                    var outputFile =
                            privateFile(
                                    directory.resolve(OfficeWorkerProtocol.CHILD_RESPONSE_FILE));
                    response =
                            runChild(
                                    jar,
                                    requestFile,
                                    outputFile,
                                    operation,
                                    deadlineMillis,
                                    requestDeadlineNanos);
                } catch (TooLargeInputException exception) {
                    response =
                            OfficeWorkerProtocol.Response.error(
                                    operation,
                                    413,
                                    OfficeWorkerFailureReasons.tooLarge(operation),
                                    List.of());
                } catch (EmptyInputException exception) {
                    response =
                            OfficeWorkerProtocol.Response.error(
                                    operation,
                                    400,
                                    OfficeWorkerFailureReasons.invalid(operation),
                                    List.of());
                } catch (TimeoutException exception) {
                    response =
                            OfficeWorkerProtocol.Response.error(
                                    operation, 408, "officeProcessingTimeout", List.of());
                } catch (Exception exception) {
                    response =
                            OfficeWorkerProtocol.Response.error(
                                    operation, 503, "officeWorkerUnavailable", List.of());
                } finally {
                    deleteTree(directory);
                }
                reply(exchange, response);
            } finally {
                closeRequest.cancel(false);
            }
        } finally {
            PARSER.release();
        }
    }

    private static void handleProbe(
            HttpExchange exchange,
            byte[] tokenDigest,
            String jar,
            long deadlineMillis,
            String operation)
            throws IOException {
        String expectedPath =
                OfficeWorkerProtocol.TEST_TIMEOUT.equals(operation)
                        ? "/__test/timeout"
                        : "/__test/oom";
        if (!expectedPath.equals(exchange.getRequestURI().getPath())) {
            reply(exchange, 404, "notFound");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            reply(exchange, 405, "methodNotAllowed");
            return;
        }
        if (!authorized(exchange, tokenDigest)) {
            reply(exchange, 401, "officeWorkerUnauthorized");
            return;
        }
        if (!PARSER.tryAcquire()) {
            reply(exchange, 503, "officeWorkerBusy");
            return;
        }
        var closeRequest =
                CLOSER.schedule(exchange::close, MAX_REQUEST_MILLIS, TimeUnit.MILLISECONDS);
        long requestDeadlineNanos =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_REQUEST_MILLIS);
        Path directory = null;
        try {
            directory = privateDirectory();
            var requestFile = privateFile(directory.resolve("request.json"));
            Files.write(
                    requestFile,
                    JSON.writeValueAsBytes(
                            new OfficeWorkerProtocol.ChildRequest(operation, "", "", "", "")));
            var outputFile =
                    privateFile(directory.resolve(OfficeWorkerProtocol.CHILD_RESPONSE_FILE));
            var response =
                    runChild(
                            jar,
                            requestFile,
                            outputFile,
                            operation,
                            deadlineMillis,
                            requestDeadlineNanos);
            reply(exchange, response);
        } catch (TimeoutException exception) {
            reply(exchange, 408, "officeProcessingTimeout");
        } catch (Exception exception) {
            reply(exchange, 503, "officeWorkerUnavailable");
        } finally {
            closeRequest.cancel(false);
            deleteTree(directory);
            PARSER.release();
        }
    }

    private static OfficeWorkerProtocol.Response runChild(
            String jar,
            Path requestFile,
            Path outputFile,
            String operation,
            long deadlineMillis,
            long requestDeadlineNanos)
            throws Exception {
        boolean probe = operation.startsWith("test-");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new java.util.ArrayList<String>();
        command.add(java);
        command.add("-XX:ActiveProcessorCount=1");
        command.add("-XX:+UseSerialGC");
        command.add("-XX:CICompilerCount=2");
        command.add("-Xss512k");
        command.add("-Xms16m");
        command.add(
                probe && OfficeWorkerProtocol.TEST_OOM.equals(operation) ? "-Xmx2g" : "-Xmx512m");
        command.add("-XX:MaxMetaspaceSize=128m");
        command.add("-XX:MaxDirectMemorySize=64m");
        command.add("-XX:ReservedCodeCacheSize=32m");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(jar);
        command.add("--office-parser-child");
        command.add(requestFile.toString());
        var builder =
                new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        // 绝不向解析器子进程传递数据库、服务商、签名或其他服务凭据。
        // Never pass database, provider, signing, or other service credentials to parser children.
        builder.environment().clear();
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("TZ", "UTC");
        boolean testMode = "true".equalsIgnoreCase(System.getenv("LUMIRA_OFFICE_WORKER_TEST_MODE"));
        if (testMode) builder.environment().put("LUMIRA_OFFICE_WORKER_CHILD_DIAGNOSTICS", "true");
        if (probe) builder.environment().put("LUMIRA_OFFICE_WORKER_TEST_MODE", "true");
        var process = builder.start();
        long remainingRequestMillis =
                Math.max(
                        0, TimeUnit.NANOSECONDS.toMillis(requestDeadlineNanos - System.nanoTime()));
        long effectiveDeadline = Math.min(deadlineMillis, remainingRequestMillis);
        if (OfficeWorkerProtocol.TEST_TIMEOUT.equals(operation))
            effectiveDeadline = Math.min(effectiveDeadline, 750L);
        if (!process.waitFor(effectiveDeadline, TimeUnit.MILLISECONDS)) {
            killTree(process);
            return OfficeWorkerProtocol.Response.error(
                    operation, 408, "officeProcessingTimeout", List.of());
        }
        if (process.exitValue() != 0) {
            if (testMode) {
                Path failureClass = requestFile.resolveSibling("child-failure-class.txt");
                String className = "none";
                if (Files.isRegularFile(failureClass)) {
                    String candidate = Files.readString(failureClass).strip();
                    if (candidate.matches("[A-Za-z0-9_.$]{1,192}")) className = candidate;
                }
                System.err.println(
                        "office parser child exit="
                                + process.exitValue()
                                + " responseBytes="
                                + responseSize(outputFile)
                                + " firstToken="
                                + firstJsonTokenKind(outputFile)
                                + " failureClass="
                                + className);
            }
            return OfficeWorkerProtocol.Response.error(
                    operation, 503, "officeWorkerUnavailable", List.of());
        }
        long outputSize = Files.size(outputFile);
        if (outputSize > MAX_RESPONSE_BYTES) {
            return OfficeWorkerProtocol.Response.error(
                    operation, 413, OfficeWorkerFailureReasons.tooLarge(operation), List.of());
        }
        OfficeWorkerProtocol.Response response;
        try {
            response = JSON.readValue(outputFile.toFile(), OfficeWorkerProtocol.Response.class);
        } catch (Exception exception) {
            if (testMode)
                System.err.println(
                        "office parser response decode class="
                                + exception.getClass().getName()
                                + " responseBytes="
                                + outputSize
                                + " firstToken="
                                + firstJsonTokenKind(outputFile));
            return OfficeWorkerProtocol.Response.error(
                    operation, 503, "officeWorkerUnavailable", List.of());
        }
        if (!operation.equals(response.kind()))
            return OfficeWorkerProtocol.Response.error(
                    operation, 503, "officeWorkerUnavailable", List.of());
        return response;
    }

    private static long responseSize(Path outputFile) {
        try {
            return Files.size(outputFile);
        } catch (IOException exception) {
            return -1;
        }
    }

    private static String firstJsonTokenKind(Path outputFile) {
        try (var input = Files.newInputStream(outputFile)) {
            for (int next; (next = input.read()) >= 0; ) {
                if (Character.isWhitespace(next)) continue;
                return switch (next) {
                    case '{' -> "object";
                    case '[' -> "array";
                    case '"' -> "string";
                    case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> "number";
                    case 't', 'f', 'n' -> "literal";
                    default -> "other";
                };
            }
        } catch (IOException exception) {
            return "unreadable";
        }
        return "empty";
    }

    private static void killTree(Process process) throws InterruptedException {
        var descendants = process.toHandle().descendants().toList();
        for (var child : descendants) child.destroyForcibly();
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline
                && descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            Thread.sleep(10);
        }
    }

    private static boolean authorized(HttpExchange exchange, byte[] expectedDigest) {
        String token = exchange.getRequestHeaders().getFirst(OfficeWorkerProtocol.TOKEN_HEADER);
        if (token == null || token.length() < 32 || token.length() > 256) return false;
        return MessageDigest.isEqual(
                expectedDigest, sha256(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static Path writeInput(InputStream input, Path directory) throws IOException {
        Path path = privateFile(directory.resolve("input.bin"));
        long count = 0;
        byte[] buffer = new byte[16 * 1024];
        try (var output = Files.newOutputStream(path)) {
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read == 0) continue;
                count += read;
                if (count > MAX_INPUT_BYTES) throw new TooLargeInputException();
                output.write(buffer, 0, read);
            }
        }
        if (count == 0) throw new EmptyInputException();
        return path;
    }

    private static boolean tooManyBytes(HttpExchange exchange, String operation)
            throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength == null) return false;
        try {
            long maximum = requiresMetadata(operation) ? MAX_FRAMED_REQUEST_BYTES : MAX_INPUT_BYTES;
            return Long.parseLong(contentLength) > maximum;
        } catch (NumberFormatException exception) {
            return true;
        }
    }

    private static boolean supportedOperation(String operation) {
        return OfficeWorkerProtocol.PREVIEW.equals(operation)
                || OfficeWorkerProtocol.DICTIONARY.equals(operation)
                || OfficeWorkerProtocol.DOCUMENT_INSPECT.equals(operation)
                || OfficeWorkerProtocol.DOCUMENT_PDF.equals(operation)
                || OfficeWorkerProtocol.WORKBOOK_READ.equals(operation)
                || OfficeWorkerProtocol.WORKBOOK_WRITE.equals(operation);
    }

    private static boolean requiresMetadata(String operation) {
        return OfficeWorkerProtocol.DOCUMENT_PDF.equals(operation)
                || OfficeWorkerProtocol.WORKBOOK_WRITE.equals(operation);
    }

    private static boolean supported(String operation, String filename) {
        String extension =
                filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (OfficeWorkerProtocol.PREVIEW.equals(operation))
            return "doc".equals(extension) || "docx".equals(extension);
        if (OfficeWorkerProtocol.DICTIONARY.equals(operation))
            return "xls".equals(extension) || "xlsx".equals(extension);
        if (OfficeWorkerProtocol.DOCUMENT_INSPECT.equals(operation)
                || OfficeWorkerProtocol.DOCUMENT_PDF.equals(operation))
            return "docx".equals(extension) || "xlsx".equals(extension);
        if (OfficeWorkerProtocol.WORKBOOK_READ.equals(operation)
                || OfficeWorkerProtocol.WORKBOOK_WRITE.equals(operation))
            return "xlsx".equals(extension);
        return false;
    }

    private static FramedPayload writeFramedRequest(
            InputStream input, Path directory, String operation) throws IOException {
        byte[] prefix = input.readNBytes(8);
        if (prefix.length != 8
                || !OfficeWorkerProtocol.REQUEST_FRAME_MAGIC.equals(
                        new String(
                                prefix,
                                0,
                                OfficeWorkerProtocol.REQUEST_FRAME_MAGIC.length(),
                                StandardCharsets.US_ASCII))) throw new EmptyInputException();
        int metadataLength = java.nio.ByteBuffer.wrap(prefix, 4, 4).getInt();
        if (metadataLength < 1 || metadataLength > MAX_METADATA_BYTES)
            throw new TooLargeInputException();
        byte[] metadataBytes = input.readNBytes(metadataLength);
        if (metadataBytes.length != metadataLength) throw new EmptyInputException();
        try {
            DocumentWorkerRequestGuard.validate(metadataBytes, operation);
        } catch (IOException invalid) {
            throw new EmptyInputException();
        }
        Path metadata = privateFile(directory.resolve(OfficeWorkerProtocol.CHILD_METADATA_FILE));
        Files.write(metadata, metadataBytes);
        if (OfficeWorkerProtocol.WORKBOOK_WRITE.equals(operation)) {
            if (input.read() != -1) throw new EmptyInputException();
            return new FramedPayload(null, metadata);
        }
        return new FramedPayload(writeInput(input, directory), metadata);
    }

    private record FramedPayload(Path input, Path metadata) {}

    private static String filename(String header) throws IOException {
        if (header == null || header.length() > 1400) throw new IOException("Invalid filename");
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(header);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid filename", exception);
        }
        if (bytes.length == 0 || bytes.length > 1024) throw new IOException("Invalid filename");
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid filename", exception);
        }
    }

    private static Path privateDirectory() throws IOException {
        try {
            return Files.createTempDirectory(
                    "lumira-office-",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException exception) {
            return Files.createTempDirectory("lumira-office-");
        }
    }

    private static Path privateFile(Path path) throws IOException {
        try {
            return Files.createFile(
                    path,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            return Files.createFile(path);
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
        } catch (IOException ignored) {
        }
    }

    private static void reply(HttpExchange exchange, OfficeWorkerProtocol.Response response)
            throws IOException {
        int status = response.status() == 0 ? 200 : response.status();
        byte[] bytes = JSON.writeValueAsBytes(response);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            status = 413;
            bytes =
                    JSON.writeValueAsBytes(
                            OfficeWorkerProtocol.Response.error(
                                    response.kind(),
                                    413,
                                    OfficeWorkerFailureReasons.tooLarge(response.kind()),
                                    List.of()));
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void reply(HttpExchange exchange, String operation, int status, String reason)
            throws IOException {
        reply(exchange, OfficeWorkerProtocol.Response.error(operation, status, reason, List.of()));
    }

    private static void reply(HttpExchange exchange, int status, String detail) throws IOException {
        byte[] bytes = detail.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int integerSetting(String name, int fallback, int minimum, int maximum) {
        try {
            int value =
                    Integer.parseInt(
                            System.getenv().getOrDefault(name, Integer.toString(fallback)));
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid Office worker numeric setting: " + name);
        }
    }

    private static long longSetting(String name, long fallback, long minimum, long maximum) {
        try {
            long value =
                    Long.parseLong(System.getenv().getOrDefault(name, Long.toString(fallback)));
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid Office worker numeric setting: " + name);
        }
    }

    private static final class TooLargeInputException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class EmptyInputException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
