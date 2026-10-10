package com.lumira.platform.plugins;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import java.io.InputStream;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@ModuleEndpoint("platform")
@RestController
@RequestMapping("/api/platform/modules/plugins")
public class PluginController {
    private final PluginRuntime plugins;

    public PluginController(PluginRuntime plugins) {
        this.plugins = plugins;
    }

    @GetMapping
    public List<PluginRuntime.View> list(Authentication auth) {
        return plugins.list(auth.getName());
    }

    @GetMapping("/frontend")
    public List<PluginRuntime.View> frontend() {
        return plugins.frontend();
    }

    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public List<PluginRuntime.View> upload(Authentication auth, InputStream stream)
            throws Exception {
        byte[] bytes = stream.readNBytes((int) PluginArchive.MAX_BYTES + 1);
        return plugins.importPackage(auth.getName(), bytes);
    }

    @PostMapping("/{id}/install-plan")
    public PluginRuntime.Plan installPlan(Authentication auth, @PathVariable String id) {
        return plugins.plan(auth.getName(), id, "INSTALL");
    }

    @PostMapping("/{id}/uninstall-plan")
    public PluginRuntime.Plan uninstallPlan(
            Authentication auth,
            @PathVariable String id,
            @RequestParam(defaultValue = "PRESERVE") String dataMode) {
        return plugins.plan(auth.getName(), id, "UNINSTALL", dataMode);
    }

    @PostMapping("/{id}/install")
    public PluginRuntime.Operation install(
            Authentication auth,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "invalidPluginPackage")
                    PluginRuntime.Execute input) {
        return plugins.execute(auth.getName(), id, "INSTALL", input);
    }

    @PostMapping("/{id}/uninstall")
    public PluginRuntime.Operation uninstall(
            Authentication auth,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "invalidPluginPackage")
                    PluginRuntime.Execute input) {
        return plugins.execute(auth.getName(), id, "UNINSTALL", input);
    }

    @GetMapping("/operations/{id}")
    public PluginRuntime.Operation operation(Authentication auth, @PathVariable UUID id) {
        return plugins.operation(auth.getName(), id);
    }

    @GetMapping("/assets/{digest}/{file}")
    public ResponseEntity<FileSystemResource> asset(
            @PathVariable String digest, @PathVariable String file) {
        return ResponseEntity.ok()
                .contentType(
                        MediaType.parseMediaType(
                                file.endsWith(".css") ? "text/css" : "text/javascript"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(plugins.asset(digest, file)));
    }
}
