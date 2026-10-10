package com.lumira.platform.identity;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.PluginExtensionsPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountLocaleControllerTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PluginExtensionsPort> plugins = mock(ObjectProvider.class);

    private final AccountLocaleService service =
            new AccountLocaleService(new AccountLocaleRepository(mock(JdbcClient.class)), plugins);
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new AccountLocaleController(service)).build();

    @Test
    void nullArrayAndMalformedJsonBodiesAreRejectedAsBadRequests() throws Exception {
        for (String body : List.of("null", "[]", "not-json")) {
            mvc.perform(
                            put("/api/account/locale")
                                    .principal(
                                            UsernamePasswordAuthenticationToken.authenticated(
                                                    "alice", "fixture", List.of()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
