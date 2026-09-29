package com.lumira.platform.dictionaries.api;

/** Read-only check supplied by dictionary consumers before an item is disabled or removed. */
public interface DictionaryItemUsagePort {
    boolean isInUse(String dictionaryCode, String itemValue);
}
