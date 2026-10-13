package com.lumira.platform.dictionaries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class DictionaryLookupEnabledPageTest {
    @Test
    void profileFieldDictionaryPageReturnsOnlyEnabledMetadataAndHonorsPaging() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        @SuppressWarnings("unchecked")
        var dictionaries =
                (JdbcClient.MappedQuerySpec<ProfileDictionaryPort.EnabledDictionary>)
                        mock(JdbcClient.MappedQuerySpec.class);
        @SuppressWarnings("unchecked")
        var count = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(ProfileDictionaryPort.EnabledDictionary.class))
                .thenReturn(dictionaries);
        when(statement.query(Long.class)).thenReturn(count);
        when(dictionaries.list())
                .thenReturn(
                        List.of(
                                new ProfileDictionaryPort.EnabledDictionary(
                                        "region", "Region", true)));
        when(count.single()).thenReturn(7L);
        var repository =
                new DictionaryLookupRepository(jdbc, new tools.jackson.databind.ObjectMapper());

        var page = repository.listEnabled(new PageRequest(2, 3, "reg"));

        assertEquals(
                new com.lumira.platform.common.PageResult<>(
                        List.of(
                                new ProfileDictionaryPort.EnabledDictionary(
                                        "region", "Region", true)),
                        7,
                        2,
                        3),
                page);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).sql(sql.capture());
        assertTrue(sql.getAllValues().getFirst().contains("SELECT code,name,enabled"));
        assertTrue(sql.getAllValues().getFirst().contains("WHERE enabled"));
        assertTrue(sql.getAllValues().getFirst().contains("LIMIT :limit OFFSET :offset"));
        assertFalse(sql.getAllValues().getFirst().contains("remark"));
        assertTrue(sql.getAllValues().getLast().contains("SELECT count(*)"));
        assertTrue(sql.getAllValues().getLast().contains("WHERE enabled"));
        verify(statement, times(2)).param("search", "reg");
        verify(statement).param("limit", 3);
        verify(statement).param("offset", 3L);
    }
}
