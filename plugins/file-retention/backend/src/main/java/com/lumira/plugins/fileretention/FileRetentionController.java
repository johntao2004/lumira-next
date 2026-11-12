package com.lumira.plugins.fileretention;

import com.lumira.platform.common.BoundedBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("file-retention")
public class FileRetentionController {
    private final FileRetentionAdministration policy;

    public FileRetentionController(FileRetentionAdministration policy) {
        this.policy = policy;
    }

    @GetMapping("/api/platform/settings/file-retention")
    public FileRetentionAdministration.View read(Authentication actor) {
        return policy.read(actor.getName());
    }

    @PutMapping("/api/platform/settings/file-retention")
    public FileRetentionAdministration.View save(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidFileRetention") @RequestBody
                    FileRetentionAdministration.Input input,
            Authentication actor) {
        return policy.save(input, actor.getName());
    }

    @PostMapping("/api/platform/settings/file-retention/rollback")
    public FileRetentionAdministration.View rollback(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidSettingRevision") @RequestBody
                    FileRetentionAdministration.RollbackInput input,
            Authentication actor) {
        return policy.rollback(input, actor.getName());
    }
}
