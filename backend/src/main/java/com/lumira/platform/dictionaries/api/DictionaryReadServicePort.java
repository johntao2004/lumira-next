package com.lumira.platform.dictionaries.api;

import com.lumira.platform.common.PluginServicePort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;

/** Shared provider contract for business policies and dictionary-backed identity fields. */
public interface DictionaryReadServicePort
        extends PluginServicePort, DictionaryLookupPort, ProfileDictionaryPort {
    @Override
    default String ownerPluginId() {
        return "dictionaries";
    }

    @Override
    default String serviceType() {
        return "dictionary-read";
    }

    @Override
    boolean isEnabled(String code);
}
