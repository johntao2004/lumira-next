package com.lumira.platform.ai;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/platform/ai")
@ModuleEndpoint("platform")
public class AiController {
    private final AiProviderService providers;
    private final AiAssistantService assistant;
    private final AiDecisionService decisions;

    public AiController(
            AiProviderService providers,
            AiAssistantService assistant,
            AiDecisionService decisions) {
        this.providers = providers;
        this.assistant = assistant;
        this.decisions = decisions;
    }

    @GetMapping("/providers")
    public List<AiProviderService.ProviderView> providers(Authentication auth) {
        return providers.list(auth.getName());
    }

    @GetMapping("/providers/{id}/models")
    public ModelConnectionService.ConnectionResult models(
            @PathVariable UUID id, Authentication auth) {
        return providers.models(id, auth.getName());
    }

    @GetMapping("/console/providers")
    public List<AiProviderService.ConsoleProviderView> consoleProviders(Authentication auth) {
        return providers.listForConsole(auth.getName());
    }

    @GetMapping("/console/providers/{id}/models")
    public ModelConnectionService.ConnectionResult consoleModels(
            @PathVariable UUID id, Authentication auth) {
        return providers.configuredModelsForConsole(id, auth.getName());
    }

    @PostMapping("/providers/discover-models")
    public ModelConnectionService.ConnectionResult discoverModels(
            @BoundedBody(maxBytes = 16384, errorCode = "invalidAiProvider") @RequestBody
                    AiProviderService.ModelDiscoveryRequest input,
            Authentication auth) {
        return providers.discoverModels(input, auth.getName());
    }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public AiProviderService.ProviderView create(
            @BoundedBody(maxBytes = 16384, errorCode = "invalidAiProvider") @RequestBody
                    AiProviderService.ProviderWrite input,
            Authentication auth) {
        return providers.create(input, auth.getName());
    }

    @PutMapping("/providers/{id}")
    public AiProviderService.ProviderView update(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 16384, errorCode = "invalidAiProvider") @RequestBody
                    AiProviderService.ProviderWrite input,
            Authentication auth) {
        return providers.update(id, input, auth.getName());
    }

    @DeleteMapping("/providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication auth) {
        providers.delete(id, version, auth.getName());
    }

    @PostMapping("/providers/{id}/test")
    public ModelConnectionService.ConnectionResult test(
            @PathVariable UUID id, Authentication auth) {
        return providers.test(id, auth.getName());
    }

    @GetMapping("/capabilities")
    public List<AiAssistantService.Capability> capabilities(Authentication auth) {
        return assistant.capabilities(auth.getName());
    }

    @PostMapping("/chat")
    public AiAssistantService.ChatResponse chat(
            @BoundedBody(maxBytes = 128 * 1024, errorCode = "invalidAiChat") @RequestBody
                    AiAssistantService.ChatRequest input,
            Authentication auth) {
        return assistant.chat(input, auth.getName());
    }

    @GetMapping("/attachments/{id}")
    public AiAssistantService.AttachmentMetadata attachment(
            @PathVariable UUID id, Authentication auth) {
        return assistant.attachment(id, auth.getName());
    }

    @PostMapping("/actions/{id}/confirm")
    public AiAssistantService.ActionResult confirm(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 4096, errorCode = "invalidAiAction")
                    @RequestBody(required = false)
                    AiAssistantService.ConfirmAction input,
            Authentication auth) {
        return assistant.confirm(id, input, auth.getName());
    }

    @DeleteMapping("/actions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id, Authentication auth) {
        assistant.cancel(id, auth.getName());
    }

    @PostMapping("/judgments")
    public JsonNode judge(
            @BoundedBody(maxBytes = 20000, errorCode = "invalidAiJudgment") @RequestBody
                    AiDecisionService.JudgmentRequest input,
            Authentication auth) {
        return decisions.judge(input, auth.getName());
    }
}
