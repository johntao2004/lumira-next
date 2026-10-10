package com.lumira.platform.sms;

import com.lumira.platform.common.SmsConfigurationPort.Settings;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

/** Official wire contracts. This class never performs I/O or logs requests/responses. */
final class SmsProviderProtocol {
    record Request(URI uri, String method, Map<String, String> headers, byte[] body) {
        @Override
        public String toString() {
            return "SmsProviderRequest[redacted]";
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORM = "application/x-www-form-urlencoded";

    static Request request(
            Settings data, OutgoingSms sms, String secret, Instant now, String nonce) {
        if (!data.provider().equals("webhook") && sms.code() == null)
            throw new IllegalArgumentException("Structured verification code required");
        if (sms.configurationKey() != null
                && !sms.configurationKey().equals(configurationKey(data)))
            throw new IllegalArgumentException("SMS configuration changed after enqueue");
        validateDestination(data, sms.recipient());
        return switch (data.provider()) {
            case "webhook" ->
                    new Request(
                            URI.create(data.endpoint()),
                            "POST",
                            Map.of(
                                    "Content-Type",
                                    "application/json",
                                    "Authorization",
                                    "Bearer " + secret),
                            JSON.writeValueAsBytes(
                                    Map.of(
                                            "to",
                                            sms.recipient(),
                                            "sender",
                                            data.sender(),
                                            "message",
                                            sms.text())));
            case "tencent" -> tencent(data, sms, secret, now);
            case "aliyun" -> aliyun(data, sms, secret, now, nonce);
            case "alibaba_intl" -> alibabaInternational(data, sms, secret, now, nonce);
            case "huawei" -> huawei(data, sms, secret, now, nonce);
            case "twilio" -> twilio(data, sms, secret);
            default -> throw new IllegalArgumentException("Unknown SMS provider");
        };
    }

    static void validateDestination(Settings data, String recipient) {
        if (!data.provider().equals("webhook") && !data.provider().equals("twilio")) {
            boolean mainland = recipient.matches("\\+861\\d{10}");
            if (data.market().equals("domestic") != mainland)
                throw new IllegalArgumentException("SMS destination does not match market");
        }
    }

    private static Request tencent(Settings d, OutgoingSms sms, String secret, Instant now) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("PhoneNumberSet", new String[] {sms.recipient()});
        payload.put("SmsSdkAppId", d.appId());
        payload.put("TemplateId", d.templateId());
        payload.put("TemplateParamSet", parameters(d, sms));
        if (!d.sender().isBlank()) payload.put("SignName", d.sender());
        if (d.market().equals("international") && !d.senderId().isBlank())
            payload.put("SenderId", d.senderId());
        byte[] body = JSON.writeValueAsBytes(payload);
        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(now);
        String host = URI.create(d.endpoint()).getHost();
        String contentType = "application/json; charset=utf-8";
        String signed = "content-type;host;x-tc-action";
        String canonical =
                "POST\n/\n\ncontent-type:"
                        + contentType
                        + "\nhost:"
                        + host
                        + "\nx-tc-action:sendsms\n\n"
                        + signed
                        + "\n"
                        + sha(body);
        String scope = date + "/sms/tc3_request";
        String toSign =
                "TC3-HMAC-SHA256\n"
                        + now.getEpochSecond()
                        + "\n"
                        + scope
                        + "\n"
                        + sha(bytes(canonical));
        byte[] dateKey = hmac("HmacSHA256", bytes("TC3" + secret), date);
        byte[] serviceKey = hmac("HmacSHA256", dateKey, "sms");
        byte[] signingKey = hmac("HmacSHA256", serviceKey, "tc3_request");
        String signature;
        try {
            signature = HexFormat.of().formatHex(hmac("HmacSHA256", signingKey, toSign));
        } finally {
            java.util.Arrays.fill(dateKey, (byte) 0);
            java.util.Arrays.fill(serviceKey, (byte) 0);
            java.util.Arrays.fill(signingKey, (byte) 0);
        }
        return new Request(
                URI.create(d.endpoint()),
                "POST",
                Map.of(
                        "Content-Type",
                        contentType,
                        "X-TC-Action",
                        "SendSms",
                        "X-TC-Version",
                        "2021-01-11",
                        "X-TC-Region",
                        d.region(),
                        "X-TC-Timestamp",
                        Long.toString(now.getEpochSecond()),
                        "Authorization",
                        "TC3-HMAC-SHA256 Credential="
                                + d.accessKeyId()
                                + "/"
                                + scope
                                + ", SignedHeaders="
                                + signed
                                + ", Signature="
                                + signature),
                body);
    }

    private static Request aliyun(
            Settings d, OutgoingSms sms, String secret, Instant now, String nonce) {
        var query = new TreeMap<String, String>();
        query.put(
                "PhoneNumbers",
                d.market().equals("domestic")
                        ? sms.recipient().substring(3)
                        : sms.recipient().substring(1));
        query.put("SignName", d.sender());
        query.put("TemplateCode", d.templateId());
        query.put("TemplateParam", JSON.writeValueAsString(parameters(d, sms)));
        String canonicalQuery = encoded(query);
        String host = URI.create(d.endpoint()).getHost();
        var signedHeaders = new TreeMap<String, String>();
        signedHeaders.put("host", host);
        signedHeaders.put("x-acs-action", "SendSms");
        signedHeaders.put("x-acs-version", "2017-05-25");
        signedHeaders.put("x-acs-date", timestamp(now));
        signedHeaders.put("x-acs-signature-nonce", nonce);
        signedHeaders.put("x-acs-content-sha256", sha(new byte[0]));
        String names = String.join(";", signedHeaders.keySet());
        String canonicalHeaders =
                signedHeaders.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue() + "\n")
                        .collect(Collectors.joining());
        String canonical =
                "POST\n/\n"
                        + canonicalQuery
                        + "\n"
                        + canonicalHeaders
                        + "\n"
                        + names
                        + "\n"
                        + sha(new byte[0]);
        String signature =
                HexFormat.of()
                        .formatHex(
                                hmac(
                                        "HmacSHA256",
                                        bytes(secret),
                                        "ACS3-HMAC-SHA256\n" + sha(bytes(canonical))));
        var headers = new LinkedHashMap<>(signedHeaders);
        headers.remove("host");
        headers.put(
                "Authorization",
                "ACS3-HMAC-SHA256 Credential="
                        + d.accessKeyId()
                        + ",SignedHeaders="
                        + names
                        + ",Signature="
                        + signature);
        return new Request(
                URI.create(d.endpoint() + "/?" + canonicalQuery), "POST", headers, new byte[0]);
    }

    private static Request alibabaInternational(
            Settings d, OutgoingSms sms, String secret, Instant now, String nonce) {
        var query = new TreeMap<String, String>();
        query.put("AccessKeyId", d.accessKeyId());
        query.put("Format", "JSON");
        query.put("Version", "2018-05-01");
        query.put("Timestamp", timestamp(now));
        query.put("SignatureNonce", nonce);
        query.put("SignatureMethod", "HMAC-SHA1");
        query.put("SignatureVersion", "1.0");
        query.put("RegionId", "ap-southeast-1");
        query.put("To", sms.recipient().substring(1));
        query.put("From", d.sender());
        if (d.market().equals("domestic")) {
            query.put("Action", "SendMessageWithTemplate");
            query.put("TemplateCode", d.templateId());
            query.put("TemplateParam", JSON.writeValueAsString(parameters(d, sms)));
        } else {
            query.put("Action", "SendMessageToGlobe");
            query.put("Message", sms.text());
        }
        String toSign = "GET&%2F&" + encode(encoded(query));
        query.put(
                "Signature",
                Base64.getEncoder().encodeToString(hmac("HmacSHA1", bytes(secret + "&"), toSign)));
        return new Request(
                URI.create(d.endpoint() + "/?" + encoded(query)),
                "GET",
                Map.of("Accept", "application/json"),
                null);
    }

    private static Request huawei(
            Settings d, OutgoingSms sms, String secret, Instant now, String nonce) {
        var form = new TreeMap<String, String>();
        form.put("from", d.sender());
        form.put("to", sms.recipient());
        form.put("templateId", d.templateId());
        form.put("templateParas", JSON.writeValueAsString(parameters(d, sms)));
        if (d.market().equals("domestic") && !d.signature().isBlank())
            form.put("signature", d.signature());
        String created = timestamp(now);
        String digest = Base64.getEncoder().encodeToString(hash(bytes(nonce + created + secret)));
        return new Request(
                URI.create(d.endpoint()),
                "POST",
                Map.of(
                        "Content-Type",
                        FORM,
                        "Authorization",
                        "WSSE realm=\"SDP\",profile=\"UsernameToken\",type=\"Appkey\"",
                        "X-WSSE",
                        "UsernameToken Username=\""
                                + d.accessKeyId()
                                + "\",PasswordDigest=\""
                                + digest
                                + "\",Nonce=\""
                                + nonce
                                + "\",Created=\""
                                + created
                                + "\""),
                bytes(encoded(form)));
    }

    private static Request twilio(Settings d, OutgoingSms sms, String secret) {
        var form = new TreeMap<String, String>();
        form.put("To", sms.recipient());
        form.put("Body", sms.text());
        form.put(d.sender().startsWith("MG") ? "MessagingServiceSid" : "From", d.sender());
        String username = d.accessKeyId().isBlank() ? d.appId() : d.accessKeyId();
        return new Request(
                URI.create(d.endpoint() + "/2010-04-01/Accounts/" + d.appId() + "/Messages.json"),
                "POST",
                Map.of(
                        "Content-Type",
                        FORM,
                        "Authorization",
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(bytes(username + ":" + secret))),
                bytes(encoded(form)));
    }

    private static Object parameters(Settings d, OutgoingSms sms) {
        if (sms.code() == null)
            throw new IllegalArgumentException("Structured verification code required");
        var source = JSON.readTree(d.templateParams());
        if (source.isArray()) {
            var result = new java.util.ArrayList<String>();
            source.forEach(value -> result.add(expand(value.asString(), sms)));
            return result;
        }
        var result = new LinkedHashMap<String, String>();
        source.properties()
                .forEach(
                        entry ->
                                result.put(
                                        entry.getKey(), expand(entry.getValue().asString(), sms)));
        return result;
    }

    private static String expand(String text, OutgoingSms sms) {
        return text.replace("{code}", sms.code())
                .replace(
                        "{minutes}",
                        Integer.toString(Math.max(1, (sms.lifetimeSeconds() + 59) / 60)));
    }

    static String configurationKey(Settings d) {
        return sha(
                JSON.writeValueAsBytes(
                        java.util.List.of(
                                d.provider(),
                                d.endpoint(),
                                d.sender(),
                                d.template(),
                                d.market(),
                                d.region(),
                                d.accessKeyId(),
                                d.appId(),
                                d.templateId(),
                                d.templateParams(),
                                d.senderId(),
                                d.signature())));
    }

    static String timestamp(Instant time) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(time);
    }

    static String encoded(Map<String, String> parameters) {
        return parameters.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Digest unavailable");
        }
    }

    static String sha(byte[] value) {
        return HexFormat.of().formatHex(hash(value));
    }

    static byte[] hmac(String algorithm, byte[] key, String value) {
        try {
            var mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(bytes(value));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signature unavailable");
        }
    }
}
