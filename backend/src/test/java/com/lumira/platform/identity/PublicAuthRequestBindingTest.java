package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class PublicAuthRequestBindingTest {
    @Test
    void publicChallengeHttpBindingRejectsUnknownPrivilegeFields() throws Exception {
        var service = mock(PublicRegistrationService.class);
        when(service.requestChallenge(any(), any()))
                .thenAnswer(
                        invocation -> {
                            PublicAuthRequests.Challenge input = invocation.getArgument(0);
                            if (input.hasUnknownFields())
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "invalidRegistrationChallenge");
                            return new PublicRegistrationService.SendResult(
                                    new PublicRegistrationService.ChallengeView(
                                            UUID.randomUUID().toString(),
                                            OffsetDateTime.now().plusMinutes(5),
                                            OffsetDateTime.now().plusSeconds(30)),
                                    0);
                        });
        var mvc =
                MockMvcBuilders.standaloneSetup(new PublicRegistrationController(service)).build();

        mvc.perform(
                        post("/api/auth/registration/challenges")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"channel":"EMAIL","contact":"person@example.test","locale":"en",
                                 "roleIds":["admin"],"authorities":["platform:roles:write"],
                                 "organizationIds":["root"],"quotaOverrides":{"storage":999999}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registrationPasswordIsWriteOnlyWithTheProductionJacksonMapper() {
        var input = new PublicAuthRequests.RegistrationCompletion();
        input.username = "person";
        input.password = "private-fixture-password";

        String json = new ObjectMapper().writeValueAsString(input);
        assertFalse(json.contains("private-fixture-password"));
        assertFalse(json.contains("\"password\""));
        assertTrue(input.toString().contains("redacted"));
    }
}
