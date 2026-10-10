package com.lumira.platform.common;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class UploadTransportConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadReadTimeout(
            @Value("${lumira.http.upload-idle-timeout-ms:30000}") int milliseconds) {
        if (milliseconds < 1000 || milliseconds > 120000)
            throw new IllegalArgumentException(
                    "Upload idle timeout must be between 1000 and 120000 milliseconds");
        return factory ->
                factory.addConnectorCustomizers(
                        connector -> {
                            if (!(connector.getProtocolHandler()
                                    instanceof AbstractHttp11Protocol<?> protocol))
                                throw new IllegalStateException(
                                        "Upload timeout requires the HTTP connector");
                            configure(protocol, milliseconds);
                        });
    }

    static void configure(AbstractHttp11Protocol<?> protocol, int milliseconds) {
        protocol.setDisableUploadTimeout(false);
        protocol.setConnectionUploadTimeout(milliseconds);
    }
}
