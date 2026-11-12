package com.lumira.platform.identity.api;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.util.UUID;

/** Published field options only; the calling host must first authorize its account or target. */
public interface ProfileDictionarySelectionPort {
    enum Host {
        ACCOUNT("account-profile"),
        USER_EDITOR("admin-user-editor");

        private final String page;

        Host(String page) {
            this.page = page;
        }

        public String page() {
            return page;
        }
    }

    PageResult<ProfileDictionaryPort.Item> list(Host host, String fieldKey, PageRequest page);

    ProfileDictionaryPort.Item find(Host host, String fieldKey, UUID itemId);
}
