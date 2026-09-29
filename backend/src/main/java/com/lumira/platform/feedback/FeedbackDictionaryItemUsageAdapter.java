package com.lumira.platform.feedback;

import com.lumira.platform.dictionaries.api.DictionaryItemUsagePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reports whether persisted feedback rows still depend on a dictionary value. */
@Component
public class FeedbackDictionaryItemUsageAdapter implements DictionaryItemUsagePort {
    private final JdbcClient jdbc;

    public FeedbackDictionaryItemUsageAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isInUse(String dictionaryCode, String itemValue) {
        String column = switch (dictionaryCode) {
            case FeedbackCatalog.STATUS -> "status";
            case FeedbackCatalog.PRIORITY -> "priority";
            case FeedbackCatalog.CATEGORY -> "category";
            default -> null;
        };
        if (column == null) return false;

        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform_feedback WHERE " + column + "=:value)")
            .param("value", itemValue)
            .query(Boolean.class)
            .single();
    }
}
