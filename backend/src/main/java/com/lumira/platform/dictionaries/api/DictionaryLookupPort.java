package com.lumira.platform.dictionaries.api;

import java.util.Collection;
import java.util.List;

/** Read-only dictionary contract for modules that consume configured choices. */
public interface DictionaryLookupPort {
    List<PolicyItem> policyItems(Collection<String> codes);

    boolean isEnabled(String code);

    record PolicyItem(String dictionaryCode, String value, String label, String remark, int sortOrder) {}
}
