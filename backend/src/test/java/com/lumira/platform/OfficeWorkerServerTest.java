package com.lumira.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OfficeWorkerServerTest {
    private static final long MEMORY_LIMIT_BYTES = 1024L * 1024 * 1024;

    @Test
    void acceptsProcessCapsUpToTheComposeLimitAndRejectsLargerOrUnboundedCaps() throws Exception {
        assertDoesNotThrow(
                () -> OfficeWorkerServer.validateResourceLimits(MEMORY_LIMIT_BYTES, 0, 64));
        assertDoesNotThrow(
                () ->
                        OfficeWorkerServer.validateResourceLimits(
                                MEMORY_LIMIT_BYTES, 0, OfficeWorkerServer.MAX_PROCESS_LIMIT));
        assertThrows(
                IllegalStateException.class,
                () ->
                        OfficeWorkerServer.validateResourceLimits(
                                MEMORY_LIMIT_BYTES, 0, OfficeWorkerServer.MAX_PROCESS_LIMIT + 1));
        assertThrows(IllegalStateException.class, () -> OfficeWorkerServer.finiteLimit("max"));
    }

    @Test
    void workerComposeProcessLimitMatchesTheHardGate() throws Exception {
        String compose = Files.readString(Path.of("..", "deploy", "office-worker.compose.yml"));
        int workerStart = compose.indexOf("  office-worker:");
        int ingressStart = compose.indexOf("  office-worker-ingress:", workerStart);
        assertTrue(workerStart >= 0 && ingressStart > workerStart);
        String workerService = compose.substring(workerStart, ingressStart);
        var matcher = Pattern.compile("(?m)^\\s*pids_limit:\\s*(\\d+)\\s*$").matcher(workerService);

        assertTrue(matcher.find(), "worker Compose service must set a finite PID limit");
        assertEquals(OfficeWorkerServer.MAX_PROCESS_LIMIT, Long.parseLong(matcher.group(1)));
    }
}
