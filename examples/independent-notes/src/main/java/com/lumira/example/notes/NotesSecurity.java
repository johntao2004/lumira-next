package com.lumira.example.notes;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 示例使用独立身份源；缺少显式凭据即拒绝启动，不继承宿主权限。 The example has its own identities; missing explicit credentials
 * fail startup and host grants are never inherited.
 */
@Configuration(proxyBeanMethods = false)
class NotesSecurity {
    @Bean
    InMemoryUserDetailsManager users(Environment env) {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String reader = env.getRequiredProperty("NOTES_READER_PASSWORD");
        String editor = env.getRequiredProperty("NOTES_EDITOR_PASSWORD");
        if (reader.length() < 16 || editor.length() < 16 || reader.equals(editor))
            throw new IllegalStateException(
                    "Distinct example passwords of at least 16 characters are required");
        return new InMemoryUserDetailsManager(
                User.withUsername("reader")
                        .password(encoder.encode(reader))
                        .authorities("notes:read")
                        .build(),
                User.withUsername("editor")
                        .password(encoder.encode(editor))
                        .authorities("notes:read", "notes:write")
                        .build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        // 保留 CSRF 防护，HTTP Basic 也会被浏览器自动附带。 Keep CSRF protection because browsers can attach Basic
        // credentials automatically.
        return http.authorizeHttpRequests(r -> r.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
