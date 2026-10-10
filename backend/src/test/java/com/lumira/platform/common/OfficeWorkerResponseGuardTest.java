package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Host-side response-bound unit tests; they do not prove the worker container's cgroup limit. */
class OfficeWorkerResponseGuardTest {
    @Test
    void acceptsTheBoundedPreviewAndDictionaryProtocolShapes() {
        assertDoesNotThrow(
                () ->
                        OfficeWorkerResponseGuard.validate(
                                ("{\"kind\":\"preview\",\"preview\":{\"text\":\"hello\","
                                                + "\"truncated\":false},\"dictionary\":null,"
                                                + "\"status\":0,\"reason\":null,\"issues\":[]}")
                                        .getBytes(StandardCharsets.UTF_8),
                                "preview"));
        assertDoesNotThrow(
                () ->
                        OfficeWorkerResponseGuard.validate(
                                ("{\"kind\":\"dictionary\",\"preview\":null,\"dictionary\":{"
                                                + "\"sha256\":\""
                                                + "a".repeat(64)
                                                + "\",\"items\":[{\"value\":\"root\","
                                                + "\"label\":\"Root\",\"parentValue\":null,"
                                                + "\"sortOrder\":10,\"enabled\":true,\"remark\":\"\"}]},"
                                                + "\"status\":0,\"reason\":null,\"issues\":[]}")
                                        .getBytes(StandardCharsets.UTF_8),
                                "dictionary"));
    }

    @Test
    void rejectsOversizedCellsArraysAndPreviewTextBeforeModelBinding() {
        var oversizedCell =
                ("{\"kind\":\"dictionary\",\"preview\":null,\"dictionary\":{"
                                + "\"sha256\":\""
                                + "a".repeat(64)
                                + "\",\"items\":[{\"value\":\""
                                + "x".repeat(201)
                                + "\",\"label\":\"Root\",\"parentValue\":null,"
                                + "\"sortOrder\":10,\"enabled\":true,\"remark\":\"\"}]},"
                                + "\"status\":0,\"reason\":null,\"issues\":[]}")
                        .getBytes(StandardCharsets.UTF_8);
        assertThrows(
                IllegalArgumentException.class,
                () -> OfficeWorkerResponseGuard.validate(oversizedCell, "dictionary"));

        String item =
                "{\"value\":\"x\",\"label\":\"X\",\"parentValue\":null,"
                        + "\"sortOrder\":1,\"enabled\":true,\"remark\":\"\"}";
        String tooManyItems =
                "{\"kind\":\"dictionary\",\"preview\":null,\"dictionary\":{"
                        + "\"sha256\":\""
                        + "a".repeat(64)
                        + "\",\"items\":["
                        + (item + ",").repeat(20_000)
                        + item
                        + "]},\"status\":0,\"reason\":null,\"issues\":[]}";
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OfficeWorkerResponseGuard.validate(
                                tooManyItems.getBytes(StandardCharsets.UTF_8), "dictionary"));

        String tooMuchText =
                "{\"kind\":\"preview\",\"preview\":{\"text\":\""
                        + "x".repeat(OfficeDocumentProcessor.MAX_PREVIEW_CODE_POINTS * 2 + 1)
                        + "\",\"truncated\":false},\"dictionary\":null,"
                        + "\"status\":0,\"reason\":null,\"issues\":[]}";
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OfficeWorkerResponseGuard.validate(
                                tooMuchText.getBytes(StandardCharsets.UTF_8), "preview"));
    }

    @Test
    void rejectsUnexpectedFieldsAndOperationConfusion() {
        var unknownField =
                "{\"kind\":\"preview\",\"preview\":{\"text\":\"x\",\"truncated\":false,"
                        + "\"extra\":\"unbounded\"},\"dictionary\":null,\"status\":0,"
                        + "\"reason\":null,\"issues\":[]}";
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OfficeWorkerResponseGuard.validate(
                                unknownField.getBytes(StandardCharsets.UTF_8), "preview"));
        var wrongOperation =
                "{\"kind\":\"dictionary\",\"preview\":null,\"dictionary\":null,"
                        + "\"status\":0,\"reason\":null,\"issues\":[]}";
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OfficeWorkerResponseGuard.validate(
                                wrongOperation.getBytes(StandardCharsets.UTF_8), "preview"));
    }
}
