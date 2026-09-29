package com.lumira.platform.localization;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalizationRepositoryRestoreTest {
    @Test
    void removesOnlySelectedLocaleUnderOptimisticActiveRowFence() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        var repository = new LocalizationRepository(jdbc, mock(ObjectMapper.class));
        var id = UUID.randomUUID();

        assertEquals(1, repository.removeTranslation(id, 7, "zh-CN", "admin"));

        verify(jdbc).sql(argThat(sql -> sql.contains("translations=translations - :locale")
            && sql.contains("jsonb_exists(translations,:locale)")
            && sql.contains("id=:id AND version=:version AND deleted=false")
            && !sql.contains("deleted=true")
            && !sql.contains("namespace=")
            && !sql.contains("default_message=")
            && !sql.contains("enabled=")));
        verify(statement).param("id", id);
        verify(statement).param("version", 7L);
        verify(statement).param("locale", "zh-CN");
        verify(statement).param("actor", "admin");
    }
}
