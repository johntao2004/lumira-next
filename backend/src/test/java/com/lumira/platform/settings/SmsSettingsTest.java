package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.plugins.deliverysettings.SmsSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class SmsSettingsTest {
    @Test
    void anUnselectedProviderIsValidOnlyWhileDisabled() {
        assertEquals("", SmsSettings.defaults().provider());
        assertDoesNotThrow(() -> SmsSettings.defaults().validate());
        assertThrows(
                ResponseStatusException.class,
                () -> new SmsSettings(true, "", "", "", "Code {code}", 10).validate());
    }

    private SmsSettings cloud(
            String provider,
            String market,
            boolean enabled,
            String endpoint,
            String sender,
            String region,
            String accessKeyId,
            String appId,
            String templateId,
            String parameters) {
        return new SmsSettings(
                enabled,
                provider,
                endpoint,
                sender,
                "Code {code}",
                10,
                market,
                region,
                accessKeyId,
                appId,
                templateId,
                parameters,
                "",
                "");
    }

    @Test
    void cloudContractsAcceptDomesticAndInternationalConfigurations() {
        for (String market : new String[] {"domestic", "international"}) {
            assertDoesNotThrow(
                    () ->
                            cloud(
                                            "tencent",
                                            market,
                                            true,
                                            "https://sms.tencentcloudapi.com",
                                            "Lumira",
                                            "ap-guangzhou",
                                            "fixture-id",
                                            "1400000000",
                                            "12345",
                                            "[\"{code}\",\"{minutes}\"]")
                                    .validate());
            assertDoesNotThrow(
                    () ->
                            cloud(
                                            "aliyun",
                                            market,
                                            true,
                                            "https://dysmsapi.aliyuncs.com",
                                            "Lumira",
                                            "cn-hangzhou",
                                            "fixture-id",
                                            "",
                                            "SMS_12345",
                                            "{\"code\":\"{code}\"}")
                                    .validate());
            assertDoesNotThrow(
                    () ->
                            cloud(
                                            "alibaba_intl",
                                            market,
                                            true,
                                            "https://dysmsapi.ap-southeast-1.aliyuncs.com",
                                            "Lumira",
                                            "ap-southeast-1",
                                            "fixture-id",
                                            "",
                                            "SMS_12345",
                                            "{\"code\":\"{code}\"}")
                                    .validate());
            assertDoesNotThrow(
                    () ->
                            cloud(
                                            "huawei",
                                            market,
                                            true,
                                            "https://smsapi.cn-north-4.myhuaweicloud.com/sms/batchSendSms/v1",
                                            "10690000",
                                            "",
                                            "fixture-id",
                                            "",
                                            "fixture-template",
                                            "[\"{code}\"]")
                                    .validate());
        }
        assertDoesNotThrow(
                () ->
                        cloud(
                                        "twilio",
                                        "international",
                                        true,
                                        "https://api.twilio.com",
                                        "+14155552671",
                                        "us1",
                                        "",
                                        "AC" + "a".repeat(32),
                                        "",
                                        "")
                                .validate());
        assertDoesNotThrow(
                () ->
                        cloud(
                                        "twilio",
                                        "international",
                                        true,
                                        "https://api.dublin.ie1.twilio.com",
                                        "MG" + "a".repeat(32),
                                        "ie1",
                                        "SK" + "b".repeat(32),
                                        "AC" + "a".repeat(32),
                                        "",
                                        "")
                                .validate());
    }

    @Test
    void draftsCanBeDisabledButCloudEndpointsAndParametersAreStrict() {
        assertDoesNotThrow(
                () ->
                        cloud(
                                        "tencent",
                                        "domestic",
                                        false,
                                        "https://sms.tencentcloudapi.com",
                                        "",
                                        "ap-guangzhou",
                                        "",
                                        "",
                                        "",
                                        "[\"{code}\"]")
                                .validate());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        cloud(
                                        "tencent",
                                        "domestic",
                                        true,
                                        "https://sms.tencentcloudapi.com",
                                        "",
                                        "ap-guangzhou",
                                        "",
                                        "",
                                        "",
                                        "[\"{code}\"]")
                                .validate());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        cloud(
                                        "tencent",
                                        "international",
                                        true,
                                        "https://sms.tencentcloudapi.com",
                                        "",
                                        "ap-singapore",
                                        "fixture-id",
                                        "1400000000",
                                        "12345",
                                        "[\"{code}\"]")
                                .validate());
        for (String parameters :
                new String[] {
                    "{\"code\":\"{code}\"}",
                    "[\"{phone}\"]",
                    "[123]",
                    "[\"{code}\\n\"]",
                    "[\"plain\"]"
                })
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            cloud(
                                            "tencent",
                                            "domestic",
                                            true,
                                            "https://sms.tencentcloudapi.com",
                                            "Lumira",
                                            "ap-guangzhou",
                                            "fixture-id",
                                            "1400000000",
                                            "12345",
                                            parameters)
                                    .validate());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        cloud(
                                        "aliyun",
                                        "domestic",
                                        true,
                                        "https://attacker.example.com",
                                        "Lumira",
                                        "cn-hangzhou",
                                        "fixture-id",
                                        "",
                                        "SMS_12345",
                                        "{\"code\":\"{code}\"}")
                                .validate());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        cloud(
                                        "huawei",
                                        "domestic",
                                        true,
                                        "https://smsapi.cn-north-4.myhuaweicloud.com.evil.example/sms/batchSendSms/v1",
                                        "1069",
                                        "",
                                        "fixture-id",
                                        "",
                                        "fixture-template",
                                        "[\"{code}\"]")
                                .validate());
    }

    @Test
    void legacyWebhookJsonKeepsItsContract() {
        var old =
                new ObjectMapper()
                        .readValue(
                                "{\"enabled\":false,\"provider\":\"webhook\",\"endpoint\":\"\",\"sender\":\"Lumira\",\"template\":\"Code {code}\",\"timeoutSeconds\":10}",
                                SmsSettings.class);
        assertDoesNotThrow(old::validate);
        assertEquals("domestic", old.market());
        assertTrue(
                old.sameCredentialDestination(
                        new SmsSettings(false, "webhook", "", "Lumira", "Code {code}", 10)));
    }

    @Test
    void credentialReuseComparesSmsEndpointUriComponents() {
        var original =
                cloud(
                        "tencent",
                        "domestic",
                        false,
                        "HTTPS://SMS.TENCENTCLOUDAPI.COM:443/v1/send",
                        "Lumira",
                        "ap-guangzhou",
                        "fixture-key",
                        "1400000000",
                        "12345",
                        "[\"{code}\"]");
        var equivalent =
                cloud(
                        "tencent",
                        "domestic",
                        false,
                        "https://sms.tencentcloudapi.com/v1/send",
                        "Lumira",
                        "ap-guangzhou",
                        "fixture-key",
                        "1400000000",
                        "12345",
                        "[\"{code}\"]");
        assertTrue(original.sameCredentialDestination(equivalent));

        var pathCaseChanged =
                cloud(
                        "tencent",
                        "domestic",
                        false,
                        "https://sms.tencentcloudapi.com/v1/Send",
                        "Lumira",
                        "ap-guangzhou",
                        "fixture-key",
                        "1400000000",
                        "12345",
                        "[\"{code}\"]");
        assertFalse(original.sameCredentialDestination(pathCaseChanged));

        var otherHost =
                cloud(
                        "tencent",
                        "domestic",
                        false,
                        "https://sms.example.com/v1/send",
                        "Lumira",
                        "ap-guangzhou",
                        "fixture-key",
                        "1400000000",
                        "12345",
                        "[\"{code}\"]");
        assertFalse(original.sameCredentialDestination(otherHost));
    }

    @Test
    void disabledDefaultsAndLoopbackWebhookAreValid() {
        assertDoesNotThrow(() -> SmsSettings.defaults().validate());
        assertDoesNotThrow(
                () ->
                        new SmsSettings(
                                        true,
                                        "webhook",
                                        "https://sms.example.test/v1/send",
                                        "Lumira",
                                        "Code {code}",
                                        10)
                                .validate());
        assertDoesNotThrow(
                () ->
                        new SmsSettings(
                                        true,
                                        "webhook",
                                        "http://127.0.0.1:18080/send",
                                        "Lumira",
                                        "Code {code}",
                                        10)
                                .validate());
    }

    @Test
    void rejectsUnsafeEndpointsAndTemplates() {
        for (String endpoint :
                new String[] {
                    "http://sms.example.test/send",
                    "https://user:pass@sms.example.test/send",
                    "https://sms.example.test/send?secret=x",
                    "sms.example.test"
                })
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            new SmsSettings(true, "webhook", endpoint, "Lumira", "Code {code}", 10)
                                    .validate());
        assertThrows(
                ResponseStatusException.class,
                () -> new SmsSettings(true, "webhook", "", "Lumira", "Code {code}", 10).validate());
        assertThrows(
                ResponseStatusException.class,
                () -> new SmsSettings(false, "webhook", "", "Lumira", "Code", 10).validate());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        new SmsSettings(
                                        true,
                                        "other",
                                        "https://sms.example.test",
                                        "Lumira",
                                        "Code {code}",
                                        10)
                                .validate());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        new SmsSettings(
                                        true,
                                        "webhook",
                                        "https://sms.example.test",
                                        "Lumira",
                                        "Code {phone}",
                                        10)
                                .validate());
    }

    @Test
    void rejectsInternalAndReservedWebhookDestinations() {
        for (String host :
                new String[] {
                    "10.0.0.5",
                    "169.254.169.254",
                    "192.168.1.1",
                    "172.16.0.9",
                    "100.64.0.1",
                    "[fd00::1]",
                    "[::ffff:10.0.0.1]",
                    "[64:ff9b::a00:1]",
                    "[2002:a00:1::]",
                    "db.internal",
                    "printer.local",
                    "sms.home",
                    "sms.lan",
                    "singlelabel"
                })
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            new SmsSettings(
                                            true,
                                            "webhook",
                                            "https://" + host + "/send",
                                            "Lumira",
                                            "Code {code}",
                                            10)
                                    .validate(),
                    host);
        assertDoesNotThrow(
                () ->
                        new SmsSettings(
                                        true,
                                        "webhook",
                                        "https://8.8.8.8/send",
                                        "Lumira",
                                        "Code {code}",
                                        10)
                                .validate());
    }

    @Test
    void credentialsAreExplicitAndWriteOnly() {
        for (String action : new String[] {"KEEP", "CLEAR"}) {
            assertDoesNotThrow(() -> new SmsSettingsService.Credentials(action, null).validate());
            assertThrows(
                    ResponseStatusException.class,
                    () -> new SmsSettingsService.Credentials(action, "******").validate());
        }
        var credentials = new SmsSettingsService.Credentials("REPLACE", "fixture-secret");
        credentials.validate();
        assertFalse(credentials.toString().contains("fixture-secret"));
        assertFalse(new ObjectMapper().writeValueAsString(credentials).contains("fixture-secret"));
        assertEquals(
                "fixture-secret",
                new ObjectMapper()
                        .readValue(
                                "{\"action\":\"REPLACE\",\"value\":\"fixture-secret\"}",
                                SmsSettingsService.Credentials.class)
                        .value());
    }
}
