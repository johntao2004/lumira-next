package com.lumira.platform.settings;

import com.lumira.platform.common.SmsConfigurationPort;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Provider configuration contains identifiers and templates, never provider secrets. */
public record SmsSettings(
        Boolean enabled,
        String provider,
        String endpoint,
        String sender,
        String template,
        Integer timeoutSeconds,
        String market,
        String region,
        String accessKeyId,
        String appId,
        String templateId,
        String templateParams,
        String senderId,
        String signature) {
    private static final Set<String> PROVIDERS =
            Set.of("", "webhook", "tencent", "aliyun", "alibaba_intl", "huawei", "twilio");

    public SmsSettings {
        market =
                market == null
                        ? ("twilio".equals(provider) ? "international" : "domestic")
                        : market;
        region = region == null ? "" : region;
        accessKeyId = accessKeyId == null ? "" : accessKeyId;
        appId = appId == null ? "" : appId;
        templateId = templateId == null ? "" : templateId;
        templateParams = templateParams == null ? "" : templateParams;
        senderId = senderId == null ? "" : senderId;
        signature = signature == null ? "" : signature;
    }

    public SmsSettings(
            Boolean enabled,
            String provider,
            String endpoint,
            String sender,
            String template,
            Integer timeoutSeconds) {
        this(
                enabled,
                provider,
                endpoint,
                sender,
                template,
                timeoutSeconds,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static SmsSettings defaults() {
        return new SmsSettings(false, "", "", "", "Lumira verification code: {code}", 10);
    }

    public SmsConfigurationPort.Settings connectionSettings() {
        return new SmsConfigurationPort.Settings(
                enabled,
                provider,
                endpoint,
                sender,
                template,
                timeoutSeconds,
                market,
                region,
                accessKeyId,
                appId,
                templateId,
                templateParams,
                senderId,
                signature);
    }

    public void validate() {
        if (enabled == null
                || provider == null
                || !PROVIDERS.contains(provider == null ? "" : provider)
                || timeoutSeconds == null
                || timeoutSeconds < 1
                || timeoutSeconds > 30
                || !(market.equals("domestic") || market.equals("international"))) invalid();
        bounded(endpoint, 2000);
        bounded(sender, 64);
        bounded(template, 500);
        bounded(region, 40);
        bounded(accessKeyId, 128);
        bounded(appId, 64);
        bounded(templateId, 64);
        if (templateParams.length() > 2000
                || templateParams
                        .codePoints()
                        .anyMatch(c -> Character.isISOControl(c) && c != 10 && c != 13 && c != 9))
            invalid();
        bounded(senderId, 64);
        bounded(signature, 64);
        if (template.isBlank()
                || !template.contains("{code}")
                || template.replace("{code}", "").replace("{minutes}", "").contains("{")) invalid();
        // Validate drafts too, but only require account-specific cloud fields when enabling.
        if (provider.isEmpty()) {
            if (enabled) invalid();
            validateEndpoint(true);
            return;
        }
        if (provider.equals("webhook")) {
            if (sender.isBlank()) invalid();
            validateEndpoint(true);
            return;
        }
        validateEndpoint(false);
        switch (provider) {
            case "tencent" -> {
                if (!Set.of("ap-guangzhou", "ap-beijing", "ap-nanjing").contains(region)) invalid();
                if (!endpoint.equals("https://sms.tencentcloudapi.com")) invalid();
                identifier(accessKeyId);
                identifier(appId);
                identifier(templateId);
                if (enabled
                        && (accessKeyId.isBlank()
                                || !appId.matches("\\d{1,20}")
                                || !templateId.matches("\\d{1,20}")
                                || (market.equals("domestic") && sender.isBlank()))) invalid();
                validateParameters(false);
            }
            case "aliyun", "alibaba_intl" -> {
                String expected =
                        provider.equals("aliyun")
                                ? "https://dysmsapi.aliyuncs.com"
                                : "https://dysmsapi.ap-southeast-1.aliyuncs.com";
                if (!endpoint.equals(expected)) invalid();
                identifier(accessKeyId);
                identifier(templateId);
                if (enabled
                        && (accessKeyId.isBlank()
                                || sender.isBlank()
                                || ((provider.equals("aliyun") || market.equals("domestic"))
                                        && templateId.isBlank()))) invalid();
                if (provider.equals("aliyun") || market.equals("domestic"))
                    validateParameters(true);
            }
            case "huawei" -> {
                URI uri = URI.create(endpoint);
                if (!uri.getHost().matches("smsapi\\.[a-z0-9-]+\\.myhuaweicloud\\.com")
                        || !"/sms/batchSendSms/v1".equals(uri.getPath())) invalid();
                identifier(accessKeyId);
                identifier(templateId);
                if (enabled && (accessKeyId.isBlank() || sender.isBlank() || templateId.isBlank()))
                    invalid();
                validateParameters(false);
            }
            case "twilio" -> {
                if (!Set.of("us1", "ie1").contains(region)) invalid();
                if (!endpoint.equals(
                        region.equals("ie1")
                                ? "https://api.dublin.ie1.twilio.com"
                                : "https://api.twilio.com")) invalid();
                if (!appId.isEmpty() && !appId.matches("AC[0-9a-fA-F]{32}")) invalid();
                if (!accessKeyId.isEmpty() && !accessKeyId.matches("SK[0-9a-fA-F]{32}")) invalid();
                if (!sender.isEmpty()
                        && !sender.matches("\\+[1-9]\\d{6,14}|[A-Za-z0-9]{1,11}|MG[0-9a-fA-F]{32}"))
                    invalid();
                if (enabled && (appId.isBlank() || sender.isBlank())) invalid();
            }
            default -> invalid();
        }
    }

    private void validateParameters(boolean object) {
        if (templateParams.isBlank()) {
            if (enabled) invalid();
            return;
        }
        try {
            var parameters = new ObjectMapper().readTree(templateParams);
            if (parameters == null
                    || (object ? !parameters.isObject() : !parameters.isArray())
                    || parameters.size() > 20) invalid();
            boolean code = false;
            for (var value : parameters) {
                if (!value.isString() || value.asString().length() > 256) invalid();
                String text = value.asString();
                if (text.codePoints().anyMatch(Character::isISOControl)) invalid();
                code |= text.contains("{code}");
                if (text.replace("{code}", "").replace("{minutes}", "").contains("{")
                        || text.contains("{phone}")) invalid();
            }
            if (!code) invalid();
        } catch (RuntimeException invalidParameters) {
            invalid();
        }
    }

    private void validateEndpoint(boolean webhook) {
        if (endpoint.isBlank()) {
            if (enabled || !webhook) invalid();
            return;
        }
        try {
            URI uri = URI.create(endpoint);
            boolean local =
                    Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                            .contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || endpoint.contains("\\")
                    || !("https".equalsIgnoreCase(uri.getScheme())
                            || (webhook && local && "http".equalsIgnoreCase(uri.getScheme())))
                    || (!local && !com.lumira.platform.common.PublicHosts.isAllowed(uri.getHost()))
                    || (!webhook && uri.getPort() != -1 && uri.getPort() != 443)) invalid();
        } catch (IllegalArgumentException badUri) {
            invalid();
        }
    }

    private static void bounded(String value, int maximum) {
        if (value == null
                || value.length() > maximum
                || value.codePoints().anyMatch(Character::isISOControl)) invalid();
    }

    private static void identifier(String value) {
        if (!value.isEmpty() && !value.matches("[A-Za-z0-9_-]+")) invalid();
    }

    public boolean sameCredentialDestination(SmsSettings other) {
        return other != null
                && provider.equals(other.provider)
                && sameEndpoint(endpoint, other.endpoint)
                && accessKeyId.equals(other.accessKeyId)
                && appId.equals(other.appId);
    }

    private static boolean sameEndpoint(String left, String right) {
        if (left == null || right == null) return false;
        if (left.isBlank() || right.isBlank()) return left.isBlank() && right.isBlank();
        try {
            URI leftUri = URI.create(left);
            URI rightUri = URI.create(right);
            return equalsIgnoreCase(leftUri.getScheme(), rightUri.getScheme())
                    && equalsIgnoreCase(leftUri.getHost(), rightUri.getHost())
                    && effectivePort(leftUri) == effectivePort(rightUri)
                    && java.util.Objects.equals(leftUri.getRawPath(), rightUri.getRawPath())
                    && java.util.Objects.equals(leftUri.getRawUserInfo(), rightUri.getRawUserInfo())
                    && java.util.Objects.equals(leftUri.getRawQuery(), rightUri.getRawQuery())
                    && java.util.Objects.equals(
                            leftUri.getRawFragment(), rightUri.getRawFragment());
        } catch (IllegalArgumentException invalidUri) {
            return false;
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        if ("https".equalsIgnoreCase(uri.getScheme())) return 443;
        if ("http".equalsIgnoreCase(uri.getScheme())) return 80;
        return -1;
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSms");
    }
}
