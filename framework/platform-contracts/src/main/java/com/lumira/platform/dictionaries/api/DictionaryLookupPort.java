package com.lumira.platform.dictionaries.api;

import java.util.Collection;
import java.util.List;

/** 供模块读取配置选项的只读字典契约。 Read-only dictionary contract for modules that consume configured choices. */
public interface DictionaryLookupPort {
    /**
     * 查询指定字典编码的策略选项，不写入字典。 Reads policy options for dictionary codes without modifying
     * dictionaries.
     */
    List<PolicyItem> policyItems(Collection<String> codes);

    /** 判断指定字典编码是否启用。 Reports whether the dictionary code is enabled. */
    boolean isEnabled(String code);

    record PolicyItem(
            String dictionaryCode, String value, String label, String remark, int sortOrder) {}
}
