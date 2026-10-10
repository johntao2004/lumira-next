package com.lumira.platform.feedback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class FeedbackDictionaryItemUsageAdapterTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec statement =
            mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    private final JdbcClient.MappedQuerySpec<Boolean> result =
            mock(JdbcClient.MappedQuerySpec.class);
    private final FeedbackDictionaryItemUsageAdapter adapter =
            new FeedbackDictionaryItemUsageAdapter(jdbc);

    @BeforeEach
    void connectQuery() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(Boolean.class)).thenReturn(result);
        when(result.single()).thenReturn(true);
    }

    @Test
    void detectsFeedbackRowsReferencingCategoryValues() {
        assertTrue(adapter.isInUse(FeedbackCatalog.CATEGORY, "SUPPORT"));

        verify(jdbc)
                .sql(argThat(sql -> sql.contains("FROM platform_feedback WHERE category=:value")));
        verify(statement).param("value", "SUPPORT");
    }

    @Test
    void detectsFeedbackRowsReferencingPriorityValues() {
        assertTrue(adapter.isInUse(FeedbackCatalog.PRIORITY, "HIGH"));

        verify(jdbc)
                .sql(argThat(sql -> sql.contains("FROM platform_feedback WHERE priority=:value")));
        verify(statement).param("value", "HIGH");
    }

    @Test
    void detectsFeedbackRowsReferencingStatusValues() {
        assertTrue(adapter.isInUse(FeedbackCatalog.STATUS, "OPEN"));

        verify(jdbc)
                .sql(argThat(sql -> sql.contains("FROM platform_feedback WHERE status=:value")));
        verify(statement).param("value", "OPEN");
    }

    @Test
    void ignoresDictionariesOwnedByOtherModules() {
        assertFalse(adapter.isInUse("sensitive_word_category", "PAYMENT"));

        verifyNoInteractions(jdbc);
    }
}
