package com.lumira.platform.sensitive;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.ContentPolicyPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import com.lumira.platform.modules.ModuleRuntime;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SensitiveWordService {
    public static final String ACCESS = "platform:sensitive-words:access";
    public static final String READ = "platform:sensitive-words:read";
    public static final String CREATE = "platform:sensitive-words:create";
    public static final String UPDATE = "platform:sensitive-words:update";
    public static final String DELETE = "platform:sensitive-words:delete";
    public static final String POLICY_UPDATE = "platform:sensitive-words:policy-update";
    public static final String IMPORT = "platform:sensitive-words:import";
    public static final String CATEGORY_DICTIONARY = "sensitive_word_category";
    private static final int MAX_WORD_LENGTH = 128;
    private static final int MAX_CATEGORY_LENGTH = 64;
    private static final int MAX_IMPORT_WORDS = 5_000;
    private static final int MAX_CHECK_LENGTH = 200_000;
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> ACTIONS = Set.of("BLOCK", "LOG_ONLY");

    public record Input(
            String word,
            String category,
            String severity,
            String action,
            Boolean enabled,
            Long version) {}

    public record ImportRequest(
            List<String> words, String category, String severity, String action) {}

    public record ImportResult(int total, int imported, int duplicated, int invalid) {}

    public record CategoryOption(String value, String label, int sortOrder) {}

    public record Match(String word, String severity, String action, String maskedWord) {}

    public record CheckResult(
            boolean hit,
            boolean blocked,
            int totalMatchCount,
            boolean blockingMatchShown,
            List<Match> matches) {
        public CheckResult {
            matches = List.copyOf(matches == null ? List.of() : matches);
        }
    }

    private final SensitiveWordRepository repository;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ModuleRuntime modules;
    private final DictionaryLookupPort dictionaries;

    private record CachedMatcher(long revision, SensitiveWordMatcher matcher) {}

    private final AtomicReference<CachedMatcher> matcher = new AtomicReference<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SensitiveWordService(
            SensitiveWordRepository repository,
            AdministrationPort guard,
            AuditPort audit,
            ModuleRuntime modules,
            DictionaryLookupPort dictionaries) {
        this.repository = repository;
        this.guard = guard;
        this.audit = audit;
        this.modules = modules;
        this.dictionaries = dictionaries;
    }

    /** Focused tests can exercise matching without constructing the lifecycle store. */
    public SensitiveWordService(
            SensitiveWordRepository repository, AdministrationPort guard, AuditPort audit) {
        this(
                repository,
                guard,
                audit,
                moduleId -> true,
                new DictionaryLookupPort() {
                    @Override
                    public List<PolicyItem> policyItems(java.util.Collection<String> codes) {
                        return List.of();
                    }

                    @Override
                    public boolean isEnabled(String code) {
                        return false;
                    }
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<SensitiveWordRepository.Word> list(
            PageRequest page,
            String word,
            String category,
            String severity,
            String action,
            Boolean enabled,
            String actor) {
        requireGlobalOperation(actor, READ);
        String wordFilter = textFilter(word, MAX_WORD_LENGTH);
        String requestedCategory = categoryFilter(category);
        String categoryFilter =
                requestedCategory == null ? null : enabledCategory(requestedCategory, null);
        String severityFilter = enumFilter(severity, SEVERITIES);
        String actionFilter = enumFilter(action, ACTIONS);
        return new PageResult<>(
                repository.list(
                        page, wordFilter, categoryFilter, severityFilter, actionFilter, enabled),
                repository.count(
                        page, wordFilter, categoryFilter, severityFilter, actionFilter, enabled),
                page.page(),
                page.pageSize());
    }

    @Transactional(readOnly = true)
    public SensitiveWordRepository.Word find(UUID id, String actor) {
        requireGlobalOperation(actor, READ);
        return repository.find(id);
    }

    @Transactional
    public SensitiveWordRepository.Word create(Input input, String actor) {
        writeGuard(actor, CREATE);
        if (input == null) fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        var values = normalize(input, enabledCategory(input.category(), "DEFAULT"));
        if (repository.exists(values.normalizedWord(), null))
            fail(HttpStatus.CONFLICT, "duplicateSensitiveWord");
        UUID id = UUID.randomUUID();
        try {
            repository.create(
                    id,
                    values.word(),
                    values.normalizedWord(),
                    values.category(),
                    values.severity(),
                    values.action(),
                    values.enabled(),
                    actor);
        } catch (DuplicateKeyException error) {
            fail(HttpStatus.CONFLICT, "duplicateSensitiveWord");
        }
        audit.append(actor, "sensitiveWord.created", id);
        return repository.find(id);
    }

    @Transactional
    public SensitiveWordRepository.Word update(UUID id, Input input, String actor) {
        writeGuard(actor, UPDATE);
        var current = repository.find(id);
        if (input == null || input.version() == null || input.version() != current.version())
            fail(HttpStatus.CONFLICT, "staleRecord");
        String category =
                Objects.equals(input.category(), current.category())
                        ? current.category()
                        : enabledCategory(input.category(), "DEFAULT");
        var values = normalize(input, category);
        if (repository.exists(values.normalizedWord(), id))
            fail(HttpStatus.CONFLICT, "duplicateSensitiveWord");
        try {
            if (repository.update(
                            id,
                            input.version(),
                            values.word(),
                            values.normalizedWord(),
                            values.category(),
                            values.severity(),
                            values.action(),
                            values.enabled(),
                            actor)
                    != 1) {
                fail(HttpStatus.CONFLICT, "staleRecord");
            }
        } catch (DuplicateKeyException error) {
            fail(HttpStatus.CONFLICT, "duplicateSensitiveWord");
        }
        audit.append(actor, "sensitiveWord.updated", id);
        return repository.find(id);
    }

    @Transactional
    public SensitiveWordRepository.Word setEnabled(
            UUID id, long version, boolean enabled, String actor) {
        writeGuard(actor, UPDATE);
        repository.find(id);
        if (repository.setEnabled(id, version, enabled, actor) != 1)
            fail(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, enabled ? "sensitiveWord.enabled" : "sensitiveWord.disabled", id);
        return repository.find(id);
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        writeGuard(actor, DELETE);
        repository.find(id);
        if (repository.softDelete(id, version, actor) != 1)
            fail(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "sensitiveWord.deleted", id);
    }

    @Transactional
    public ImportResult importWords(ImportRequest input, String actor) {
        writeGuard(actor, IMPORT);
        if (input == null || input.words() == null || input.words().size() > MAX_IMPORT_WORDS)
            fail(HttpStatus.BAD_REQUEST, "invalidSensitiveImport");
        String category = enabledCategory(input.category(), "IMPORTED");
        String severity = severity(input.severity());
        String action = action(input.action());
        int invalid = 0;
        int duplicated = 0;
        int imported = 0;
        var unique = new LinkedHashMap<String, String>();
        for (String candidate : input.words()) {
            String word;
            try {
                word = word(candidate);
            } catch (ResponseStatusException error) {
                invalid++;
                continue;
            }
            String normalized = SensitiveWordMatcher.normalizeForMatch(word);
            if (normalized.isBlank() || normalized.length() > MAX_WORD_LENGTH) {
                invalid++;
            } else if (unique.putIfAbsent(normalized, word) != null) {
                duplicated++;
            }
        }
        for (var entry : unique.entrySet()) {
            try {
                if (repository.insertIfAbsent(
                                UUID.randomUUID(),
                                entry.getValue(),
                                entry.getKey(),
                                category,
                                severity,
                                action,
                                actor)
                        == 1) imported++;
                else duplicated++;
            } catch (DuplicateKeyException error) {
                duplicated++;
            }
        }
        if (imported > 0) {
            audit.append(actor, "sensitiveWord.imported", UUID.randomUUID());
        }
        return new ImportResult(input.words().size(), imported, duplicated, invalid);
    }

    @Transactional(readOnly = true)
    public CheckResult check(String text, String actor) {
        requireGlobalOperation(actor, READ);
        return scan(text);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<CategoryOption> categories(String actor) {
        requireGlobalOperation(actor, READ);
        return categoryItems().stream()
                .map(item -> new CategoryOption(item.value(), item.label(), item.sortOrder()))
                .toList();
    }

    /** Used by public platform submissions without granting dictionary access. */
    public CheckResult scanSubmission(String text) {
        return scan(text);
    }

    public ContentPolicyPort.ScanResult evaluate(String text) {
        if (!modules.isEnabled("sensitive-words")) return new ContentPolicyPort.ScanResult(false);
        return new ContentPolicyPort.ScanResult(scan(text).blocked());
    }

    private CheckResult scan(String text) {
        if (text == null || text.isBlank())
            return new CheckResult(false, false, 0, false, List.of());
        if (text.length() > MAX_CHECK_LENGTH) fail(HttpStatus.BAD_REQUEST, "sensitiveTextTooLong");
        var scan = matcher().scan(text);
        var matches =
                scan.matches().stream()
                        .map(
                                item ->
                                        new Match(
                                                item.word(),
                                                item.severity(),
                                                item.action(),
                                                mask(item.word())))
                        .toList();
        return new CheckResult(
                scan.totalMatchCount() > 0,
                scan.blocked(),
                scan.totalMatchCount(),
                scan.blockingMatchDisplayed(),
                matches);
    }

    private SensitiveWordMatcher matcher() {
        CachedMatcher current = matcher.get();
        var snapshot = repository.matcherSnapshot(current == null ? -1 : current.revision());
        if (current != null && current.revision() == snapshot.revision()) return current.matcher();
        var rebuilt =
                new SensitiveWordMatcher(
                        snapshot.entries().stream()
                                .map(
                                        entry ->
                                                new SensitiveWordMatcher.Entry(
                                                        entry.word(),
                                                        entry.normalizedWord(),
                                                        entry.severity(),
                                                        entry.action()))
                                .toList());
        matcher.set(new CachedMatcher(snapshot.revision(), rebuilt));
        return rebuilt;
    }

    private void writeGuard(String actor, String permission) {
        guard.require(actor, ACCESS);
        guard.requireGlobal(actor, ACCESS);
        guard.require(actor, permission);
        guard.requireGlobal(actor, permission);
    }

    private void requireGlobalOperation(String actor, String permission) {
        guard.require(actor, ACCESS);
        guard.requireGlobal(actor, ACCESS);
        guard.require(actor, permission);
        guard.requireGlobal(actor, permission);
    }

    private Normalized normalize(Input input, String categoryValue) {
        if (input == null) fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        String normalizedWord = word(input.word());
        String matchKey = SensitiveWordMatcher.normalizeForMatch(normalizedWord);
        if (matchKey.isBlank() || matchKey.length() > MAX_WORD_LENGTH)
            fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        return new Normalized(
                normalizedWord,
                matchKey,
                categoryValue,
                severity(input.severity()),
                action(input.action()),
                input.enabled() == null || input.enabled());
    }

    private String word(String value) {
        if (value == null) fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (normalized.isBlank()
                || normalized.length() > MAX_WORD_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl))
            fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        return normalized;
    }

    private String category(String value) {
        String normalized =
                value == null || value.isBlank()
                        ? "DEFAULT"
                        : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (normalized.isBlank()
                || normalized.codePointCount(0, normalized.length()) > MAX_CATEGORY_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl))
            fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        return normalized;
    }

    private String enabledCategory(String value, String defaultValue) {
        String normalized = value == null || value.isBlank() ? defaultValue : category(value);
        if (normalized == null) fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");

        var values = categoryItems().stream().map(DictionaryLookupPort.PolicyItem::value).toList();
        String exactValue = value == null ? null : value.strip();
        return values.stream()
                .filter(candidate -> Objects.equals(candidate, exactValue))
                .findFirst()
                .or(
                        () ->
                                values.stream()
                                        .filter(candidate -> Objects.equals(candidate, normalized))
                                        .findFirst())
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "invalidSensitiveWord"));
    }

    private List<DictionaryLookupPort.PolicyItem> categoryItems() {
        return dictionaries.policyItems(List.of(CATEGORY_DICTIONARY));
    }

    private String severity(String value) {
        String normalized =
                value == null || value.isBlank()
                        ? "MEDIUM"
                        : value.strip().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(normalized)) fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        return normalized;
    }

    private String action(String value) {
        String normalized =
                value == null || value.isBlank() ? "BLOCK" : value.strip().toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        return normalized;
    }

    private String textFilter(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        }
        return normalized;
    }

    private String categoryFilter(String value) {
        if (value == null || value.isBlank()) return null;
        return category(value);
    }

    private String enumFilter(String value, Set<String> supported) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!supported.contains(normalized)) fail(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        return normalized;
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) return "***";
        if (value.length() <= 2) return "*".repeat(value.length());
        return value.charAt(0) + "*".repeat(value.length() - 2) + value.charAt(value.length() - 1);
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }

    private record Normalized(
            String word,
            String normalizedWord,
            String category,
            String severity,
            String action,
            boolean enabled) {}
}
