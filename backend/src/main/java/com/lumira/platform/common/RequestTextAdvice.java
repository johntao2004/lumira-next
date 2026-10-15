package com.lumira.platform.common;

import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL text cannot store NUL; reject submitted business text before any writes. */
@ControllerAdvice
public class RequestTextAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper mapper;

    public RequestTextAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        return JacksonJsonHttpMessageConverter.class.isAssignableFrom(converter);
    }

    @Override
    public Object afterBodyRead(
            Object body,
            HttpInputMessage message,
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        validate(mapper.valueToTree(body));
        return body;
    }

    private static void validate(JsonNode value) {
        if (value.isString() && value.stringValue().indexOf('\0') >= 0) throw invalid();
        if (value.isObject()) {
            for (var entry : value.properties()) {
                if (entry.getKey().indexOf('\0') >= 0) throw invalid();
                validate(entry.getValue());
            }
        } else if (value.isArray()) {
            for (var item : value) validate(item);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRequest");
    }
}
