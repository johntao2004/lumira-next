package com.lumira.platform.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

import java.util.List;

/** Enforce JSON field types at the HTTP boundary without changing storage or provider clients. */
@Configuration
public class RequestJsonConfiguration implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (int index = 0; index < converters.size(); index++) {
            if (converters.get(index) instanceof JacksonJsonHttpMessageConverter original) {
                var requests = new RequestConverter(strictMapper(original.getMapper()));
                requests.setSupportedMediaTypes(original.getSupportedMediaTypes());
                converters.add(index, requests);
                return;
            }
        }
    }

    static JsonMapper strictMapper(JsonMapper original) {
        var builder = original.rebuild();
        builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        builder.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        builder.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        builder.withCoercionConfig(
                LogicalType.Integer,
                config -> {
                    config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                });
        builder.withCoercionConfig(
                LogicalType.Float,
                config -> {
                    config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                });
        builder.withCoercionConfig(
                LogicalType.Boolean,
                config -> {
                    config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                    config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                });
        builder.withCoercionConfig(
                LogicalType.Textual,
                config -> {
                    config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                    config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                });
        return builder.build();
    }

    private static final class RequestConverter extends JacksonJsonHttpMessageConverter {
        private RequestConverter(JsonMapper mapper) {
            super(mapper);
        }

        @Override
        public boolean canWrite(ResolvableType type, Class<?> valueClass, MediaType mediaType) {
            return false;
        }

        @Override
        public boolean canWrite(Class<?> valueClass, MediaType mediaType) {
            return false;
        }
    }
}
