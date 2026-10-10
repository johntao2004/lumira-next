package com.lumira.platform.files;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class FileConfiguration {
    @Bean
    FileQuota fileQuota(
            @Value("${lumira.files.default-bytes:${lumira.files.maximum-bytes:1073741824}}")
                    long bytes,
            @Value("${lumira.files.default-files:${lumira.files.maximum-files:1000}}") long files) {
        return new FileQuota(bytes, files);
    }

    @Bean
    LocalBlobStore legacyLocalBlobStore(
            @Value("${lumira.files.local-root:.runtime/files}") String root) throws IOException {
        return new LocalBlobStore(Path.of(root));
    }

    @Bean
    PostgresBlobStore postgresBlobStore(
            JdbcTemplate jdbc, FileRepository files, PlatformTransactionManager manager) {
        return new PostgresBlobStore(jdbc, files, manager);
    }

    @Bean
    @Primary
    BlobStore blobStore(FileRepository files, LocalBlobStore local, PostgresBlobStore postgres) {
        return new RoutedBlobStore(files, local, postgres);
    }
}
