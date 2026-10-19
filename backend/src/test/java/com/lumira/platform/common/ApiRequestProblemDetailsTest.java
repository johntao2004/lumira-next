package com.lumira.platform.common;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.dictionaries.DictionaryController;
import com.lumira.platform.dictionaries.DictionaryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiRequestProblemDetailsTest {
    private final DictionaryService service = mock(DictionaryService.class);
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new DictionaryController(service))
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build();

    @Test
    void missingRequiredVersionUsesBadRequestProblemDetails() throws Exception {
        mvc.perform(delete("/api/platform/dictionaries/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("invalidRequest"));
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedRequestMediaUsesUnsupportedMediaProblemDetails() throws Exception {
        mvc.perform(
                        post("/api/platform/dictionaries")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("{\"code\":\"example\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.detail").value("invalidRequest"));
        verifyNoInteractions(service);
    }

    @Test
    void malformedBodyAndParameterKeepProblemDetailsContract() throws Exception {
        mvc.perform(
                        post("/api/platform/dictionaries")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("invalidRequest"));
        mvc.perform(
                        delete("/api/platform/dictionaries/00000000-0000-0000-0000-000000000001")
                                .param("version", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("invalidRequest"));
        verifyNoInteractions(service);
    }
}
