package com.lumira.platform.dictionaries.api;

/**
 * 字典项停用或删除前，由使用方提供只读引用检查。 Read-only check supplied by dictionary consumers before an item is
 * disabled or removed.
 */
public interface DictionaryItemUsagePort {
    /** 判断字典项值是否仍被当前模块使用。 Reports whether this module still uses the dictionary item value. */
    boolean isInUse(String dictionaryCode, String itemValue);
}
