package com.lumira.platform.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.settings.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class SmtpConnectionSourceTest {
    private final SettingStore settings = mock(SettingStore.class);
    private final SecretStore secrets = mock(SecretStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private final SmtpConfigurationReader configuration =
            new SmtpConfigurationReader(
                    settings, secrets, mock(com.lumira.platform.common.SecretKeys.class), mapper);
    private final SmtpConnectionSource source = new SmtpConnectionSource(configuration);

    private void configure(boolean enabled, boolean auth) {
        var data =
                new SmtpSettings(
                        enabled,
                        "smtp.example.test",
                        587,
                        "STARTTLS",
                        auth,
                        "sender",
                        "sender@example.test",
                        "");
        when(settings.find("smtp"))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Snapshot(id, 1, mapper.writeValueAsString(data))));
    }

    @Test
    void absentOrDisabledConfigurationDoesNotReadCredentials() {
        when(settings.find("smtp")).thenReturn(Optional.empty());
        assertEquals(
                "smtpUnavailable",
                assertThrows(ResponseStatusException.class, source::load).getReason());
        configure(false, true);
        assertEquals(
                "smtpUnavailable",
                assertThrows(ResponseStatusException.class, source::load).getReason());
        verifyNoInteractions(secrets);
    }

    @Test
    void unauthenticatedConfigurationNeverDecryptsAnUnusedPassword() {
        configure(true, false);
        try (var connection = source.load()) {
            assertNull(connection.password());
        }
        verifyNoInteractions(secrets);
    }

    @Test
    void authenticatedConnectionOwnsAndClearsItsCredentialBytes() {
        configure(true, true);
        byte[] bytes = {1, 2, 3};
        when(secrets.reveal("settings:" + id + ":smtp-password")).thenReturn(Optional.of(bytes));
        var connection = source.load();
        assertSame(bytes, connection.password());
        assertEquals("SmtpConnection[redacted]", connection.toString());
        connection.close();
        assertArrayEquals(new byte[3], bytes);
        assertThrows(IllegalStateException.class, connection::password);
        connection.close();
    }

    @Test
    void missingOrUnreadableCredentialsFailWithoutReturningCryptographicDetails() {
        configure(true, true);
        String context = "settings:" + id + ":smtp-password";
        when(secrets.reveal(context)).thenReturn(Optional.empty());
        assertEquals(
                "smtpPasswordRequired",
                assertThrows(ResponseStatusException.class, source::load).getReason());
        when(secrets.reveal(context))
                .thenThrow(new IllegalStateException("sensitive crypto failure"));
        var error = assertThrows(ResponseStatusException.class, source::load);
        assertEquals("secretKeyUnavailable", error.getReason());
        assertNull(error.getCause());
    }
}
