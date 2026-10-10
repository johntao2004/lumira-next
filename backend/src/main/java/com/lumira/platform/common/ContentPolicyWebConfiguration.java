package com.lumira.platform.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class ContentPolicyWebConfiguration implements WebMvcConfigurer {
    private final ContentPolicyParameterInterceptor interceptor;

    public ContentPolicyWebConfiguration(ContentPolicyParameterInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
