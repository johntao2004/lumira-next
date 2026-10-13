package com.lumira.plugins.wechatpay;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict JSON and UTF-8 helpers for signed WeChat Pay messages. */
final class WechatPayJson {
    private static final JsonFactory FACTORY =
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final JsonMapper JSON = JsonMapper.builder(FACTORY).build();
    private static final int MAX_JSON_BYTES = 1024 * 1024;

    private WechatPayJson() {}

    static JsonNode parseObject(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_JSON_BYTES)
            throw new IllegalArgumentException("WeChat Pay JSON size is invalid");
        return parseObject(decodeUtf8(bytes));
    }

    static JsonNode parseObject(String json) {
        if (json == null
                || json.isBlank()
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
            throw new IllegalArgumentException("WeChat Pay JSON size is invalid");
        try (JsonParser parser = FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT)
                throw new IllegalArgumentException("WeChat Pay JSON must be an object");
            JsonNode root = JSON.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null)
                throw new IllegalArgumentException("WeChat Pay JSON is invalid");
            return root;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("WeChat Pay JSON is invalid");
        }
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("WeChat Pay request JSON could not be encoded");
        }
    }

    static String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    static String requiredText(JsonNode object, String field) {
        return requiredText(object, field, 2048);
    }

    static String requiredText(JsonNode object, String field, int maxLength) {
        String value = text(object, field);
        if (value == null
                || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(
                    "WeChat Pay response field is missing or invalid: " + field);
        return value;
    }

    static long requiredLong(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException(
                    "WeChat Pay response number is missing or invalid: " + field);
        return value.longValue();
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("WeChat Pay JSON is not valid UTF-8");
        }
    }
}
