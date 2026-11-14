package com.lumira.example.notes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 独立应用的最小启动装配；不扫描或依赖宿主实现。 Minimal standalone composition; no host implementation scanning or
 * dependency.
 */
@SpringBootApplication
public class NotesApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotesApplication.class, args);
    }
}
