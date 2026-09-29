package com.lumira.platform.files;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor owned by the file and media module. */
@Configuration(proxyBeanMethods = false)
public class FilesModuleConfiguration {
    @Bean
    PlatformModule filesModule() {
        return new PlatformModule("files", "文件与媒体", "1.0.0", List.of("identity", "platform", "settings"), true);
    }
}
