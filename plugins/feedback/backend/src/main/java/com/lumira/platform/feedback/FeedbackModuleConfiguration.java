package com.lumira.platform.feedback;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Descriptor for the optional feedback module. */
@Configuration(proxyBeanMethods = false)
public class FeedbackModuleConfiguration {
    @Bean
    PlatformModule feedbackModule() {
        return new PlatformModule(
                "feedback",
                "工单反馈",
                "1.0.6",
                List.of("platform", "files", "dictionaries", "notifications"));
    }
}
