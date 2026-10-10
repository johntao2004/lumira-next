package com.lumira.platform.common;

import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice
public class BoundedBodyAdvice extends RequestBodyAdviceAdapter {
    @Override
    public boolean supports(
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.hasParameterAnnotation(BoundedBody.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage message,
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter)
            throws IOException {
        var budget = parameter.getParameterAnnotation(BoundedBody.class);
        return bounded(message, budget.maxBytes(), budget.errorCode());
    }

    static HttpInputMessage bounded(HttpInputMessage message, int limit, String code)
            throws IOException {
        if (limit < 1 || limit == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid body budget");
        // Check actual bytes too: Content-Length may be absent for a chunked request.
        byte[] bytes = message.getBody().readNBytes(limit + 1);
        if (bytes.length > limit || message.getHeaders().getContentLength() > limit)
            throw oversized(code);
        return new HttpInputMessage() {
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }

    private static ResponseStatusException oversized(String code) {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, code);
    }
}
