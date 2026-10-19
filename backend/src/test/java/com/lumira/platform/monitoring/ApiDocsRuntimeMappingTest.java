package com.lumira.platform.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.PluginAuthorizationPort;
import com.lumira.platform.modules.ModuleEndpoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Exercises real springdoc generation and runtime plugin mapping refresh without a database. */
@SpringBootTest(
        classes = ApiDocsRuntimeMappingTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.api-docs.path=/api/platform/api-docs",
            "springdoc.cache.disabled=true",
            "springdoc.paths-to-match=/api/**",
            "springdoc.paths-to-exclude=/api/platform/api-docs,/api/platform/api-docs/**,/api/platform/api-docs.yaml,/api/internal,/api/internal/**,/api/health,/api/health/**,/internal,/internal/**,/health,/health/**,/actuator/**",
            "management.endpoint.health.group.readiness.include=readinessState",
            "spring.flyway.enabled=false",
            "spring.session.jdbc.enabled=false"
        })
class ApiDocsRuntimeMappingTest {
    @Autowired WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @MockitoBean PluginAuthorizationPort pluginAuthorization;

    @MockitoBean com.lumira.platform.dictionaries.DictionaryService dictionaries;
    @MockitoBean com.lumira.platform.organizations.OrganizationService organizations;
    @MockitoBean com.lumira.platform.navigation.NavigationMenuService navigation;
    @MockitoBean com.lumira.platform.settings.SmtpSettingsService smtp;
    @MockitoBean com.lumira.platform.settings.WechatSettingsService wechat;
    @MockitoBean com.lumira.platform.common.AdministrationPort administration;
    @MockitoBean com.lumira.platform.users.UserXlsxExportService exports;
    @MockitoBean com.lumira.platform.directory.templates.TemplatePdfService printing;
    @MockitoBean com.lumira.platform.ai.AiConversationShareService shares;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void specContainsMethodsParametersRequestAndResponseDtosAndSecurityContracts()
            throws Exception {
        mvc.perform(get("/api/platform/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(
                        jsonPath("$.paths['/api/platform/docs-probe'].get.parameters[0].name")
                                .value("pageSize"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/platform/docs-probe'].post.requestBody.content['application/json'].schema.$ref")
                                .value("#/components/schemas/" + schemaName(ApiDocsRequest.class)))
                .andExpect(
                        jsonPath(schemaPath(ApiDocsRequest.class) + ".properties.label.type")
                                .value("string"))
                .andExpect(
                        jsonPath(schemaPath(ApiDocsResponse.class) + ".properties.id.type")
                                .value("integer"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.lumiraSession.in").value("cookie"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.csrfToken.name")
                                .value("X-CSRF-TOKEN"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/auth/password-recovery/request'].post.security[0].csrfToken")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/auth/password-recovery/request'].post.security[0].lumiraSession")
                                .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/methods'].get.security").isEmpty())
                .andExpect(
                        jsonPath("$.paths['/api/auth/registration/profile-schema'].get.security")
                                .isEmpty())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/auth/second-factor/enrollment/start'].post.security[0].csrfToken")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/auth/second-factor/enrollment/start'].post.security[0].lumiraSession")
                                .doesNotExist())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/auth/second-factor/enrollment/confirm'].post.security[0].csrfToken")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/auth/second-factor/enrollment/confirm'].post.security[0].lumiraSession")
                                .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/platform/api-docs']").doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/api/platform/api-docs/nested-probe']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/internal/probe']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/health/probe']").doesNotExist())
                .andExpect(jsonPath("$.paths['/internal/probe']").doesNotExist())
                .andExpect(jsonPath("$.paths['/health/probe']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/view-probe']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/plugins/unmapped-probe']").doesNotExist());
    }

    @Test
    void dynamicallyRegisteredPluginRouteAppearsAndDisappearsOnTheNextRead() throws Exception {
        var plugin = new DynamicPluginController();
        assertTrue(context.getBeansOfType(DynamicPluginController.class).isEmpty());
        mvc.perform(get("/api/platform/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/api/plugins/runtime-probe/{probeId}']").doesNotExist())
                .andExpect(jsonPath(schemaPath(PluginProbeResponse.class)).doesNotExist());

        when(pluginAuthorization.authorize(
                        "GET", "/api/plugins/runtime-probe/{probeId}", Set.of(), false))
                .thenReturn(true);
        var publicMethod = DynamicPluginController.class.getMethod("publicProbe", String.class);
        var publicMapping =
                RequestMappingInfo.paths("/api/plugins/runtime-probe/{probeId}")
                        .methods(RequestMethod.GET)
                        .options(mappings.getBuilderConfiguration())
                        .build();
        var protectedMethod = DynamicPluginController.class.getMethod("protectedProbe");
        var protectedMapping =
                RequestMappingInfo.paths("/api/plugins/private-probe")
                        .methods(RequestMethod.GET)
                        .options(mappings.getBuilderConfiguration())
                        .build();
        var inheritedMethod = DynamicPluginController.class.getMethod("inheritedProbe");
        var inheritedMapping =
                RequestMappingInfo.paths("/api/plugins/inherited-probe")
                        .methods(RequestMethod.GET)
                        .options(mappings.getBuilderConfiguration())
                        .build();

        mappings.registerMapping(publicMapping, plugin, publicMethod);
        mappings.registerMapping(protectedMapping, plugin, protectedMethod);
        mappings.registerMapping(inheritedMapping, plugin, inheritedMethod);
        try {
            mvc.perform(get("/api/platform/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.paths['/api/plugins/runtime-probe/{probeId}'].get")
                                    .exists())
                    .andExpect(
                            jsonPath("$.paths['/api/plugins/runtime-probe/{probeId}'].get.security")
                                    .isEmpty())
                    .andExpect(jsonPath("$.paths['/api/plugins/private-probe'].get").exists())
                    .andExpect(
                            jsonPath("$.paths['/api/plugins/private-probe'].get.security")
                                    .doesNotExist())
                    .andExpect(jsonPath("$.paths['/api/plugins/inherited-probe'].get").exists())
                    .andExpect(
                            jsonPath(
                                            schemaPath(PluginProbeResponse.class)
                                                    + ".properties.ready.type")
                                    .value("boolean"));
            verify(pluginAuthorization)
                    .authorize("GET", "/api/plugins/runtime-probe/{probeId}", Set.of(), false);
            verify(pluginAuthorization)
                    .authorize("GET", "/api/plugins/private-probe", Set.of(), false);
        } finally {
            mappings.unregisterMapping(publicMapping);
            mappings.unregisterMapping(protectedMapping);
            mappings.unregisterMapping(inheritedMapping);
        }

        mvc.perform(get("/api/platform/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/api/plugins/runtime-probe/{probeId}']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/plugins/private-probe']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/plugins/inherited-probe']").doesNotExist())
                .andExpect(jsonPath(schemaPath(PluginProbeResponse.class)).doesNotExist());
    }

    @Test
    void requestSchemasWithRepeatedSimpleNamesStayDistinctAndJsonNodesStayHidden()
            throws Exception {
        var document =
                new tools.jackson.databind.ObjectMapper()
                        .readTree(
                                mvc.perform(get("/api/platform/api-docs"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString());
        assertRequestSchema(
                document,
                "/api/platform/dictionaries",
                "post",
                com.lumira.platform.dictionaries.DictionaryService.Input.class,
                "code",
                "structure");
        assertRequestSchema(
                document,
                "/api/platform/organizations",
                "post",
                com.lumira.platform.organizations.OrganizationService.Input.class,
                "parentId",
                "sortNo");
        assertRequestSchema(
                document,
                "/api/platform/navigation/menus",
                "post",
                com.lumira.platform.navigation.NavigationMenuService.Input.class,
                "menuCode",
                "componentKey");
        assertRequestSchema(
                document,
                "/api/platform/settings/smtp",
                "put",
                com.lumira.platform.settings.SmtpSettingsService.Input.class,
                "data",
                "credentials");
        assertRequestSchema(
                document,
                "/api/platform/settings/wechat",
                "put",
                com.lumira.platform.settings.WechatSettingsService.Input.class,
                "appId",
                "appSecret");
        assertRequestSchema(
                document,
                "/api/platform/users/export.xlsx",
                "post",
                com.lumira.platform.users.UserXlsxExportService.Request.class,
                "scope",
                "fields",
                "filters");
        assertRequestSchema(
                document,
                "/api/platform/users/template-print",
                "post",
                com.lumira.platform.directory.templates.TemplatePdfService.Request.class,
                "templateId",
                "format",
                "merge");
        assertRequestSchema(
                document,
                "/api/platform/ai/shares",
                "post",
                com.lumira.platform.ai.AiConversationShareService.ShareWrite.class,
                "title",
                "messages");
        assertRequestSchema(
                document,
                "/api/platform/ai/shares/{id}",
                "put",
                com.lumira.platform.ai.AiConversationShareService.ShareWrite.class,
                "title",
                "messages");
        String shareSchema =
                document.path("components")
                        .path("schemas")
                        .path(
                                schemaName(
                                        com.lumira.platform.ai.AiConversationShareService.ShareWrite
                                                .class))
                        .toString();
        assertFalse(shareSchema.contains("JsonNode"));
        assertFalse(shareSchema.contains("nodeType"));
    }

    private static void assertRequestSchema(
            tools.jackson.databind.JsonNode document,
            String path,
            String method,
            Class<?> requestType,
            String... properties) {
        String name = schemaName(requestType);
        assertEquals(
                "#/components/schemas/" + name,
                document.path("paths")
                        .path(path)
                        .path(method)
                        .path("requestBody")
                        .path("content")
                        .path("application/json")
                        .path("schema")
                        .path("$ref")
                        .asText(),
                path);
        var schema = document.path("components").path("schemas").path(name).path("properties");
        for (String property : properties)
            assertTrue(schema.has(property), path + " missing " + property);
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                SessionAutoConfiguration.class
            })
    @Import({
        ApiDocsConfiguration.class,
        SampleController.class,
        ViewController.class,
        com.lumira.platform.dictionaries.DictionaryController.class,
        com.lumira.platform.organizations.OrganizationController.class,
        com.lumira.platform.navigation.NavigationController.class,
        com.lumira.platform.settings.SmtpSettingsController.class,
        com.lumira.platform.settings.WechatSettingsController.class,
        com.lumira.platform.users.UserExportController.class,
        com.lumira.platform.users.UserTemplatePrintController.class,
        com.lumira.platform.ai.AiConversationShareController.class
    })
    static class TestApplication {}

    @RestController
    static class SampleController {
        @GetMapping("/api/platform/docs-probe")
        ApiDocsResponse get(@RequestParam(defaultValue = "20") int pageSize) {
            return new ApiDocsResponse(pageSize, true);
        }

        @PostMapping("/api/platform/docs-probe")
        ApiDocsResponse create(@RequestBody ApiDocsRequest request) {
            return new ApiDocsResponse(request.label().length(), true);
        }

        @PostMapping("/api/auth/password-recovery/request")
        void recover(@RequestBody ApiDocsRequest request) {}

        @GetMapping("/api/auth/csrf")
        CsrfResponse csrf() {
            return new CsrfResponse("X-CSRF-TOKEN", "");
        }

        @GetMapping({
            "/api/internal/probe",
            "/api/health/probe",
            "/api/platform/api-docs/nested-probe",
            "/internal/probe",
            "/health/probe"
        })
        ApiDocsResponse internal() {
            return new ApiDocsResponse(0, false);
        }

        @GetMapping("/api/auth/methods")
        ApiDocsResponse authenticationMethods() {
            return new ApiDocsResponse(1, true);
        }

        @GetMapping("/api/auth/registration/profile-schema")
        ApiDocsResponse registrationProfileSchema() {
            return new ApiDocsResponse(1, true);
        }

        @PostMapping("/api/auth/second-factor/enrollment/start")
        ApiDocsResponse startEnrollment(@RequestBody ApiDocsRequest request) {
            return new ApiDocsResponse(request.label().length(), true);
        }

        @PostMapping("/api/auth/second-factor/enrollment/confirm")
        ApiDocsResponse confirmEnrollment(@RequestBody ApiDocsRequest request) {
            return new ApiDocsResponse(request.label().length(), true);
        }
    }

    @Controller
    static class ViewController {
        @GetMapping("/api/view-probe")
        String view() {
            return "view";
        }
    }

    @RestController
    @RequestMapping("/api/plugins")
    @ModuleEndpoint("probe")
    static class DynamicPluginController extends PluginControllerBase {
        @Schema(description = "Dynamically registered plugin endpoint response.")
        @GetMapping("/runtime-probe/{probeId}")
        public PluginProbeResponse publicProbe(@PathVariable String probeId) {
            return new PluginProbeResponse(true);
        }

        @GetMapping("/private-probe")
        public PluginProbeResponse protectedProbe() {
            return new PluginProbeResponse(false);
        }

        @GetMapping("/unmapped-probe")
        public PluginProbeResponse unmappedProbe() {
            return new PluginProbeResponse(false);
        }
    }

    static class PluginControllerBase {
        @GetMapping("/inherited-probe")
        public PluginProbeResponse inheritedProbe() {
            return new PluginProbeResponse(true);
        }
    }

    private static String schemaName(Class<?> type) {
        return type.getName().replace('$', '.');
    }

    private static String schemaPath(Class<?> type) {
        return "$.components.schemas['" + schemaName(type) + "']";
    }

    record ApiDocsRequest(String label) {}

    record ApiDocsResponse(int id, boolean active) {}

    record CsrfResponse(String headerName, String token) {}

    record PluginProbeResponse(boolean ready) {}
}
