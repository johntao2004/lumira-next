package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

class BoundedBodyAdviceTest {
    HttpInputMessage message(byte[] bytes, long declared) {
        var headers = new HttpHeaders();
        if (declared >= 0) headers.setContentLength(declared);
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() {
                return headers;
            }

            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    @Test
    void preservesExactBoundaryAndContentHeaders() throws Exception {
        var source = message(new byte[] {1, 2, 3, 4}, 4);
        var result = BoundedBodyAdvice.bounded(source, 4, "tooLarge");
        assertArrayEquals(new byte[] {1, 2, 3, 4}, result.getBody().readAllBytes());
        assertSame(source.getHeaders(), result.getHeaders());
    }

    @Test
    void rejectsUnknownLengthAndUnderreportedLength() {
        for (long length : new long[] {-1, 1, 5})
            assertEquals(
                    413,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            BoundedBodyAdvice.bounded(
                                                    message(new byte[5], length), 4, "tooLarge"))
                            .getStatusCode()
                            .value());
    }

    @Test
    void rejectsDeclaredExcessEvenWhenBodyIsShort() {
        assertEquals(
                "tooLarge",
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        BoundedBodyAdvice.bounded(
                                                message(new byte[1], 5), 4, "tooLarge"))
                        .getReason());
    }
}
