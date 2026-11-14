package com.lumira.platform.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.ApiExceptionHandler;
import com.lumira.platform.common.AuditPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class AiConversationShareRequestValidationTest {
    @Test
    void documentationSchemaDoesNotDiscardUnknownFieldsOrCoerceJsonTypes() throws Exception {
        var jdbc = mock(JdbcClient.class);
        var audit = mock(AuditPort.class);
        var service =
                new AiConversationShareService(
                        jdbc, new ObjectMapper(), mock(AdministrationPort.class), audit);
        var mvc =
                MockMvcBuilders.standaloneSetup(new AiConversationShareController(service))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        var actor = UsernamePasswordAuthenticationToken.authenticated("actor", null, List.of());
        for (String body :
                List.of(
                        "{\"title\":\"Support\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"extra\":true}",
                        "{\"title\":123,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                        "{\"title\":\"Support\",\"messages\":[{\"role\":\"user\",\"content\":123}]}",
                        "{\"title\":\"Support\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\",\"extra\":true}]}")) {
            mvc.perform(
                            post("/api/platform/ai/shares")
                                    .principal(actor)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("invalidAiShare"));
            mvc.perform(
                            put("/api/platform/ai/shares/00000000-0000-0000-0000-000000000001")
                                    .principal(actor)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("invalidAiShare"));
        }
        verifyNoInteractions(jdbc, audit);
    }
}
