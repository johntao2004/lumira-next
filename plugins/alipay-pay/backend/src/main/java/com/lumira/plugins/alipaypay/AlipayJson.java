package com.lumira.plugins.alipaypay;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses signed Alipay JSON while retaining the exact response-object substring for RSA2. */
final class AlipayJson {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final JsonFactory FACTORY =
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final JsonMapper JSON = JsonMapper.builder(FACTORY).build();

    private AlipayJson() {}

    static JsonNode verifiedResponse(
            byte[] bytes, String responseName, AlipaySettings.Configuration config) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES)
            throw new IllegalArgumentException("Alipay response size is invalid");
        String body = decodeUtf8(bytes);
        JsonNode root = parseStrict(body);
        if (root == null || !root.isObject())
            throw new IllegalArgumentException("Alipay response is not an object");
        JsonNode signatureNode = root.get("sign");
        if (signatureNode == null || !signatureNode.isTextual() || signatureNode.asText().isBlank())
            throw new IllegalArgumentException("Alipay response signature is missing");
        JsonNode response = root.get(responseName);
        if (response == null || !response.isObject())
            throw new IllegalArgumentException("Alipay response payload is missing");
        String rawResponse = exactTopLevelObject(body, responseName);
        if (!PaymentCrypto.verifySha256Rsa(config.publicKey(), rawResponse, signatureNode.asText()))
            throw new IllegalArgumentException("Alipay response signature is invalid");
        return response;
    }

    static String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    static String requiredText(JsonNode object, String field) {
        String value = text(object, field);
        if (value == null)
            throw new IllegalArgumentException("Alipay response field is missing: " + field);
        return value;
    }

    static void requireSuccess(JsonNode response) {
        if (!"10000".equals(text(response, "code"))) {
            String subCode = text(response, "sub_code");
            throw new IllegalStateException(
                    "Alipay rejected the request ("
                            + (subCode == null ? "provider error" : subCode)
                            + ")");
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Alipay response is not valid UTF-8");
        }
    }

    private static JsonNode parseStrict(String body) {
        try (JsonParser parser = FACTORY.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT)
                throw new IllegalArgumentException("Alipay response JSON is invalid");
            JsonNode root = JSON.readTree(parser);
            if (parser.nextToken() != null)
                throw new IllegalArgumentException("Alipay response has trailing data");
            return root;
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("Alipay response JSON is invalid");
        }
    }

    private static String exactTopLevelObject(String body, String expectedName) {
        int cursor = skipWhitespace(body, 0);
        if (cursor >= body.length() || body.charAt(cursor++) != '{')
            throw new IllegalArgumentException("Alipay response JSON is invalid");
        for (; ; ) {
            cursor = skipWhitespace(body, cursor);
            if (cursor >= body.length() || body.charAt(cursor) == '}') break;
            int keyEnd = stringEnd(body, cursor);
            String key;
            try {
                key = JSON.readValue(body.substring(cursor, keyEnd), String.class);
            } catch (tools.jackson.core.JacksonException exception) {
                throw new IllegalArgumentException("Alipay response JSON is invalid");
            }
            cursor = skipWhitespace(body, keyEnd);
            if (cursor >= body.length() || body.charAt(cursor++) != ':')
                throw new IllegalArgumentException("Alipay response JSON is invalid");
            int valueStart = skipWhitespace(body, cursor);
            int valueEnd = valueEnd(body, valueStart);
            if (expectedName.equals(key)) {
                if (body.charAt(valueStart) != '{')
                    throw new IllegalArgumentException("Alipay response payload is not an object");
                return body.substring(valueStart, valueEnd);
            }
            cursor = skipWhitespace(body, valueEnd);
            if (cursor < body.length() && body.charAt(cursor) == ',') cursor++;
            else if (cursor < body.length() && body.charAt(cursor) == '}') break;
            else throw new IllegalArgumentException("Alipay response JSON is invalid");
        }
        throw new IllegalArgumentException("Alipay response payload is missing");
    }

    private static int valueEnd(String body, int start) {
        char first = body.charAt(start);
        if (first == '"') return stringEnd(body, start);
        if (first != '{' && first != '[') {
            int cursor = start;
            while (cursor < body.length()
                    && body.charAt(cursor) != ','
                    && body.charAt(cursor) != '}'
                    && body.charAt(cursor) != ']') cursor++;
            while (cursor > start && Character.isWhitespace(body.charAt(cursor - 1))) cursor--;
            return cursor;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int cursor = start; cursor < body.length(); cursor++) {
            char character = body.charAt(cursor);
            if (inString) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
            } else if (character == '"') inString = true;
            else if (character == '{' || character == '[') depth++;
            else if (character == '}' || character == ']') {
                depth--;
                if (depth == 0) return cursor + 1;
            }
        }
        throw new IllegalArgumentException("Alipay response JSON is invalid");
    }

    private static int stringEnd(String body, int start) {
        if (start >= body.length() || body.charAt(start) != '"')
            throw new IllegalArgumentException("Alipay response JSON is invalid");
        boolean escaped = false;
        for (int cursor = start + 1; cursor < body.length(); cursor++) {
            char character = body.charAt(cursor);
            if (escaped) escaped = false;
            else if (character == '\\') escaped = true;
            else if (character == '"') return cursor + 1;
        }
        throw new IllegalArgumentException("Alipay response JSON is invalid");
    }

    private static int skipWhitespace(String value, int cursor) {
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
        return cursor;
    }
}
