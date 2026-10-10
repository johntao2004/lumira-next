package com.lumira.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class PlatformApplication {
    public static void main(String[] args) {
        if (args.length >= 2 && "--office-parser-child".equals(args[0])) {
            int status = OfficeParserChild.run(java.nio.file.Path.of(args[1]));
            if (status != 0) System.exit(status);
            return;
        }
        if (args.length == 1 && "--office-worker".equals(args[0])) {
            try {
                OfficeWorkerServer.run();
            } catch (Exception error) {
                System.exit(1);
            }
            return;
        }
        if (args.length == 1 && "--office-worker-healthcheck".equals(args[0])) {
            if (OfficeWorkerServer.healthCheck() != 0) System.exit(1);
            return;
        }
        if (MfaOfflineRecoveryCommand.requested(args)) {
            int status = MfaOfflineRecoveryCommand.run(args);
            if (status != 0) System.exit(status);
            return;
        }
        SpringApplication.run(PlatformApplication.class, args);
    }
}
