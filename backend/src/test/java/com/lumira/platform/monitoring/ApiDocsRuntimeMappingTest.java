package com.lumira.platform.monitoring;

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
                                .value("#/components/schemas/ApiDocsRequest"))
                .andExpect(
                        jsonPath("$.components.schemas.ApiDocsRequest.properties.label.type")
                                .value("string"))
                .andExpect(
                        jsonPath("$.components.schemas.ApiDocsResponse.properties.id.type")
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
                .andExpect(jsonPath("$.components.schemas.PluginProbeResponse").doesNotExist());

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
                                            "$.components.schemas.PluginProbeResponse.properties.ready.type")
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
                .andExpect(jsonPath("$.components.schemas.PluginProbeResponse").doesNotExist());
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                SessionAutoConfiguration.class
            })
    @Import({ApiDocsConfiguration.class, SampleController.class, ViewController.class})
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

    record ApiDocsRequest(String label) {}

    record ApiDocsResponse(int id, boolean active) {}

    record CsrfResponse(String headerName, String token) {}

    record PluginProbeResponse(boolean ready) {}
}
