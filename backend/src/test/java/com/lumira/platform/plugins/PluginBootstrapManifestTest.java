package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PluginBootstrapManifestTest {
    @TempDir Path directory;

    @Test
    void defaultsAreExplicitBundleConfigurationInsteadOfKernelBusinessNames() throws Exception {
        var source = directory.resolve("bootstrap.json");
        Files.writeString(source, "[\"custom-business\",\"another-plugin\"]");
        assertEquals(
                List.of("custom-business", "another-plugin"),
                PluginRuntime.bootstrapPluginIds(source, JsonMapper.builder().build()));
        Files.writeString(source, "[]");
        assertEquals(
                List.of(), PluginRuntime.bootstrapPluginIds(source, JsonMapper.builder().build()));
        for (String invalid : List.of("[null]", "[\"../outside\"]", "[\"same\",\"same\"]")) {
            Files.writeString(source, invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PluginRuntime.bootstrapPluginIds(source, JsonMapper.builder().build()));
        }
    }
}
