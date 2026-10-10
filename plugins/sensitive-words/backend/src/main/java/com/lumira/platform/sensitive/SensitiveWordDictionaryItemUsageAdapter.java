package com.lumira.platform.sensitive;

import com.lumira.platform.dictionaries.api.DictionaryItemUsagePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Prevents removing a category value while an active sensitive-word row references it. */
@Component
public class SensitiveWordDictionaryItemUsageAdapter implements DictionaryItemUsagePort {
    private final JdbcClient jdbc;

    public SensitiveWordDictionaryItemUsageAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isInUse(String dictionaryCode, String itemValue) {
        if (!SensitiveWordService.CATEGORY_DICTIONARY.equals(dictionaryCode)) return false;
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_sensitive_word WHERE category=:value AND deleted=false)")
                .param("value", itemValue)
                .query(Boolean.class)
                .single();
    }
}
