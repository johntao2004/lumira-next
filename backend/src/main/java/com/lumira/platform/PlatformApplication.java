/**
 * 分流宿主、Office worker、解析子进程及离线 MFA 恢复入口，使专用进程复用发行包而无需启动整套 Web 服务。 Dispatches host, Office worker,
 * parser-child and offline MFA recovery entry points so specialized processes can reuse the
 * distribution without starting the web application.
 */
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
                String message = error.getMessage();
                if (message != null && message.startsWith("Office worker ")) {
                    System.err.println("Office worker startup failed: " + message);
                } else {
                    System.err.println(
                            "Office worker startup failed: " + error.getClass().getSimpleName());
                }
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
