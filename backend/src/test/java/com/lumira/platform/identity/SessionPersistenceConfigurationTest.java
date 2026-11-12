package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

class SessionPersistenceConfigurationTest {
    @Test
    void defaultKeepsImmediateTouchesAndUnconditionalExplicitRevocation() {
        var repository = mock(JdbcIndexedSessionRepository.class);
        new SessionPersistenceConfiguration().sessionTouchCustomizer(0).customize(repository);
        verify(repository).setDeleteSessionQuery("DELETE FROM %TABLE_NAME% WHERE SESSION_ID = ?");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void windowIsBoundedAndEnabledOnlyExplicitly() {
        var configuration = new SessionPersistenceConfiguration();
        assertThrows(
                IllegalArgumentException.class, () -> configuration.sessionTouchCustomizer(-1));
        assertThrows(
                IllegalArgumentException.class, () -> configuration.sessionTouchCustomizer(1001));
        var repository = mock(JdbcIndexedSessionRepository.class);
        configuration.sessionTouchCustomizer(1000).customize(repository);
        verify(repository).setUpdateSessionQuery(SessionPersistenceConfiguration.updateQuery(1000));
    }
}
