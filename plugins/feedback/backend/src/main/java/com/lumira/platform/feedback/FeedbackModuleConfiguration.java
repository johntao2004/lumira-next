package com.lumira.platform.feedback;

import com.lumira.platform.modules.PlatformModule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Descriptor for the optional feedback module. */
@Configuration(proxyBeanMethods = false)
public class FeedbackModuleConfiguration {
    @Bean
    PlatformModule feedbackModule() {
        return new PlatformModule(
                "feedback",
                "工单反馈",
                "1.0.14",
                List.of("platform", "identity", "files", "dictionaries", "notifications"));
    }
}
