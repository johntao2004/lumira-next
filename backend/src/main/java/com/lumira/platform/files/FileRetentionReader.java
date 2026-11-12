package com.lumira.platform.files;

import com.lumira.platform.common.FileRetentionSettings;
import com.lumira.platform.settings.SettingStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Storage cleanup keeps enforcing the persisted policy when its management plugin is absent. */
@Service
public class FileRetentionReader {
    private final SettingStore settings;
    private final ObjectMapper mapper;

    public FileRetentionReader(SettingStore settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public FileRetentionSettings current() {
        return settings.find("file-retention")
                .map(s -> mapper.readValue(s.payload(), FileRetentionSettings.class))
                .orElseGet(FileRetentionSettings::defaults);
    }
}
