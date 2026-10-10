package com.lumira.platform.localization;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LocalizationService {
    public static final String READ = "platform:localization:read";
    public static final String WRITE = "platform:localization:write";
    public static final String IMPORT = "platform:localization:import";
    private static final int MAX_IMPORT_ENTRIES = 20_000;
    private static final long MAX_PACKAGE_CHARACTERS = 5_000_000;
    private static final Pattern MESSAGE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,199}");
    private static final Pattern CUSTOM_KEY =
            Pattern.compile("custom\\.[A-Za-z0-9][A-Za-z0-9_.-]{0,193}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9_.-]{0,79}");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<\\s*/?\\s*[a-z][^>]*>");

    public record Input(
            String messageKey,
            String namespace,
            String defaultMessage,
            Map<String, String> translations,
            Boolean enabled,
            Long version) {}

    public record ImportEntry(
            String messageKey,
            String namespace,
            String defaultMessage,
            Map<String, String> translations) {}

    public record ImportRequest(List<ImportEntry> entries) {}

    public record ImportResult(int total, int imported, int duplicated, int invalid) {}

    public record LocaleView(
            String locale,
            String displayName,
            String sourceLocale,
            String fallbackLocale,
            boolean builtIn,
            boolean enabled,
            long releaseVersion,
            int textCount) {}

    public record CatalogEntry(
            String id,
            String messageKey,
            String namespace,
            String sourceMessage,
            String currentValue,
            String valueSource,
            boolean overridden,
            boolean builtIn,
            boolean enabled,
            long version,
            Map<String, String> translations) {
        public CatalogEntry {
            translations = Map.copyOf(translations == null ? Map.of() : translations);
        }
    }

    public record OverrideEntry(String messageKey, String value, Long version) {}

    public record OverrideRequest(
            String locale,
            List<OverrideEntry> entries,
            Boolean overwriteExisting,
            String sourceLocale,
            String sourceSnapshotToken) {
        public OverrideRequest(
                String locale, List<OverrideEntry> entries, Boolean overwriteExisting) {
            this(locale, entries, overwriteExisting, null, null);
        }
    }

    public record OverrideResult(String locale, int saved, int skipped, long releaseVersion) {}

    public record BundleRequest(
            int formatVersion,
            String locale,
            String displayName,
            String sourceLocale,
            String fallbackLocale,
            Long releaseVersion,
            Integer effectiveTextCount,
            Map<String, String> messages) {
        public BundleRequest(
                int formatVersion,
                String locale,
                String displayName,
                String sourceLocale,
                String fallbackLocale,
                Map<String, String> messages) {
            this(
                    formatVersion,
                    locale,
                    displayName,
                    sourceLocale,
                    fallbackLocale,
                    null,
                    null,
                    messages);
        }

        public BundleRequest {
            messages =
                    java.util.Collections.unmodifiableMap(
                            new LinkedHashMap<>(messages == null ? Map.of() : messages));
        }
    }

    public record BundlePreviewRequest(BundleRequest bundle, Boolean overwriteExisting) {}

    public record BundlePreview(
            String locale,
            boolean newLocale,
            int added,
            int duplicates,
            int overwrites,
            int unknown,
            int fallbacks,
            List<String> unknownKeys) {}

    public record BundleImportRequest(
            BundleRequest bundle, Boolean overwriteExisting, Boolean ignoreUnknownKeys) {}

    public record BundleImportResult(
            String locale,
            int imported,
            int duplicated,
            int unknown,
            int fallbacks,
            long releaseVersion) {}

    public record Bundle(
            int formatVersion,
            String locale,
            String displayName,
            String sourceLocale,
            String fallbackLocale,
            long releaseVersion,
            int effectiveTextCount,
            Map<String, String> messages,
            String snapshotToken) {
        public Bundle(
                int formatVersion,
                String locale,
                String displayName,
                String sourceLocale,
                String fallbackLocale,
                long releaseVersion,
                int effectiveTextCount,
                Map<String, String> messages) {
            this(
                    formatVersion,
                    locale,
                    displayName,
                    sourceLocale,
                    fallbackLocale,
                    releaseVersion,
                    effectiveTextCount,
                    messages,
                    null);
        }

        public Bundle {
            messages = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(messages));
        }
    }

    public record PublishResult(String locale, long releaseVersion, int entries) {}

    private record Values(
            String messageKey,
            String namespace,
            String defaultMessage,
            Map<String, String> translations,
            boolean enabled) {}

    private record Resolved(String value, String source) {}

    private final LocalizationRepository repository;
    private final LocalizationLocaleRepository localeRepository;
    private final LocalizationBuiltinCatalog builtins;
    private final AdministrationPort guard;
    private final AuditPort audit;

    public LocalizationService(
            LocalizationRepository repository,
            LocalizationLocaleRepository localeRepository,
            LocalizationBuiltinCatalog builtins,
            AdministrationPort guard,
            AuditPort audit) {
        this.repository = repository;
        this.localeRepository = localeRepository;
        this.builtins = builtins;
        this.guard = guard;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<CatalogEntry> list(
            PageRequest page, String requestedLocale, Boolean enabled, String actor) {
        guard.requireGlobal(actor, READ);
        var language = requireLocale(requestedLocale);
        var chain = fallbackChain(language);
        var rows = repository.allActive();
        var byKey = new LinkedHashMap<String, LocalizationRepository.Entry>();
        for (var row : rows) byKey.putIfAbsent(row.messageKey(), row);
        var entries = new ArrayList<CatalogEntry>(builtins.keys().size() + rows.size());
        for (String key : builtins.keys()) {
            entries.add(
                    catalogEntry(
                            key,
                            "platform",
                            builtins.sourceText(key),
                            byKey.get(key),
                            language,
                            chain,
                            true));
        }
        for (var row : rows) {
            if (builtins.hasKey(row.messageKey())) continue;
            entries.add(
                    catalogEntry(
                            row.messageKey(),
                            row.namespace(),
                            row.defaultMessage(),
                            row,
                            language,
                            chain,
                            false));
        }
        entries.sort(Comparator.comparing(CatalogEntry::messageKey));
        var filtered =
                entries.stream()
                        .filter(item -> enabled == null || item.enabled() == enabled)
                        .filter(item -> matches(page, item))
                        .toList();
        int start = (int) Math.min(page.offset(), filtered.size());
        int end = Math.min(start + page.pageSize(), filtered.size());
        return new PageResult<>(
                filtered.subList(start, end), filtered.size(), page.page(), page.pageSize());
    }

    @Transactional(readOnly = true)
    public LocalizationRepository.Entry find(UUID id, String actor) {
        guard.requireGlobal(actor, READ);
        return repository.find(id);
    }

    @Transactional(readOnly = true)
    public List<LocaleView> locales(String actor) {
        guard.requireGlobal(actor, READ);
        return localeViews();
    }

    /** Public metadata only; language names and safe fallback routing contain no secrets. */
    @Transactional(readOnly = true)
    public List<LocaleView> publicLocales() {
        return localeViews();
    }

    private List<LocaleView> localeViews() {
        int dynamicCount =
                (int)
                        repository.allActive().stream()
                                .filter(row -> !builtins.hasKey(row.messageKey()) && row.enabled())
                                .count();
        int count = builtins.keys().size() + dynamicCount;
        return localeRepository.listEnabled().stream()
                .map(
                        item ->
                                new LocaleView(
                                        item.locale(),
                                        item.displayName(),
                                        item.sourceLocale(),
                                        item.fallbackLocale(),
                                        item.builtIn(),
                                        item.enabled(),
                                        repository.releaseVersion(item.locale()),
                                        count))
                .toList();
    }

    @Transactional
    public LocalizationRepository.Entry create(Input input, String actor) {
        writeGuard(actor, WRITE);
        Values values = values(input);
        if (!CUSTOM_KEY.matcher(values.messageKey()).matches()
                || !"custom".equals(values.namespace()))
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        if (repository.exists(values.messageKey(), null))
            fail(HttpStatus.CONFLICT, "duplicateLocalization");
        repository.lockCatalogForWrite();
        UUID id = UUID.randomUUID();
        repository.create(
                id,
                values.messageKey(),
                values.namespace(),
                values.defaultMessage(),
                values.translations(),
                values.enabled(),
                actor);
        advance(values.translations().keySet(), actor);
        audit.append(actor, "localization.created", id);
        return repository.find(id);
    }

    @Transactional
    public LocalizationRepository.Entry update(UUID id, Input input, String actor) {
        writeGuard(actor, WRITE);
        repository.lockCatalogForWrite();
        var current = repository.find(id);
        if (builtins.hasKey(current.messageKey()))
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        if (input == null || input.version() == null || input.version() != current.version())
            fail(HttpStatus.CONFLICT, "staleRecord");
        Values values = values(input);
        if (!CUSTOM_KEY.matcher(values.messageKey()).matches()
                || !"custom".equals(values.namespace()))
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        if (repository.exists(values.messageKey(), id))
            fail(HttpStatus.CONFLICT, "duplicateLocalization");
        if (repository.update(
                        id,
                        input.version(),
                        values.messageKey(),
                        values.namespace(),
                        values.defaultMessage(),
                        values.translations(),
                        values.enabled(),
                        actor)
                != 1) {
            fail(HttpStatus.CONFLICT, "staleRecord");
        }
        advance(values.translations().keySet(), actor);
        audit.append(actor, "localization.updated", id);
        return repository.find(id);
    }

    @Transactional
    public LocalizationRepository.Entry setEnabled(
            UUID id, long version, boolean enabled, String actor) {
        writeGuard(actor, WRITE);
        repository.lockCatalogForWrite();
        var current = repository.find(id);
        if (builtins.hasKey(current.messageKey()))
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        if (repository.setEnabled(id, version, enabled, actor) != 1)
            fail(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, enabled ? "localization.enabled" : "localization.disabled", id);
        return repository.find(id);
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        writeGuard(actor, WRITE);
        repository.lockCatalogForWrite();
        var current = repository.find(id);
        if (builtins.hasKey(current.messageKey()))
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        if (repository.softDelete(id, version, actor) != 1)
            fail(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "localization.deleted", id);
    }

    /** Saves selected locale values without replacing other locales or unrelated keys. */
    @Transactional
    public OverrideResult saveOverrides(OverrideRequest input, String actor) {
        writeGuard(actor, WRITE);
        if (input == null
                || input.entries() == null
                || input.entries().isEmpty()
                || input.entries().size() > 100)
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        if ((input.sourceLocale() == null) != (input.sourceSnapshotToken() == null))
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        repository.lockCatalogForWrite();
        if (input.sourceLocale() != null)
            requireCurrentSourceSnapshot(input.sourceLocale(), input.sourceSnapshotToken());
        var language = requireLocale(input.locale());
        boolean allowOverwrite = Boolean.TRUE.equals(input.overwriteExisting());
        var seen = new LinkedHashSet<String>();
        int saved = 0;
        int skipped = 0;
        for (var candidate : input.entries()) {
            if (candidate == null || !seen.add(candidate.messageKey()))
                fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
            String key = validKey(candidate.messageKey());
            String value = validCopy(candidate.value());
            var row = repository.findByKey(key).orElse(null);
            if (!builtins.hasKey(key) && row == null)
                fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
            if (row != null
                    && row.translations().containsKey(language.locale())
                    && !allowOverwrite) {
                skipped++;
                continue;
            }
            long expected = candidate.version() == null ? -1 : candidate.version();
            if (row == null) {
                if (expected != 0
                        || repository.insertOverride(
                                        UUID.randomUUID(),
                                        key,
                                        "platform",
                                        builtins.sourceText(key),
                                        language.locale(),
                                        value,
                                        actor)
                                != 1) {
                    fail(HttpStatus.CONFLICT, "staleRecord");
                }
            } else {
                if (expected < 1
                        || row.version() != expected
                        || repository.updateTranslation(
                                        row.id(), expected, language.locale(), value, actor)
                                != 1) {
                    fail(HttpStatus.CONFLICT, "staleRecord");
                }
            }
            saved++;
        }
        if (saved > 0) {
            repository.publish(language.locale(), actor);
            audit.append(
                    actor, "localization.translations.saved", localeAuditId(language.locale()));
        }
        return new OverrideResult(
                language.locale(), saved, skipped, repository.releaseVersion(language.locale()));
    }

    /**
     * Clears only an explicit translation for the selected locale and restores normal fallback
     * resolution.
     */
    @Transactional
    public void restoreTranslation(
            String requestedLocale, String requestedKey, long expectedVersion, String actor) {
        writeGuard(actor, WRITE);
        repository.lockCatalogForWrite();
        var language = requireLocale(requestedLocale);
        String key = validKey(requestedKey);
        var row =
                repository
                        .findByKey(key)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "localizationNotFound"));
        if (expectedVersion < 1
                || row.version() != expectedVersion
                || !row.translations().containsKey(language.locale())) {
            fail(HttpStatus.CONFLICT, "staleRecord");
        }
        int changed =
                isCanonicalBuiltinOverlay(key, row) && row.translations().size() == 1
                        ? repository.softDelete(row.id(), expectedVersion, actor)
                        : repository.removeTranslation(
                                row.id(), expectedVersion, language.locale(), actor);
        if (changed != 1) {
            fail(HttpStatus.CONFLICT, "staleRecord");
        }
        repository.publish(language.locale(), actor);
        audit.append(actor, "localization.translation.restored", row.id());
    }

    private boolean isCanonicalBuiltinOverlay(String key, LocalizationRepository.Entry row) {
        if (!builtins.hasKey(key) || !row.enabled() || !"platform".equals(row.namespace()))
            return false;
        String source = builtins.sourceText(key);
        return source != null && source.equals(row.defaultMessage());
    }

    @Transactional(readOnly = true)
    public BundlePreview previewBundle(BundlePreviewRequest request, String actor) {
        guard.requireGlobal(actor, IMPORT);
        var checked = validateBundle(request == null ? null : request.bundle());
        return bundlePreview(checked, Boolean.TRUE.equals(request.overwriteExisting()), null);
    }

    @Transactional
    public BundleImportResult importBundle(BundleImportRequest request, String actor) {
        writeGuard(actor, IMPORT);
        repository.lockCatalogForWrite();
        var checked = validateBundle(request == null ? null : request.bundle());
        boolean overwrite = Boolean.TRUE.equals(request.overwriteExisting());
        boolean ignoreUnknown = Boolean.TRUE.equals(request.ignoreUnknownKeys());
        var targetLanguage = localeRepository.find(checked.locale()).orElse(null);
        var chain =
                targetLanguage == null
                        ? fallbackChain(
                                checked.locale(), checked.fallbackLocale(), checked.sourceLocale())
                        : fallbackChain(targetLanguage);
        var preview = bundlePreview(checked, overwrite, chain);
        if (preview.unknown() > 0 && !ignoreUnknown)
            fail(HttpStatus.BAD_REQUEST, "invalidLocalizationBundle");
        if (preview.added() + preview.overwrites() == 0 && preview.newLocale())
            fail(HttpStatus.BAD_REQUEST, "invalidLocalizationBundle");
        if (preview.newLocale()
                && localeRepository.insert(
                                checked.locale(),
                                checked.displayName(),
                                checked.sourceLocale(),
                                checked.fallbackLocale(),
                                actor)
                        != 1) {
            fail(HttpStatus.CONFLICT, "staleRecord");
        }
        int imported = 0;
        int duplicated = 0;
        var active = new LinkedHashMap<String, LocalizationRepository.Entry>();
        for (var row : repository.allActive()) active.put(row.messageKey(), row);
        for (var translation : checked.messages().entrySet()) {
            String key = translation.getKey();
            if (!builtins.hasKey(key) && !active.containsKey(key)) continue;
            var row = active.get(key);
            boolean hasExplicitTranslation =
                    row != null && row.translations().containsKey(checked.locale());
            if (hasExplicitTranslation) {
                String currentTranslation = row.translations().get(checked.locale());
                if (!overwrite || translation.getValue().equals(currentTranslation)) {
                    duplicated++;
                    continue;
                }
            } else if (sameAsCurrentEffectiveValue(
                    key, row, targetLanguage, translation.getValue(), chain)) {
                duplicated++;
                continue;
            }
            if (row == null) {
                String source = builtins.sourceText(key);
                if (source == null
                        || repository.insertOverride(
                                        UUID.randomUUID(),
                                        key,
                                        "platform",
                                        source,
                                        checked.locale(),
                                        translation.getValue(),
                                        actor)
                                != 1) {
                    fail(HttpStatus.CONFLICT, "staleRecord");
                }
            } else {
                if (repository.updateTranslation(
                                row.id(),
                                row.version(),
                                checked.locale(),
                                translation.getValue(),
                                actor)
                        != 1) {
                    fail(HttpStatus.CONFLICT, "staleRecord");
                }
                active.put(
                        key,
                        new LocalizationRepository.Entry(
                                row.id(),
                                row.messageKey(),
                                row.namespace(),
                                row.defaultMessage(),
                                merged(
                                        row.translations(),
                                        checked.locale(),
                                        translation.getValue()),
                                row.enabled(),
                                row.version() + 1,
                                row.createdBy(),
                                actor,
                                row.createdAt(),
                                row.updatedAt()));
            }
            imported++;
        }
        if (imported > 0 || preview.newLocale()) {
            long version = repository.publish(checked.locale(), actor);
            audit.append(actor, "localization.bundle.imported", localeAuditId(checked.locale()));
            return new BundleImportResult(
                    checked.locale(),
                    imported,
                    duplicated,
                    preview.unknown(),
                    preview.fallbacks(),
                    version);
        }
        return new BundleImportResult(
                checked.locale(),
                imported,
                duplicated,
                preview.unknown(),
                preview.fallbacks(),
                repository.releaseVersion(checked.locale()));
    }

    @Transactional
    public ImportResult importEntries(ImportRequest input, String actor) {
        writeGuard(actor, IMPORT);
        if (input == null || input.entries() == null || input.entries().size() > MAX_IMPORT_ENTRIES)
            fail(HttpStatus.BAD_REQUEST, "invalidLocalizationImport");
        repository.lockCatalogForWrite();
        int invalid = 0;
        int duplicated = 0;
        int imported = 0;
        var unique = new LinkedHashMap<String, Values>();
        for (ImportEntry candidate : input.entries()) {
            try {
                Values values = values(candidate);
                if (!CUSTOM_KEY.matcher(values.messageKey()).matches()
                        || !"custom".equals(values.namespace())) {
                    invalid++;
                    continue;
                }
                if (unique.putIfAbsent(values.messageKey(), values) != null) duplicated++;
            } catch (RuntimeException invalidEntry) {
                invalid++;
            }
        }
        for (Values values : unique.values()) {
            try {
                if (repository.insertIfAbsent(
                                UUID.randomUUID(),
                                values.messageKey(),
                                values.namespace(),
                                values.defaultMessage(),
                                values.translations(),
                                actor)
                        == 1) imported++;
                else duplicated++;
            } catch (RuntimeException duplicate) {
                duplicated++;
            }
        }
        if (imported > 0) {
            advance(
                    unique.values().stream()
                            .flatMap(item -> item.translations().keySet().stream())
                            .collect(java.util.stream.Collectors.toSet()),
                    actor);
            audit.append(actor, "localization.imported", UUID.randomUUID());
        }
        return new ImportResult(input.entries().size(), imported, duplicated, invalid);
    }

    /**
     * Publish advances the registered locale's version and returns its complete effective bundle.
     */
    @Transactional
    public PublishResult publish(String requestedLocale, String actor) {
        writeGuard(actor, WRITE);
        repository.lockCatalogForWrite();
        var language = requireLocale(requestedLocale);
        long version = repository.publish(language.locale(), actor);
        audit.append(actor, "localization.published", localeAuditId(language.locale()));
        return new PublishResult(
                language.locale(), version, builtins.keys().size() + customCount());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Bundle bundle(String requestedLocale) {
        return buildBundle(requestedLocale);
    }

    private Bundle buildBundle(String requestedLocale) {
        var language = requireLocale(requestedLocale);
        var chain = fallbackChain(language);
        var rows = repository.allActive();
        var byKey = new LinkedHashMap<String, LocalizationRepository.Entry>();
        rows.forEach(row -> byKey.putIfAbsent(row.messageKey(), row));
        var messages = new LinkedHashMap<String, String>();
        for (String key : builtins.keys()) {
            Resolved value =
                    resolve(key, builtins.sourceText(key), byKey.get(key), language, chain);
            if (value.value() != null) messages.put(key, value.value());
        }
        for (var row : rows) {
            if (builtins.hasKey(row.messageKey()) || !row.enabled()) continue;
            Resolved value = resolve(row.messageKey(), row.defaultMessage(), row, language, chain);
            if (value.value() != null) messages.put(row.messageKey(), value.value());
        }
        long releaseVersion = repository.releaseVersion(language.locale());
        return new Bundle(
                1,
                language.locale(),
                language.displayName(),
                language.sourceLocale(),
                language.fallbackLocale(),
                releaseVersion,
                messages.size(),
                messages,
                sourceSnapshotToken(language, chain, releaseVersion, messages));
    }

    private void requireCurrentSourceSnapshot(String requestedLocale, String expectedToken) {
        String token = clean(expectedToken);
        if (token == null || !token.matches("[a-f0-9]{64}"))
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        String current = buildBundle(canonicalLocale(requestedLocale)).snapshotToken();
        if (!MessageDigest.isEqual(
                token.getBytes(StandardCharsets.US_ASCII),
                current.getBytes(StandardCharsets.US_ASCII))) {
            fail(HttpStatus.CONFLICT, "localizationSourceChanged");
        }
    }

    private static String sourceSnapshotToken(
            LocalizationLocaleRepository.Language language,
            List<String> fallbackChain,
            long releaseVersion,
            Map<String, String> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, language.locale());
            updateDigest(digest, language.sourceLocale());
            updateDigest(digest, language.fallbackLocale());
            updateDigest(digest, Long.toString(language.version()));
            updateDigest(digest, Long.toString(releaseVersion));
            updateDigest(digest, Integer.toString(fallbackChain.size()));
            for (String locale : fallbackChain) updateDigest(digest, locale);
            updateDigest(digest, Integer.toString(messages.size()));
            new TreeMap<>(messages)
                    .forEach(
                            (key, value) -> {
                                updateDigest(digest, key);
                                updateDigest(digest, value);
                            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private BundlePreview bundlePreview(
            BundleRequest bundle, boolean overwrite, List<String> suppliedChain) {
        // validateBundle returns canonical metadata; derive registration state without requiring it
        // to exist.
        boolean isNew = !localeRepository.exists(bundle.locale());
        var targetLanguage = localeRepository.find(bundle.locale()).orElse(null);
        var chain =
                suppliedChain != null
                        ? suppliedChain
                        : targetLanguage == null
                                ? fallbackChain(
                                        bundle.locale(),
                                        bundle.fallbackLocale(),
                                        bundle.sourceLocale())
                                : fallbackChain(targetLanguage);
        var allRows = repository.allActive();
        var rows = new LinkedHashMap<String, LocalizationRepository.Entry>();
        allRows.forEach(row -> rows.putIfAbsent(row.messageKey(), row));
        int added = 0, duplicates = 0, overwrites = 0, unknown = 0;
        var unknownKeys = new ArrayList<String>();
        long characters = 0;
        for (var entry : bundle.messages().entrySet()) {
            characters +=
                    entry.getKey().length()
                            + (entry.getValue() == null ? 0 : entry.getValue().length());
            if (!builtins.hasKey(entry.getKey()) && !rows.containsKey(entry.getKey())) {
                unknown++;
                if (unknownKeys.size() < 100) unknownKeys.add(entry.getKey());
                continue;
            }
            var row = rows.get(entry.getKey());
            boolean exists = row != null && row.translations().containsKey(bundle.locale());
            if (exists) {
                boolean unchanged =
                        entry.getValue().equals(row.translations().get(bundle.locale()));
                if (overwrite && !unchanged) overwrites++;
                else duplicates++;
            } else if (sameAsCurrentEffectiveValue(
                    entry.getKey(), row, targetLanguage, entry.getValue(), chain)) duplicates++;
            else added++;
        }
        if (characters > MAX_PACKAGE_CHARACTERS)
            fail(HttpStatus.BAD_REQUEST, "invalidLocalizationBundle");
        int totalKnown =
                builtins.keys().size()
                        + (int) rows.keySet().stream().filter(key -> !builtins.hasKey(key)).count();
        int fallbacks =
                Math.max(
                        0,
                        totalKnown
                                - (int)
                                        bundle.messages().keySet().stream()
                                                .filter(
                                                        key ->
                                                                builtins.hasKey(key)
                                                                        || rows.containsKey(key))
                                                .count());
        return new BundlePreview(
                bundle.locale(),
                isNew,
                added,
                duplicates,
                overwrites,
                unknown,
                fallbacks,
                List.copyOf(unknownKeys));
    }

    private boolean sameAsCurrentEffectiveValue(
            String key,
            LocalizationRepository.Entry row,
            LocalizationLocaleRepository.Language locale,
            String incoming,
            List<String> chain) {
        if (locale == null) return false;
        String source =
                builtins.hasKey(key)
                        ? builtins.sourceText(key)
                        : row == null ? null : row.defaultMessage();
        String current = resolve(key, source, row, locale, chain).value();
        return current != null && current.equals(incoming);
    }

    private BundleRequest validateBundle(BundleRequest bundle) {
        if (bundle == null
                || bundle.formatVersion() != 1
                || bundle.messages() == null
                || bundle.messages().isEmpty()
                || bundle.messages().size() > MAX_IMPORT_ENTRIES
                || (bundle.releaseVersion() != null && bundle.releaseVersion() < 0)
                || (bundle.effectiveTextCount() != null && bundle.effectiveTextCount() < 0)) {
            fail(HttpStatus.BAD_REQUEST, "invalidLocalizationBundle");
        }
        String target = canonicalLocale(bundle.locale());
        String source = canonicalLocale(bundle.sourceLocale());
        String fallback = canonicalLocale(bundle.fallbackLocale());
        String displayName = validDisplayName(bundle.displayName());
        boolean newLocale = !localeRepository.exists(target);
        if (!localeRepository.exists(source)
                || !localeRepository.exists(fallback)
                || (newLocale && target.equals(fallback))) {
            fail(HttpStatus.BAD_REQUEST, "invalidLocalizationBundle");
        }
        long characters = 0;
        var cleanMessages = new LinkedHashMap<String, String>();
        for (var entry : bundle.messages().entrySet()) {
            String key = validKey(entry.getKey());
            String value = validBundleCopy(entry.getValue());
            if (cleanMessages.putIfAbsent(key, value) != null)
                fail(HttpStatus.BAD_REQUEST, "invalidLocalizationBundle");
            characters += key.length() + value.length();
            if (characters > MAX_PACKAGE_CHARACTERS)
                fail(HttpStatus.BAD_REQUEST, "invalidLocalizationBundle");
        }
        return new BundleRequest(
                1,
                target,
                displayName,
                source,
                fallback,
                bundle.releaseVersion(),
                bundle.effectiveTextCount(),
                cleanMessages);
    }

    private CatalogEntry catalogEntry(
            String key,
            String namespace,
            String source,
            LocalizationRepository.Entry row,
            LocalizationLocaleRepository.Language language,
            List<String> chain,
            boolean builtIn) {
        Resolved resolved = resolve(key, source, row, language, chain);
        String value = resolved.value();
        boolean overridden = row != null && row.translations().containsKey(language.locale());
        return new CatalogEntry(
                builtIn ? "builtin:" + key : row.id().toString(),
                key,
                namespace,
                source,
                value == null ? "" : value,
                resolved.source(),
                overridden,
                builtIn,
                builtIn || row.enabled(),
                row == null ? 0 : row.version(),
                row == null ? Map.of() : row.translations());
    }

    private Resolved resolve(
            String key,
            String sourceText,
            LocalizationRepository.Entry row,
            LocalizationLocaleRepository.Language target,
            List<String> chain) {
        for (int i = 0; i < chain.size(); i++) {
            String candidate = chain.get(i);
            if (row != null && row.enabled()) {
                String override = row.translations().get(candidate);
                if (override != null && !override.isBlank())
                    return new Resolved(override, i == 0 ? "OVERRIDE" : "FALLBACK");
            }
            String staticValue = builtins.messages(candidate).get(key);
            if (staticValue != null && !staticValue.isBlank()) {
                if (staticValue.equals(sourceText)) {
                    return new Resolved(
                            staticValue,
                            target.locale().equals(builtins.sourceLocale())
                                    ? "SOURCE"
                                    : "FALLBACK");
                }
                return new Resolved(staticValue, i == 0 ? "STATIC" : "FALLBACK");
            }
        }
        if (sourceText != null && !sourceText.isBlank()) return new Resolved(sourceText, "SOURCE");
        return new Resolved(null, "MISSING");
    }

    private List<String> fallbackChain(LocalizationLocaleRepository.Language language) {
        return fallbackChain(language.locale(), language.fallbackLocale(), language.sourceLocale());
    }

    private List<String> fallbackChain(String locale, String fallbackLocale, String sourceLocale) {
        var order = new LinkedHashSet<String>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(locale);
        queue.add(fallbackLocale);
        queue.add(sourceLocale);
        queue.add(builtins.sourceLocale());
        queue.add("zh-CN");
        int hops = 0;
        while (!queue.isEmpty() && hops++ < 32) {
            String next = queue.removeFirst();
            if (next == null || !order.add(next)) continue;
            var metadata = localeRepository.find(next).orElse(null);
            if (metadata != null) {
                queue.addLast(metadata.fallbackLocale());
                queue.addLast(metadata.sourceLocale());
            }
        }
        return List.copyOf(order);
    }

    private boolean matches(PageRequest page, CatalogEntry row) {
        if (!page.search().isBlank()) {
            String query = page.search().toLowerCase(Locale.ROOT);
            if (!contains(row.messageKey(), query)
                    && !contains(row.namespace(), query)
                    && !contains(row.sourceMessage(), query)
                    && !contains(row.currentValue(), query)) return false;
        }
        Map<String, String> fields =
                Map.of(
                        "messageKey",
                        row.messageKey(),
                        "namespace",
                        row.namespace(),
                        "sourceMessage",
                        row.sourceMessage(),
                        "currentValue",
                        row.currentValue(),
                        "valueSource",
                        row.valueSource(),
                        "enabled",
                        Boolean.toString(row.enabled()),
                        "builtIn",
                        Boolean.toString(row.builtIn()));
        for (var filter : page.filters().entrySet()) {
            String key = filter.getKey();
            if (key.endsWith(".from") || key.endsWith(".to")) continue;
            String actual = fields.get(key);
            if (actual == null) continue;
            String expected = filter.getValue();
            String mode = page.filterModes().getOrDefault(key, "contains");
            if ("exact".equals(mode)
                    ? !actual.equalsIgnoreCase(expected)
                    : !contains(actual, expected.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private LocalizationLocaleRepository.Language requireLocale(String raw) {
        String value = canonicalLocale(raw);
        return localeRepository
                .find(value)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "localizationLocaleInvalid"));
    }

    public static String canonicalLocale(String raw) {
        if (raw == null
                || raw.isBlank()
                || raw.length() > 63
                || raw.contains("_")
                || raw.codePoints().anyMatch(Character::isISOControl)) {
            fail(HttpStatus.BAD_REQUEST, "localizationLocaleInvalid");
        }
        try {
            String canonical =
                    new Locale.Builder().setLanguageTag(raw.strip()).build().toLanguageTag();
            if (canonical.equals("und") || !canonical.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
                fail(HttpStatus.BAD_REQUEST, "localizationLocaleInvalid");
            return canonical;
        } catch (java.util.IllformedLocaleException invalid) {
            fail(HttpStatus.BAD_REQUEST, "localizationLocaleInvalid");
            return "en";
        }
    }

    private Values values(Input input) {
        if (input == null) fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        String key = validKey(input.messageKey());
        String namespace =
                input.namespace() == null || input.namespace().isBlank()
                        ? "custom"
                        : input.namespace().strip();
        if (!NAMESPACE.matcher(namespace).matches())
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        String defaultMessage = validCopy(input.defaultMessage());
        var cleanTranslations = new LinkedHashMap<String, String>();
        if (input.translations() != null) {
            if (input.translations().size() > localeRepository.listEnabled().size())
                fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
            for (var entry : input.translations().entrySet())
                cleanTranslations.put(
                        requireLocale(entry.getKey()).locale(), validCopy(entry.getValue()));
        }
        return new Values(
                key,
                namespace,
                defaultMessage,
                Map.copyOf(cleanTranslations),
                input.enabled() == null || input.enabled());
    }

    private Values values(ImportEntry input) {
        if (input == null) fail(HttpStatus.BAD_REQUEST, "invalidLocalizationImport");
        return values(
                new Input(
                        input.messageKey(),
                        input.namespace(),
                        input.defaultMessage(),
                        input.translations(),
                        true,
                        null));
    }

    private static String validKey(String input) {
        String value = clean(input);
        if (value == null || !MESSAGE_KEY.matcher(value).matches())
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        return value;
    }

    private static String validCopy(String input) {
        String value = clean(input);
        if (value == null
                || value.length() > 2_000
                || hasUnsupportedControl(value)
                || HTML_TAG.matcher(value).find()) {
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        }
        return value;
    }

    private static String validBundleCopy(String input) {
        if (input == null
                || input.length() > 2_000
                || Normalizer.normalize(input, Normalizer.Form.NFKC).strip().isEmpty()
                || hasUnsupportedControl(input)
                || HTML_TAG.matcher(input).find()) {
            fail(HttpStatus.BAD_REQUEST, "invalidLocalization");
        }
        return input;
    }

    private static boolean hasUnsupportedControl(String value) {
        return value.codePoints()
                .anyMatch(
                        codePoint ->
                                Character.isISOControl(codePoint)
                                        && codePoint != '\n'
                                        && codePoint != '\r'
                                        && codePoint != '\t');
    }

    private static String validDisplayName(String input) {
        String value = clean(input);
        if (value == null
                || value.length() > 100
                || value.codePoints().anyMatch(Character::isISOControl)
                || HTML_TAG.matcher(value).find()) {
            fail(HttpStatus.BAD_REQUEST, "invalidLocalizationBundle");
        }
        return value;
    }

    private void advance(Set<String> locales, String actor) {
        for (String value : locales) repository.publish(value, actor);
    }

    private int customCount() {
        return (int)
                repository.allActive().stream()
                        .filter(row -> !builtins.hasKey(row.messageKey()) && row.enabled())
                        .count();
    }

    private void writeGuard(String actor, String permission) {
        guard.require(actor, permission);
        guard.requireGlobal(actor, permission);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static Map<String, String> merged(
            Map<String, String> current, String locale, String value) {
        var copy = new LinkedHashMap<>(current);
        copy.put(locale, value);
        return copy;
    }

    private static UUID localeAuditId(String locale) {
        return UUID.nameUUIDFromBytes(
                ("localization-locale:" + locale).getBytes(StandardCharsets.UTF_8));
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }
}
