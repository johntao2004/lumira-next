package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OnlineSessionControllerTest {
    @Test
    void forwardsColumnFiltersFromHttpQuery() throws Exception {
        var service = mock(OnlineSessionService.class);
        var actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("admin");
        when(service.list(eq("admin"), any())).thenReturn(new PageResult<>(List.of(), 0, 2, 10));
        var mvc = MockMvcBuilders.standaloneSetup(new OnlineSessionController(service)).build();
        mvc.perform(
                        get("/api/platform/online-users")
                                .principal(actor)
                                .param("page", "2")
                                .param("pageSize", "10")
                                .param("filter.username", "alice")
                                .param("filterMode.username", "contains"))
                .andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(PageRequest.class);
        verify(service).list(eq("admin"), request.capture());
        assertEquals(2, request.getValue().page());
        assertEquals(
                PageRequest.fromQueryParameters(
                        2,
                        10,
                        "",
                        java.util.Map.of(
                                "filter.username", "alice", "filterMode.username", "contains")),
                request.getValue());
    }
}
