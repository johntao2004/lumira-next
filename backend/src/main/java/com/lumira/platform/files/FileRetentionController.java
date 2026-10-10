package com.lumira.platform.files;

import com.lumira.platform.common.BoundedBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("files")
public class FileRetentionController {
    private final FileRetentionPolicy policy;

    public FileRetentionController(FileRetentionPolicy policy) {
        this.policy = policy;
    }

    @GetMapping("/api/platform/settings/file-retention")
    public FileRetentionPolicy.View read() {
        return policy.read();
    }

    @PutMapping("/api/platform/settings/file-retention")
    public FileRetentionPolicy.View save(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidFileRetention") @RequestBody
                    FileRetentionPolicy.Input input,
            Authentication actor) {
        return policy.save(input, actor.getName());
    }

    @PostMapping("/api/platform/settings/file-retention/rollback")
    public FileRetentionPolicy.View rollback(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidSettingRevision") @RequestBody
                    FileRetentionPolicy.RollbackInput input,
            Authentication actor) {
        return policy.rollback(input, actor.getName());
    }
}
