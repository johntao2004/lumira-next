package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lumira.platform.ai.AiProviderService;
import com.lumira.platform.identity.ProfileValuesService;
import com.lumira.platform.settings.WatermarkSettings;
import com.lumira.plugins.authenticationconsole.SecuritySettingsController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RequestJsonBindingTest {
    private final JsonMapper sharedMapper = JsonMapper.builderWithJackson2Defaults().build();
    private final BoundaryController controller = new BoundaryController();
    private MockMvc mvc;
    private List<HttpMessageConverter<?>> converters;

    @BeforeEach
    void configureHttpBoundary() {
        converters = new ArrayList<>(List.of(new JacksonJsonHttpMessageConverter(sharedMapper)));
        new RequestJsonConfiguration().extendMessageConverters(converters);
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                        .setControllerAdvice(
                                new RequestTextAdvice(sharedMapper), new ApiExceptionHandler())
                        .build();
    }

    @Test
    void rejectsFractionalOrStringVersionsAndNumericOverflowBeforeBusinessWrites()
            throws Exception {
        for (String version : List.of("1.5", "1.0", "\"1\"", "\"\"", "9223372036854775808")) {
            reject("/binding/security", "{\"version\":" + version + "}");
        }
        reject("/binding/security", "{\"version\":1,\"data\":{\"passwordMinLength\":8.5}}");
        reject("/binding/security", "{\"version\":1,\"data\":{\"passwordMinLength\":2147483648}}");
        assertEquals(0, controller.writes.get());
    }

    @Test
    void rejectsCoercedBooleansTextAndEnumOrdinals() throws Exception {
        for (String enabled : List.of("1", "0", "\"true\"", "\"false\"", "\"\"")) {
            reject("/binding/provider", "{\"enabled\":" + enabled + "}");
        }
        for (String name : List.of("123", "1.5", "true")) {
            reject("/binding/provider", "{\"name\":" + name + "}");
        }
        for (String kind : List.of("0", "\"0\"", "\"UNKNOWN\"")) {
            reject("/binding/provider", "{\"providerKind\":" + kind + "}");
        }
        assertEquals(0, controller.writes.get());
    }

    @Test
    void rejectsDuplicatePropertiesIncludingNestedObjects() throws Exception {
        reject("/binding/security", "{\"version\":1,\"version\":2}");
        reject("/binding/profile", "{\"values\":{\"code\":\"one\",\"code\":\"two\"}}");
        assertEquals(0, controller.writes.get());
    }

    @Test
    void rejectsTrailingJsonValuesBeforeBusinessWrites() throws Exception {
        for (String trailing : List.of("{\"version\":2}", "null", "[]", "true", "123")) {
            reject("/binding/security", "{\"version\":1} " + trailing);
        }
        assertEquals(0, controller.writes.get());
    }

    @Test
    void rejectsNulInNestedListsMapValuesAndMapKeysBeforeBusinessWrites() throws Exception {
        reject("/binding/profile", "{\"values\":{\"code\":\"a\\u0000b\"}}");
        reject("/binding/profile", "{\"values\":{\"a\\u0000b\":\"value\"}}");
        reject("/binding/tree", "{\"items\":[{\"text\":\"a\\u0000b\"}]}");
        assertEquals(0, controller.writes.get());
    }

    @Test
    void preservesNativeTypesOptionalFieldsDecimalSettingsAndTextFormatting() throws Exception {
        mvc.perform(
                        post("/binding/security")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":1}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/binding/provider")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"00123\",\"providerKind\":\"OPENAI\",\"enabled\":true,\"credential\":{\"action\":\"KEEP\",\"value\":\"private\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("name").value("00123"))
                .andExpect(jsonPath("credential.value").doesNotExist());
        mvc.perform(
                        post("/binding/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"version\":1,\"schemaVersion\":1,\"values\":{\"code\":\"00123\",\"note\":\"a\\n"
                                                + "\\tb\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("values.code").value("00123"))
                .andExpect(jsonPath("values.note").value("a\n\tb"));
        mvc.perform(
                        post("/binding/watermark")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"opacity\":0.15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("opacity").value(0.15));
        assertEquals(4, controller.writes.get());
    }

    @Test
    void bindsOrderedModelNamesAndKeepsLegacySingleModelRequestsCompatible() throws Exception {
        mvc.perform(
                        post("/binding/provider")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"Provider\",\"providerKind\":\"OPENAI\",\"modelName\":\"gpt-default\",\"modelNames\":[\"gpt-default\",\"gpt-fallback\"],\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("modelName").value("gpt-default"))
                .andExpect(jsonPath("modelNames[0]").value("gpt-default"))
                .andExpect(jsonPath("modelNames[1]").value("gpt-fallback"));
        mvc.perform(
                        post("/binding/provider")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"Legacy provider\",\"providerKind\":\"OPENAI\",\"modelName\":\"legacy-model\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("modelName").value("legacy-model"));
        assertEquals(2, controller.writes.get());
    }

    @Test
    void preservesSharedMapperCompatibilityAndOriginalResponseConverter() {
        assertEquals(
                Map.of("value", "123"),
                sharedMapper.readValue(
                        "{\"value\":123}",
                        sharedMapper
                                .getTypeFactory()
                                .constructMapType(Map.class, String.class, String.class)));
        assertEquals(1, sharedMapper.readValue("1.5", Integer.class));
        var requestConverter = converters.getFirst();
        assertFalse(requestConverter.canWrite(Map.class, MediaType.APPLICATION_JSON));
        assertFalse(
                ((JacksonJsonHttpMessageConverter) requestConverter)
                        .canWrite(
                                ResolvableType.forClass(Map.class),
                                Map.class,
                                MediaType.APPLICATION_JSON));
        assertSame(sharedMapper, ((JacksonJsonHttpMessageConverter) converters.get(1)).getMapper());
    }

    private void reject(String path, String json) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value("invalidRequest"));
    }

    @RestController
    static class BoundaryController {
        final AtomicInteger writes = new AtomicInteger();

        @PostMapping("/binding/security")
        SecuritySettingsController.Input security(
                @RequestBody SecuritySettingsController.Input input) {
            writes.incrementAndGet();
            return input;
        }

        @PostMapping("/binding/provider")
        AiProviderService.ProviderWrite provider(
                @RequestBody AiProviderService.ProviderWrite input) {
            writes.incrementAndGet();
            return input;
        }

        @PostMapping("/binding/profile")
        ProfileValuesService.Input profile(@RequestBody ProfileValuesService.Input input) {
            writes.incrementAndGet();
            return input;
        }

        @PostMapping("/binding/watermark")
        WatermarkSettings watermark(@RequestBody WatermarkSettings input) {
            writes.incrementAndGet();
            return input;
        }

        @PostMapping("/binding/tree")
        JsonNode tree(@RequestBody JsonNode input) {
            writes.incrementAndGet();
            return input;
        }
    }
}
