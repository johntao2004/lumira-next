package com.lumira.platform.localization;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.localization.api.LocalizationEntryResponse;
import com.lumira.platform.modules.ModuleEndpoint;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/localization")
@ModuleEndpoint("localization")
public class LocalizationController {
    private final LocalizationService service;
    private final LocalizationAiService localizationAi;

    public LocalizationController(
            LocalizationService service, LocalizationAiService localizationAi) {
        this.service = service;
        this.localizationAi = localizationAi;
    }

    @GetMapping("/entries")
    public PageResult<LocalizationService.CatalogEntry> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "zh-CN") String locale,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication auth) {
        PageRequest request = PageRequest.fromQueryParameters(page, pageSize, search, parameters);
        Boolean status =
                request.filters().containsKey("enabled")
                        ? Boolean.valueOf(request.filters().get("enabled"))
                        : enabled;
        return service.list(request, locale, status, auth.getName());
    }

    @GetMapping("/locales")
    public java.util.List<LocalizationService.LocaleView> locales(Authentication auth) {
        return service.locales(auth.getName());
    }

    @GetMapping("/entries/{id}")
    public LocalizationEntryResponse find(@PathVariable UUID id, Authentication auth) {
        return LocalizationEntryResponse.from(service.find(id, auth.getName()));
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public LocalizationEntryResponse create(
            @BoundedBody(maxBytes = 32_000, errorCode = "invalidLocalization") @RequestBody
                    LocalizationService.Input input,
            Authentication auth) {
        return LocalizationEntryResponse.from(service.create(input, auth.getName()));
    }

    @PutMapping("/entries/{id}")
    public LocalizationEntryResponse update(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 32_000, errorCode = "invalidLocalization") @RequestBody
                    LocalizationService.Input input,
            Authentication auth) {
        return LocalizationEntryResponse.from(service.update(id, input, auth.getName()));
    }

    @PatchMapping("/entries/{id}/status")
    public LocalizationEntryResponse status(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 1_024, errorCode = "invalidLocalization") @RequestBody
                    StatusInput input,
            Authentication auth) {
        if (input == null || input.enabled() == null || input.version() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalidLocalization");
        }
        return LocalizationEntryResponse.from(
                service.setEnabled(id, input.version(), input.enabled(), auth.getName()));
    }

    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication auth) {
        service.delete(id, version, auth.getName());
    }

    @PostMapping("/import")
    public LocalizationService.ImportResult importEntries(
            @BoundedBody(maxBytes = 8_000_000, errorCode = "invalidLocalizationImport") @RequestBody
                    LocalizationService.ImportRequest input,
            Authentication auth) {
        return service.importEntries(input, auth.getName());
    }

    @PostMapping("/translations")
    public LocalizationService.OverrideResult translations(
            @BoundedBody(maxBytes = 1_000_000, errorCode = "invalidLocalization") @RequestBody
                    LocalizationService.OverrideRequest input,
            Authentication auth) {
        return service.saveOverrides(input, auth.getName());
    }

    @DeleteMapping("/translations/{locale}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreTranslation(
            @PathVariable("locale") String locale,
            @RequestParam("messageKey") String messageKey,
            @RequestParam("version") long version,
            Authentication auth) {
        service.restoreTranslation(locale, messageKey, version, auth.getName());
    }

    @PostMapping("/bundle/preview")
    public LocalizationService.BundlePreview previewBundle(
            @BoundedBody(maxBytes = 8_000_000, errorCode = "invalidLocalizationBundle") @RequestBody
                    LocalizationService.BundlePreviewRequest input,
            Authentication auth) {
        return service.previewBundle(input, auth.getName());
    }

    @PostMapping("/bundle/import")
    public LocalizationService.BundleImportResult importBundle(
            @BoundedBody(maxBytes = 8_000_000, errorCode = "invalidLocalizationBundle") @RequestBody
                    LocalizationService.BundleImportRequest input,
            Authentication auth) {
        return service.importBundle(input, auth.getName());
    }

    @GetMapping("/ai/providers")
    public java.util.List<LocalizationAiService.ProviderOption> translationProviders(
            Authentication auth) {
        return localizationAi.providers(auth.getName());
    }

    @PostMapping("/ai/translate")
    public LocalizationAiService.TranslationResponse translate(
            @BoundedBody(maxBytes = 64_000, errorCode = "invalidLocalization") @RequestBody
                    LocalizationAiService.TranslationRequest input,
            Authentication auth) {
        return localizationAi.translate(input, auth.getName());
    }

    @PostMapping("/publish/{locale}")
    public LocalizationService.PublishResult publish(
            @PathVariable String locale, Authentication auth) {
        return service.publish(locale, auth.getName());
    }

    public record StatusInput(Boolean enabled, Long version) {}
}
