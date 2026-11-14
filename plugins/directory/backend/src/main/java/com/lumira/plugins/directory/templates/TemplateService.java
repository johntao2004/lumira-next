package com.lumira.plugins.directory.templates;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DocumentWorkerClient;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.TemplateUserDataPort;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class TemplateService {
    public static final int MAX_TEMPLATE_BYTES = 10 * 1024 * 1024;
    private static final Pattern TAG = Pattern.compile("[\\p{L}\\p{N}_. -]{1,40}");

    public record Summary(
            UUID id,
            String name,
            String category,
            List<String> tags,
            String status,
            long version,
            Integer draftVersion,
            Integer publishedVersion,
            java.time.OffsetDateTime updatedAt) {}

    public record VersionInfo(
            UUID id,
            int version,
            String status,
            String filename,
            String sha256,
            List<DocumentWorkerClient.Slot> slots,
            List<String> placeholders,
            Map<String, String> bindings,
            String createdBy,
            java.time.OffsetDateTime createdAt) {}

    public record Detail(
            UUID id,
            String name,
            String category,
            List<String> tags,
            String status,
            long version,
            Integer draftVersion,
            Integer publishedVersion,
            VersionInfo currentVersion) {}

    record RenderInput(Map<String, String> slotBindings, Map<String, String> values) {}

    private static final TypeReference<List<DocumentWorkerClient.Slot>> SLOT_LIST =
            new TypeReference<>() {};
    private final TemplateRepository repository;
    private final TemplateUserDataPort userData;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ObjectMapper mapper;

    public TemplateService(
            TemplateRepository repository,
            TemplateUserDataPort userData,
            AdministrationPort guard,
            AuditPort audit,
            ObjectMapper mapper) {
        this.repository = repository;
        this.userData = userData;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Summary> list(
            int page,
            int pageSize,
            String search,
            String category,
            String tag,
            String status,
            String actor) {
        guard.requirePage(actor, PlatformPageId.PRINT_TEMPLATES);
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPagination");
        long total = repository.count(search, category, tag, status);
        var items =
                repository.list(page, pageSize, search, category, tag, status).stream()
                        .map(this::summary)
                        .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail find(UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.PRINT_TEMPLATES);
        var template = repository.find(id, false);
        Integer current =
                template.draftVersion() != null
                        ? template.draftVersion()
                        : template.publishedVersion();
        return detail(template, current);
    }

    @Transactional(readOnly = true)
    public List<VersionInfo> versions(UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.PRINT_TEMPLATES);
        repository.find(id, false);
        return repository.versions(id).stream().map(this::versionInfo).toList();
    }

    @Transactional(readOnly = true)
    public List<TemplateUserDataPort.Field> fields(String actor) {
        guard.requirePage(actor, PlatformPageId.PRINT_TEMPLATES);
        return userData.readableFields(actor);
    }

    @Transactional
    public Detail create(
            String name,
            String category,
            List<String> tags,
            String bindingMode,
            String filename,
            byte[] source,
            String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.TEMPLATE_CREATE);
        requireNotRolePreview();
        validateFile(filename, source);
        validateBindingMode(bindingMode);
        var safeName = required(name, 160, "invalidTemplate");
        var safeCategory = optional(category, 80, "invalidTemplate");
        var safeTags = normalizeTags(tags);
        var inspection = inspect(filename, source);
        var available =
                userData.readableFields(actor).stream()
                        .map(TemplateUserDataPort.Field::key)
                        .collect(java.util.stream.Collectors.toSet());
        var bindings =
                bindingMode.equals("auto")
                        ? autoBindings(inspection, available)
                        : Map.<String, String>of();
        var id = UUID.randomUUID();
        int version =
                repository.create(
                        id,
                        safeName,
                        safeCategory,
                        safeTags,
                        actor,
                        filename.strip(),
                        source,
                        sha256(source),
                        inspectionMap(inspection),
                        bindings);
        audit.append(actor, "template.create", id);
        return detail(repository.find(id, false), version);
    }

    @Transactional
    public Detail updateMetadata(
            UUID id, long version, String name, String category, List<String> tags, String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.TEMPLATE_UPDATE);
        requireNotRolePreview();
        repository.updateMetadata(
                id,
                version,
                required(name, 160, "invalidTemplate"),
                optional(category, 80, "invalidTemplate"),
                normalizeTags(tags));
        audit.append(actor, "template.metadata.update", id);
        var template = repository.find(id, false);
        return detail(
                template,
                template.draftVersion() != null
                        ? template.draftVersion()
                        : template.publishedVersion());
    }

    @Transactional
    public Detail newDraft(UUID id, long version, String filename, byte[] source, String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.TEMPLATE_UPDATE);
        requireNotRolePreview();
        validateFile(filename, source);
        var inspection = inspect(filename, source);
        int next =
                repository.appendDraft(
                        id,
                        version,
                        actor,
                        filename.strip(),
                        source,
                        sha256(source),
                        inspectionMap(inspection));
        audit.append(actor, "template.source.draft", id);
        return detail(repository.find(id, false), next);
    }

    @Transactional
    public Detail updateBindings(
            UUID id, long templateVersion, Map<String, String> bindings, String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.TEMPLATE_UPDATE);
        requireNotRolePreview();
        var template = repository.find(id, false);
        if (template.version() != templateVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        int draftVersion =
                template.draftVersion() == null
                        ? repository.clonePublishedDraft(id, templateVersion, actor)
                        : template.draftVersion();
        template = repository.find(id, false);
        var templateVersionData = repository.version(id, draftVersion);
        if (!"draft".equals(templateVersionData.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        var allowedFields =
                userData.readableFields(actor).stream()
                        .map(TemplateUserDataPort.Field::key)
                        .collect(java.util.stream.Collectors.toSet());
        validateBindings(templateVersionData, bindings, allowedFields);
        repository.updateBindings(
                id, template.version(), draftVersion, bindings == null ? Map.of() : bindings);
        audit.append(actor, "template.bindings.update", id);
        return detail(repository.find(id, false), draftVersion);
    }

    @Transactional
    public Detail publish(UUID id, long version, String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.TEMPLATE_PUBLISH);
        requireNotRolePreview();
        var template = repository.find(id, false);
        if (template.version() != version || template.draftVersion() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        var draft = repository.version(id, template.draftVersion());
        var fields =
                userData.readableFields(actor).stream()
                        .map(TemplateUserDataPort.Field::key)
                        .collect(java.util.stream.Collectors.toSet());
        validateBindings(draft, draft.bindings(), fields);
        for (String placeholder : inspection(draft).placeholders()) {
            String field = draft.bindings().getOrDefault(placeholder, placeholder);
            if (!fields.contains(field))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "templateBindingIncomplete");
        }
        repository.publish(id, version);
        audit.append(actor, "template.publish", id);
        template = repository.find(id, false);
        return detail(template, template.publishedVersion());
    }

    @Transactional
    public Detail deactivate(UUID id, long version, String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.TEMPLATE_UPDATE);
        requireNotRolePreview();
        repository.deactivate(id, version);
        audit.append(actor, "template.deactivate", id);
        var template = repository.find(id, false);
        return detail(template, template.publishedVersion());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] preview(
            UUID id,
            int requestedVersion,
            UUID userId,
            Map<String, String> overrides,
            String actor) {
        guard.requireOperation(actor, PlatformOperation.TEMPLATE_USE);
        requireNotRolePreview();
        if (userId != null) guard.requireOperationUser(actor, userId, PlatformOperation.USER_PRINT);
        var template = repository.find(id, false);
        int versionNo =
                requestedVersion > 0
                        ? requestedVersion
                        : template.draftVersion() != null
                                ? template.draftVersion()
                                : template.publishedVersion() == null
                                        ? 0
                                        : template.publishedVersion();
        if (versionNo <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "templateNotPublished");
        var snapshot = repository.version(id, versionNo);
        if ("draft".equals(snapshot.state())) {
            guard.requireOperation(actor, PlatformOperation.TEMPLATE_UPDATE);
        } else if (!"published".equals(snapshot.state())
                || !Integer.valueOf(versionNo).equals(template.publishedVersion())
                || "inactive".equals(template.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "templateNotPublished");
        }
        var values = new LinkedHashMap<String, String>();
        if (userId != null) values.putAll(userData.readValues(userId, actor));
        applyOverrides(values, overrides, actor);
        return render(snapshot, values, actor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderPublished(UUID id, int versionNo, UUID userId, String actor) {
        guard.requireOperation(actor, PlatformOperation.TEMPLATE_USE);
        var template = repository.find(id, false);
        if (!"published".equals(template.status())
                || !Integer.valueOf(versionNo).equals(template.publishedVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "templateNotPublished");
        var version = repository.version(id, versionNo);
        return render(version, userData.readValues(userId, actor), actor);
    }

    private byte[] render(
            TemplateRepository.Version version, Map<String, String> sourceValues, String actor) {
        var fields =
                userData.readableFields(actor).stream()
                        .map(TemplateUserDataPort.Field::key)
                        .collect(java.util.stream.Collectors.toSet());
        var inspection = inspection(version);
        var bindings = version.bindings();
        validateBindings(version, bindings, fields);
        var renderInput = renderInput(inspection, bindings, sourceValues, fields);
        try {
            return DocumentWorkerClient.pdf(
                    version.filename(),
                    version.source(),
                    renderInput.slotBindings(),
                    renderInput.values());
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "templateRenderFailed", failure);
        }
    }

    static RenderInput renderInput(
            DocumentWorkerClient.Inspection inspection,
            Map<String, String> bindings,
            Map<String, String> sourceValues,
            Set<String> readableFields) {
        var resultValues = new LinkedHashMap<String, String>();
        var slotBindings = new LinkedHashMap<String, String>();
        int slotIndex = 0;
        for (var slot : inspection.slots()) {
            String field = bindings.get(slot.key());
            if (field == null) continue;
            String alias;
            do {
                alias = "slotValue" + slotIndex++;
            } while (inspection.placeholders().contains(alias) || resultValues.containsKey(alias));
            slotBindings.put(slot.key(), alias);
            resultValues.put(alias, sourceValues.getOrDefault(field, ""));
        }
        for (String placeholder : inspection.placeholders()) {
            String sourceField = bindings.getOrDefault(placeholder, placeholder);
            if (!readableFields.contains(sourceField))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fieldNotReadable");
            resultValues.put(placeholder, sourceValues.getOrDefault(sourceField, ""));
        }
        return new RenderInput(Map.copyOf(slotBindings), Map.copyOf(resultValues));
    }

    private void applyOverrides(
            Map<String, String> values, Map<String, String> overrides, String actor) {
        if (overrides == null || overrides.isEmpty()) return;
        Set<String> allowed =
                userData.readableFields(actor).stream()
                        .map(TemplateUserDataPort.Field::key)
                        .collect(java.util.stream.Collectors.toSet());
        if (overrides.size() > 1_000
                || overrides.entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey() == null
                                                || e.getValue() == null
                                                || e.getValue().length() > 20_000
                                                || !allowed.contains(e.getKey())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fieldNotReadable");
        values.putAll(overrides);
    }

    private void validateBindings(
            TemplateRepository.Version version,
            Map<String, String> bindings,
            Set<String> allowedFields) {
        var inspection = inspection(version);
        Set<String> targets = new java.util.HashSet<>(inspection.placeholders());
        inspection.slots().forEach(slot -> targets.add(slot.key()));
        if (bindings == null) return;
        if (bindings.size() > 500
                || bindings.entrySet().stream()
                        .anyMatch(
                                entry ->
                                        entry.getKey() == null
                                                || entry.getValue() == null
                                                || !targets.contains(entry.getKey())
                                                || !allowedFields.contains(entry.getValue())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidTemplateBinding");
    }

    private Detail detail(TemplateRepository.Summary template, Integer version) {
        return new Detail(
                template.id(),
                template.name(),
                template.category(),
                template.tags(),
                template.status(),
                template.version(),
                template.draftVersion(),
                template.publishedVersion(),
                version == null ? null : versionInfo(repository.version(template.id(), version)));
    }

    private Summary summary(TemplateRepository.Summary value) {
        return new Summary(
                value.id(),
                value.name(),
                value.category(),
                value.tags(),
                value.status(),
                value.version(),
                value.draftVersion(),
                value.publishedVersion(),
                value.updatedAt());
    }

    private VersionInfo versionInfo(TemplateRepository.Version value) {
        var inspection = inspection(value);
        return new VersionInfo(
                value.templateId(),
                value.version(),
                value.state(),
                value.filename(),
                value.sha256(),
                inspection.slots(),
                inspection.placeholders(),
                value.bindings(),
                value.createdBy(),
                value.createdAt());
    }

    private DocumentWorkerClient.Inspection inspection(TemplateRepository.Version value) {
        try {
            return mapper.convertValue(
                    value.inspection(), new TypeReference<DocumentWorkerClient.Inspection>() {});
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "templateMetadataInvalid", invalid);
        }
    }

    private Map<String, Object> inspectionMap(DocumentWorkerClient.Inspection inspection) {
        var map = new LinkedHashMap<String, Object>();
        map.put("slots", inspection.slots());
        map.put("placeholders", inspection.placeholders());
        return map;
    }

    private DocumentWorkerClient.Inspection inspect(String filename, byte[] bytes) {
        try {
            return DocumentWorkerClient.inspect(filename, bytes);
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupportedFile", failure);
        }
    }

    private static Map<String, String> autoBindings(
            DocumentWorkerClient.Inspection inspection, Set<String> fields) {
        var map = new LinkedHashMap<String, String>();
        for (String placeholder : inspection.placeholders())
            if (fields.contains(placeholder)) map.put(placeholder, placeholder);
        return Map.copyOf(map);
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) return List.of();
        if (tags.size() > 30)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidTemplate");
        var normalized = new java.util.LinkedHashSet<String>();
        for (String tag : tags) {
            if (tag == null || !TAG.matcher(tag.strip()).matches())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidTemplate");
            normalized.add(tag.strip());
        }
        return List.copyOf(normalized);
    }

    private static String required(String value, int max, String code) {
        if (value == null || value.isBlank() || value.strip().length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
        return value.strip();
    }

    private static String optional(String value, int max, String code) {
        if (value == null || value.isBlank()) return null;
        if (value.strip().length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
        return value.strip();
    }

    private static void validateFile(String filename, byte[] bytes) {
        if (filename == null
                || bytes == null
                || bytes.length == 0
                || bytes.length > MAX_TEMPLATE_BYTES
                || !(filename.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")
                        || filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")))
            throw new ResponseStatusException(
                    bytes != null && bytes.length > MAX_TEMPLATE_BYTES
                            ? HttpStatus.PAYLOAD_TOO_LARGE
                            : HttpStatus.BAD_REQUEST,
                    "unsupportedFile");
    }

    private static void validateBindingMode(String mode) {
        if (!"auto".equals(mode) && !"manual".equals(mode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidBindingMode");
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireNotRolePreview() {
        var request = com.lumira.platform.identity.api.AuthenticationRequestContext.current();
        if (request != null
                && request.authorization() != null
                && request.authorization().previewActive())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
}
