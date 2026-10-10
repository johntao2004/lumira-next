package com.lumira.platform.sms;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.SmsConfigurationPort.Settings;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SmsProviderProtocolTest {
    static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    static final String NONCE = "fixture-nonce";
    static final String SECRET = "fixture-secret";
    static final ObjectMapper JSON = new ObjectMapper();

    static Settings settings(String provider, String market) {
        String endpoint =
                switch (provider) {
                    case "tencent" -> "https://sms.tencentcloudapi.com";
                    case "aliyun" -> "https://dysmsapi.aliyuncs.com";
                    case "alibaba_intl" -> "https://dysmsapi.ap-southeast-1.aliyuncs.com";
                    case "huawei" ->
                            "https://smsapi.cn-north-4.myhuaweicloud.com/sms/batchSendSms/v1";
                    case "twilio" -> "https://api.twilio.com";
                    default -> "http://127.0.0.1:18080/send";
                };
        return new Settings(
                true,
                provider,
                endpoint,
                provider.equals("huawei")
                        ? "10690000"
                        : provider.equals("twilio") ? "+15555550100" : "Lumira",
                "Code {code}",
                10,
                market,
                provider.equals("twilio") ? "us1" : "ap-guangzhou",
                "fixture-id",
                provider.equals("twilio") ? "AC" + "a".repeat(32) : "1400006666",
                provider.equals("tencent") ? "1110" : "SMS_42",
                provider.equals("tencent") || provider.equals("huawei")
                        ? "[\"{code}\"]"
                        : "{\"code\":\"{code}\"}",
                "GlobalSender",
                "");
    }

    static OutgoingSms sms(Settings d, String phone) {
        return new OutgoingSms(
                phone, "Code 002569", "002569", 600, SmsProviderProtocol.configurationKey(d));
    }

    static SmsProviderProtocol.Request request(Settings d, String phone) {
        return SmsProviderProtocol.request(d, sms(d, phone), SECRET, NOW, NONCE);
    }

    static Map<String, String> form(String encoded) {
        return java.util.Arrays.stream(encoded.split("&"))
                .map(s -> s.split("=", 2))
                .collect(
                        Collectors.toMap(
                                s -> URLDecoder.decode(s[0], StandardCharsets.UTF_8),
                                s -> URLDecoder.decode(s[1], StandardCharsets.UTF_8)));
    }

    @Test
    void tencentPayloadAndTc3SignatureMatchIndependentVector() {
        var d = settings("tencent", "domestic");
        var r = request(d, "+8613800138000");
        var body = JSON.readTree(r.body());
        assertEquals("002569", body.path("TemplateParamSet").get(0).asString());
        assertFalse(body.has("SenderId"));
        assertEquals("2021-01-11", r.headers().get("X-TC-Version"));
        assertTrue(
                r.headers()
                        .get("Authorization")
                        .endsWith(
                                "Signature=5eb618e91ae21ebca37f8a322b4372d0112dfc0019625c5b03f05564309eccb2"));
        assertEquals("POST", r.method());
        assertFalse(new String(r.body(), StandardCharsets.UTF_8).contains(SECRET));
        var intl =
                JSON.readTree(request(settings("tencent", "international"), "+15555550123").body());
        assertEquals("GlobalSender", intl.path("SenderId").asString());
    }

    @Test
    void aliyunAcs3SignatureAndPhoneEncodingMatchIndependentVector() {
        var r = request(settings("aliyun", "domestic"), "+8613800138000");
        var query = form(r.uri().getRawQuery());
        assertEquals("13800138000", query.get("PhoneNumbers"));
        assertEquals("{\"code\":\"002569\"}", query.get("TemplateParam"));
        assertTrue(
                r.headers()
                        .get("Authorization")
                        .endsWith(
                                "Signature=3792301d13b9a147af82e0affc43a55b281e4d790f551ed5c4496089452c164f"));
        assertEquals(
                "15555550123",
                form(request(settings("aliyun", "international"), "+15555550123")
                                .uri()
                                .getRawQuery())
                        .get("PhoneNumbers"));
    }

    @Test
    void alibabaInternationalSelectsTheOfficialActionForEachMarket() {
        var cn =
                form(
                        request(settings("alibaba_intl", "domestic"), "+8613800138000")
                                .uri()
                                .getRawQuery());
        assertEquals("SendMessageWithTemplate", cn.get("Action"));
        assertEquals("2018-05-01", cn.get("Version"));
        assertEquals("HMAC-SHA1", cn.get("SignatureMethod"));
        assertEquals("8613800138000", cn.get("To"));
        var global =
                form(
                        request(settings("alibaba_intl", "international"), "+15555550123")
                                .uri()
                                .getRawQuery());
        assertEquals("SendMessageToGlobe", global.get("Action"));
        assertEquals("Code 002569", global.get("Message"));
        assertFalse(global.containsKey("TemplateCode"));
        assertEquals("ABRoCdLY/GxzRs24FAWVgyx2rsg=", cn.get("Signature"));
        assertEquals("8xEQdtA8gn9p5bsGzPOUERDa3TM=", global.get("Signature"));
    }

    @Test
    void huaweiWsseUsesBase64OfRawSha256AndPreservesLeadingZeroes() {
        var r = request(settings("huawei", "domestic"), "+8613800138000");
        assertTrue(
                r.headers()
                        .get("X-WSSE")
                        .contains(
                                "PasswordDigest=\"bFAJFyRODy62gmtBC90ytBNyozwFjyjFMKrBEYgp39U=\""));
        var fields = form(new String(r.body(), StandardCharsets.UTF_8));
        assertEquals("[\"002569\"]", fields.get("templateParas"));
        assertEquals("+8613800138000", fields.get("to"));
    }

    @Test
    void twilioUsesBasicAuthenticationAndMessagesResource() {
        var r = request(settings("twilio", "international"), "+15555550123");
        assertTrue(r.uri().getPath().endsWith("/Accounts/AC" + "a".repeat(32) + "/Messages.json"));
        assertEquals("Code 002569", form(new String(r.body(), StandardCharsets.UTF_8)).get("Body"));
        assertEquals(
                "Basic "
                        + java.util.Base64.getEncoder()
                                .encodeToString(
                                        ("fixture-id:" + SECRET).getBytes(StandardCharsets.UTF_8)),
                r.headers().get("Authorization"));
    }

    @Test
    void cloudCodeCannotBeGuessedFromTextAndQueuedConfigurationCannotBeReplaced() {
        var d = settings("tencent", "domestic");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SmsProviderProtocol.request(
                                d,
                                new OutgoingSms("+8613800138000", "Code 002569"),
                                SECRET,
                                NOW,
                                NONCE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SmsProviderProtocol.request(
                                d,
                                sms(settings("aliyun", "domestic"), "+8613800138000"),
                                SECRET,
                                NOW,
                                NONCE));
        assertThrows(IllegalArgumentException.class, () -> request(d, "+15555550123"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SmsProviderProtocol.request(
                                settings("twilio", "international"),
                                new OutgoingSms("+15555550123", "Legacy code"),
                                SECRET,
                                NOW,
                                NONCE));
    }
}
