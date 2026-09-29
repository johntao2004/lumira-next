package com.lumira.platform.sensitive;

import com.lumira.platform.dictionaries.api.DictionaryItemUsagePort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SensitiveWordDictionaryItemUsageAdapterTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    private final JdbcClient.MappedQuerySpec<Boolean> result = mock(JdbcClient.MappedQuerySpec.class);
    private final DictionaryItemUsagePort adapter = new SensitiveWordDictionaryItemUsageAdapter(jdbc);

    @Test
    void checksOnlyActiveRowsWhenCheckingCategoryReferences() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(Boolean.class)).thenReturn(result);
        when(result.single()).thenReturn(true);

        assertTrue(adapter.isInUse(SensitiveWordService.CATEGORY_DICTIONARY, "legacy-category"));

        verify(jdbc).sql(argThat(sql -> sql.contains(
            "FROM platform_sensitive_word WHERE category=:value AND deleted=false")));
        verify(statement).param("value", "legacy-category");
    }

    @Test
    void ignoresUnrelatedDictionaries() {
        assertFalse(adapter.isInUse("work_order_feedback_category", "QUESTION"));
        verifyNoInteractions(jdbc);
    }
}
