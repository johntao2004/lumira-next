package com.lumira.platform.identity;

import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.LocaleRegistryPort;
import com.lumira.platform.common.PluginExtensionsPort;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountLocaleService {
    public record LocaleResponse(String locale) {}

    private static final Set<String> BUILT_IN =
            Set.of("zh-CN", "zh-TW", "en", "ja", "ko", "fr", "es", "de");
    private final AccountLocaleRepository repository;
    private final ObjectProvider<PluginExtensionsPort> plugins;
    private final AuthorizationContextBoundaryPort authorizationBoundary;

    public AccountLocaleService(
            AccountLocaleRepository repository, ObjectProvider<PluginExtensionsPort> plugins) {
        this(repository, plugins, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccountLocaleService(
            AccountLocaleRepository repository,
            ObjectProvider<PluginExtensionsPort> plugins,
            AuthorizationContextBoundaryPort authorizationBoundary) {
        this.repository = repository;
        this.plugins = plugins;
        this.authorizationBoundary = authorizationBoundary;
    }

    @Transactional(readOnly = true)
    public LocaleResponse read(String actor) {
        var saved =
                repository
                        .find(actor)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        // Keep a disabled language in storage; do not silently overwrite the user's choice.
        return new LocaleResponse(
                saved.locale() != null && enabled(saved.locale()) ? saved.locale() : null);
    }

    @Transactional
    public LocaleResponse save(String actor, String input) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        String locale = canonical(input);
        if (!enabled(locale)) throw invalid();
        if (!repository.save(actor, locale))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return new LocaleResponse(locale);
    }

    private boolean enabled(String locale) {
        var runtime = plugins.getIfAvailable();
        List<Boolean> registered =
                runtime == null
                        ? List.of()
                        : runtime.extensions(
                                LocaleRegistryPort.class, port -> port.enabled(locale));
        return registered.isEmpty()
                ? BUILT_IN.contains(locale)
                : registered.stream().anyMatch(Boolean::booleanValue);
    }

    static String canonical(String input) {
        if (input == null
                || input.length() > 63
                || !input.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) throw invalid();
        try {
            return new Locale.Builder().setLanguageTag(input).build().toLanguageTag();
        } catch (java.util.IllformedLocaleException error) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidLocale");
    }
}
