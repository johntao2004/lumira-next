package com.lumira.platform.localization;

import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, permission-free runtime bundle consumed by the web shell. */
@RestController
@RequestMapping("/api/public/localization")
@ModuleEndpoint("localization")
public class LocalizationRuntimeController {
    private final LocalizationService service;

    public LocalizationRuntimeController(LocalizationService service) {
        this.service = service;
    }

    @GetMapping("/bundle/{locale}")
    public LocalizationService.Bundle bundle(@PathVariable String locale) {
        return service.bundle(locale);
    }

    @GetMapping("/locales")
    public java.util.List<LocalizationService.LocaleView> locales() {
        return service.publicLocales();
    }
}
