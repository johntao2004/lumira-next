package com.lumira.platform.localization;

import com.lumira.platform.common.LocaleRegistryPort;
import org.springframework.stereotype.Component;

@Component
public class LocalizationLocaleRegistry implements LocaleRegistryPort {
    private final LocalizationLocaleRepository locales;

    public LocalizationLocaleRegistry(LocalizationLocaleRepository locales) {
        this.locales = locales;
    }

    @Override
    public boolean enabled(String locale) {
        return locales.exists(locale);
    }
}
