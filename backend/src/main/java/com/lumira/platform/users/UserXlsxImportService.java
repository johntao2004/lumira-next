package com.lumira.platform.users;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DocumentWorkerClient;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.TemplateUserDataPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import com.lumira.platform.identity.api.UserProfileValuesPort;
import com.lumira.platform.roles.RoleService;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** XLSX parsing, preview planning, and atomic commit of account rows. */
@Service
public class UserXlsxImportService {
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_DATA_ROWS = 500;
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}");

    public record Column(String name, int index) {}

    /**
     * Internal persisted plan; version maps make stale role and organization assignments
     * detectable.
     */
    public record RowPlan(
            int rowNumber,
            String action,
            UUID matchedUserId,
            Long expectedUserVersion,
            String username,
            Map<String, String> changes,
            Map<String, Long> roleVersions,
            Map<String, Long> organizationVersions,
            List<String> errors) {
        public RowPlan {
            changes = Map.copyOf(changes == null ? Map.of() : changes);
            roleVersions = Map.copyOf(roleVersions == null ? Map.of() : roleVersions);
            organizationVersions =
                    Map.copyOf(organizationVersions == null ? Map.of() : organizationVersions);
            errors = List.copyOf(errors == null ? List.of() : errors);
        }
    }

    public record ChangeView(String before, String after) {}

    public record PreviewRow(
            int rowNumber,
            String action,
            UUID matchedUserId,
            Long expectedUserVersion,
            Map<String, ChangeView> changes,
            List<String> errors) {}

    public record PreviewView(
            UUID previewId,
            long previewVersion,
            String fileHash,
            OffsetDateTime expiresAt,
            List<PreviewRow> rows) {}

    public record CommitRequest(long previewVersion, String idempotencyKey) {}

    public record TaskRow(int rowNumber, String action, String username, List<String> errors) {}

    public record TaskResult(
            String status,
            int created,
            int updated,
            int failed,
            List<TaskRow> rows,
            String errorCode) {
        public TaskResult {
            rows = List.copyOf(rows == null ? List.of() : rows);
        }
    }

    public record TaskView(
            UUID taskId,
            String status,
            int created,
            int updated,
            int failed,
            List<TaskRow> rows,
            String errorCode) {}

    private static final class RowFailure extends RuntimeException {
        private final int rowNumber;
        private final String code;

        private RowFailure(int rowNumber, String code, Throwable cause) {
            super(code, cause);
            this.rowNumber = rowNumber;
            this.code = code;
        }
    }

    private final UserImportStore store;
    private final UserRepository usersRepository;
    private final UserService users;
    private final UserEditorService editor;
    private final UserProfileValuesPort profileValues;
    private final ProfileSchemaPort profileSchema;
    private final TemplateUserDataPort userData;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final JdbcClient jdbc;
    private final RoleService roleDelegation;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate atomic;

    public UserXlsxImportService(
            UserImportStore store,
            UserRepository usersRepository,
            UserService users,
            UserEditorService editor,
            UserProfileValuesPort profileValues,
            ProfileSchemaPort profileSchema,
            TemplateUserDataPort userData,
            AdministrationPort guard,
            AuditPort audit,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this(
                store,
                usersRepository,
                users,
                editor,
                profileValues,
                profileSchema,
                userData,
                guard,
                audit,
                jdbc,
                transactionManager,
                null);
    }

    @Autowired
    public UserXlsxImportService(
            UserImportStore store,
            UserRepository usersRepository,
            UserService users,
            UserEditorService editor,
            UserProfileValuesPort profileValues,
            ProfileSchemaPort profileSchema,
            TemplateUserDataPort userData,
            AdministrationPort guard,
            AuditPort audit,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RoleService roleDelegation) {
        this.store = store;
        this.usersRepository = usersRepository;
        this.users = users;
        this.editor = editor;
        this.profileValues = profileValues;
        this.profileSchema = profileSchema;
        this.userData = userData;
        this.guard = guard;
        this.audit = audit;
        this.jdbc = jdbc;
        this.roleDelegation = roleDelegation;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.atomic = new TransactionTemplate(transactionManager);
        this.atomic.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.atomic.setTimeout(600);
    }

    public List<Column> columns(String filename, byte[] bytes, String actor) {
        requireImport(actor);
        validateWorkbookFile(filename, bytes);
        var rows = readWorkbook(bytes);
        if (rows.isEmpty() || rows.getFirst().isEmpty()) badRequest("emptyWorkbook");
        var seen = new HashSet<String>();
        var result = new ArrayList<Column>();
        var header = rows.getFirst();
        for (int index = 0; index < header.size(); index++) {
            String name = cell(header, index).strip();
            if (name.isEmpty()) continue;
            if (name.length() > 200 || !seen.add(name)) badRequest("invalidWorkbookHeader");
            result.add(new Column(name, index));
        }
        if (result.isEmpty()) badRequest("invalidWorkbookHeader");
        return List.copyOf(result);
    }

    public List<TemplateUserDataPort.Field> fields(String actor) {
        requireImport(actor);
        return userData.importableFields(actor).stream()
                .filter(TemplateUserDataPort.Field::writable)
                .toList();
    }

    public byte[] standardTemplate(String actor) {
        var importFields = fields(actor);
        var keys = importFields.stream().map(TemplateUserDataPort.Field::key).toList();
        if (!keys.contains("username")) badRequest("forbidden");
        var rows = new ArrayList<List<String>>();
        rows.add(importFields.stream().map(TemplateUserDataPort.Field::key).toList());
        try {
            return DocumentWorkerClient.writeWorkbook(rows);
        } catch (ResponseStatusException failure) {
            throw failure;
        }
    }

    public PreviewView preview(
            String filename, byte[] bytes, String rawMapping, String mode, String actor) {
        requireImport(actor);
        rejectRolePreview();
        validateWorkbookFile(filename, bytes);
        String safeMode = mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("create", "update", "upsert").contains(safeMode))
            badRequest("invalidImportMode");
        Map<String, String> mapping = parseMapping(rawMapping);
        if (mapping.isEmpty() || mapping.size() > 256 || !mapping.containsValue("username"))
            badRequest("invalidImportMapping");

        var rows = readWorkbook(bytes);
        if (rows.isEmpty()) badRequest("emptyWorkbook");
        List<String> header = rows.getFirst();
        var headerIndex = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < header.size(); index++) {
            String name = cell(header, index).strip();
            if (name.isEmpty()) continue;
            if (headerIndex.putIfAbsent(name, index) != null) badRequest("invalidWorkbookHeader");
        }
        if (!headerIndex.keySet().containsAll(mapping.keySet())) badRequest("invalidImportMapping");
        validateMapping(mapping, actor);

        var plans = new ArrayList<RowPlan>();
        int dataNumber = 0;
        var rawRows = new ArrayList<Map.Entry<Integer, Map<String, String>>>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.stream().allMatch(value -> value == null || value.isBlank())) continue;
            dataNumber++;
            if (dataNumber > MAX_DATA_ROWS)
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "batchLimitExceeded");
            var values = new LinkedHashMap<String, String>();
            mapping.forEach(
                    (column, field) -> {
                        String value = cell(row, headerIndex.get(column));
                        if (value != null && !value.isBlank()) values.put(field, value.strip());
                    });
            rawRows.add(Map.entry(index + 1, values));
        }
        if (rawRows.isEmpty()) badRequest("emptyWorkbook");
        var usernameCounts = new HashMap<String, Integer>();
        rawRows.forEach(
                item -> {
                    String username = item.getValue().get("username");
                    if (username != null) usernameCounts.merge(username, 1, Integer::sum);
                });
        for (var item : rawRows)
            plans.add(planRow(item.getKey(), item.getValue(), safeMode, usernameCounts, actor));

        UUID id = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(24);
        long schemaVersion = profileSchema.read().version();
        String hash = sha256(bytes);
        String owner = owner(actor);
        store.createPreview(id, owner, hash, safeMode, 1, schemaVersion, plans, expiresAt);
        return previewView(
                new UserImportStore.Preview(
                        id, owner, hash, safeMode, 1, schemaVersion, plans, expiresAt),
                actor);
    }

    public byte[] errorsWorkbook(UUID previewId, String actor) {
        requireImport(actor);
        rejectRolePreview();
        var preview = store.preview(previewId, owner(actor), false);
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("rowNumber", "username", "errors"));
        for (var row : preview.rows())
            if (!row.errors().isEmpty())
                rows.add(
                        List.of(
                                Integer.toString(row.rowNumber()),
                                row.username() == null ? "" : row.username(),
                                String.join("; ", row.errors())));
        if (rows.size() == 1) badRequest("noImportErrors");
        return DocumentWorkerClient.writeWorkbook(rows);
    }

    public TaskView commit(UUID previewId, CommitRequest request, String actor) {
        requireImport(actor);
        rejectRolePreview();
        if (request == null
                || request.previewVersion() <= 0
                || request.idempotencyKey() == null
                || !request.idempotencyKey().equals(request.idempotencyKey().strip())
                || request.idempotencyKey().length() > 128
                || request.idempotencyKey().isBlank()) badRequest("invalidImportCommit");
        validateTaskAuthorization(actor);
        String owner = owner(actor);
        var existing = store.taskByIdempotency(owner, request.idempotencyKey());
        if (existing != null) {
            if (!existing.previewId().equals(previewId)
                    || existing.previewVersion() != request.previewVersion())
                conflict("idempotencyKeyConflict");
            return taskView(existing);
        }
        var preview = store.preview(previewId, owner, false);
        if (preview.previewVersion() != request.previewVersion()) conflict("importVersionConflict");
        if (!preview.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
            conflict("importPreviewExpired");
        UUID taskId = UUID.randomUUID();
        try {
            requiresNew.executeWithoutResult(
                    status -> store.createRunningTask(taskId, preview, request.idempotencyKey()));
        } catch (DataIntegrityViolationException duplicate) {
            existing = store.taskByIdempotency(owner, request.idempotencyKey());
            if (existing == null) throw duplicate;
            if (!existing.previewId().equals(previewId)
                    || existing.previewVersion() != request.previewVersion())
                conflict("idempotencyKeyConflict");
            return taskView(existing);
        }

        try {
            UserImportStore.Task completed =
                    atomic.execute(
                            status -> {
                                revalidatePreview(preview, actor);
                                if (preview.rows().stream()
                                        .anyMatch(row -> !row.errors().isEmpty()))
                                    throw new RowFailure(
                                            preview.rows().stream()
                                                    .filter(row -> !row.errors().isEmpty())
                                                    .findFirst()
                                                    .orElseThrow()
                                                    .rowNumber(),
                                            "importHasInvalidRows",
                                            null);
                                int created = 0;
                                int updated = 0;
                                var resultRows = new ArrayList<TaskRow>();
                                for (RowPlan row : preview.rows()) {
                                    try {
                                        if ("create".equals(row.action())) {
                                            applyRow(row, preview, actor);
                                            created++;
                                        } else if ("update".equals(row.action())) {
                                            applyRow(row, preview, actor);
                                            updated++;
                                        } else {
                                            throw new RowFailure(
                                                    row.rowNumber(), "invalidImportRow", null);
                                        }
                                        resultRows.add(
                                                new TaskRow(
                                                        row.rowNumber(),
                                                        row.action(),
                                                        row.username(),
                                                        List.of()));
                                    } catch (ResponseStatusException failure) {
                                        throw new RowFailure(
                                                row.rowNumber(), errorCode(failure), failure);
                                    }
                                }
                                var result =
                                        new TaskResult(
                                                "completed", created, updated, 0, resultRows, null);
                                store.completeTask(taskId, result);
                                audit.append(actor, "user.import.completed", taskId);
                                return store.task(taskId, owner);
                            });
            if (completed == null)
                throw new IllegalStateException("Import transaction returned no task");
            return taskView(completed);
        } catch (RuntimeException failure) {
            String code =
                    failure instanceof RowFailure rowFailure ? rowFailure.code : errorCode(failure);
            int failedRow = failure instanceof RowFailure rowFailure ? rowFailure.rowNumber : 0;
            var failedRows =
                    preview.rows().stream()
                            .map(
                                    row -> {
                                        String rowCode =
                                                failedRow == row.rowNumber()
                                                        ? code
                                                        : "batchRolledBack";
                                        return new TaskRow(
                                                row.rowNumber(),
                                                "failed",
                                                row.username(),
                                                List.of(rowCode));
                                    })
                            .toList();
            var result = new TaskResult("failed", 0, 0, preview.rows().size(), failedRows, code);
            requiresNew.executeWithoutResult(
                    status -> {
                        store.failTask(taskId, result);
                        audit.append(actor, "user.import.failed", taskId);
                    });
            return taskView(store.task(taskId, owner));
        }
    }

    public TaskView task(UUID taskId, String actor) {
        requireImport(actor);
        rejectRolePreview();
        return taskView(store.task(taskId, owner(actor)));
    }

    RowPlan planRow(
            int rowNumber,
            Map<String, String> values,
            String mode,
            Map<String, Integer> usernameCounts,
            String actor) {
        var errors = new LinkedHashSet<String>();
        String username = values.get("username");
        if (username == null || !USERNAME.matcher(username).matches())
            errors.add("invalidUsername");
        if (username != null && usernameCounts.getOrDefault(username, 0) > 1)
            errors.add("duplicateUsername");
        if (values.containsKey("enabled") && parseBoolean(values.get("enabled")) == null)
            errors.add("invalidBoolean");
        String displayName = values.get("displayName");
        if (displayName != null && !displayName.isBlank() && displayName.strip().length() > 200)
            errors.add("invalidProfileValues");
        var summary =
                username == null
                        ? null
                        : usersRepository.findByUsernameVisible(
                                username,
                                actor,
                                authorization(),
                                PlatformOperation.USER_IMPORT.permission(),
                                PlatformPageId.USERS.accessPermission());
        String action = summary == null ? "create" : "update";
        UUID matchedId = summary == null ? null : summary.id();
        Long expectedVersion = summary == null ? null : summary.version();
        if (summary == null
                && ("create".equals(mode) || "upsert".equals(mode))
                && !hasNonBlank(values.get("displayName"))) errors.add("missingDisplayName");
        if (summary != null && "create".equals(mode)) errors.add("usernameAlreadyExists");
        if (summary == null && "update".equals(mode)) errors.add("userNotFound");
        if (summary == null && ("create".equals(mode) || "upsert".equals(mode))) {
            try {
                guard.requireOperationActionGlobal(actor, PlatformOperation.USER_CREATE);
            } catch (ResponseStatusException denied) {
                errors.add("forbidden");
            }
        }
        if (summary != null) {
            try {
                authorizeChanges(summary.id(), values, actor);
            } catch (ResponseStatusException denied) {
                errors.add("forbidden");
            }
        }
        Map<String, Long> roleVersions = codeVersions(values.get("roles"), true, errors);
        Map<String, Long> organizationVersions =
                codeVersions(values.get("organizations"), false, errors);
        if (roleDelegation != null
                && values.containsKey("roles")
                && !errors.contains("invalidRoleCodes")) {
            try {
                roleDelegation.validateImportAssignment(
                        matchedId, enabledRoleIds(values.get("roles")), actor);
            } catch (ResponseStatusException denied) {
                String reason = denied.getReason();
                errors.add(reason == null || reason.isBlank() ? "forbidden" : reason);
            }
        }
        if (summary != null && changesContainUserData(values)) {
            try {
                var current = userData.readValues(summary.id(), actor);
                for (var field : importProfileFields()) {
                    if ("displayName".equals(field.key()) || !Boolean.TRUE.equals(field.required()))
                        continue;
                    String value = values.getOrDefault(field.key(), current.get(field.key()));
                    if (value == null || value.isBlank())
                        errors.add("requiredFieldMissing:" + field.key());
                }
            } catch (ResponseStatusException denied) {
                errors.add("forbidden");
            }
        }
        if (summary == null && changesContainUserData(values)) {
            for (var field : importProfileFields())
                if (!"displayName".equals(field.key())
                        && Boolean.TRUE.equals(field.required())
                        && !hasNonBlank(values.get(field.key())))
                    errors.add("requiredFieldMissing:" + field.key());
        }
        validateProfileInputs(values, errors);
        if (summary != null && values.keySet().stream().noneMatch(key -> !"username".equals(key)))
            errors.add("noImportChanges");
        var safeChanges = new LinkedHashMap<>(values);
        safeChanges.remove("username");
        if (!errors.isEmpty()) action = "invalid";
        return new RowPlan(
                rowNumber,
                action,
                matchedId,
                expectedVersion,
                username,
                safeChanges,
                roleVersions,
                organizationVersions,
                List.copyOf(errors));
    }

    void applyRow(RowPlan row, UserImportStore.Preview preview, String actor) {
        UUID id;
        if ("create".equals(row.action())) {
            if (row.username() == null || !USERNAME.matcher(row.username()).matches())
                badRequest("invalidUsername");
            String displayName = row.changes().get("displayName");
            if (displayName == null || displayName.isBlank()) badRequest("missingDisplayName");
            var created = users.createImported(row.username(), displayName, actor);
            id = created.id();
        } else {
            var current = users.find(row.matchedUserId(), actor);
            if (current.version() != row.expectedUserVersion()) conflict("importVersionConflict");
            id = current.id();
        }
        var changes = row.changes();
        if ("create".equals(row.action())
                && changes.keySet().stream().allMatch("displayName"::equals)) return;
        validateCodeVersions(row, true);
        validateCodeVersions(row, false);
        var profileFields = profileSchema.read().data().forHost("admin-user-editor").fields();
        var profileKeys =
                profileFields.stream()
                        .filter(ProfileFields.Field::custom)
                        .map(ProfileFields.Field::key)
                        .collect(java.util.stream.Collectors.toSet());
        UserSummary latest = users.find(id, actor);
        var basicProfile = new LinkedHashMap<String, String>();
        for (String key : List.of("realName", "birthDate", "region"))
            if (changes.containsKey(key)) basicProfile.put(key, changes.get(key));
        if (!basicProfile.isEmpty())
            latest = users.updateBasicProfileFields(id, latest.version(), basicProfile, actor);
        Map<String, String> incomingProfile = new LinkedHashMap<>();
        for (String key : profileKeys)
            if (changes.containsKey(key)) incomingProfile.put(key, changes.get(key));
        UserProfileValuesPort.EditorSnapshot profile = null;
        Map<String, String> mergedProfile = null;
        if (!incomingProfile.isEmpty()) {
            profile = profileValues.readForEditor(id);
            mergedProfile = new LinkedHashMap<>(profile.values());
            mergedProfile.putAll(incomingProfile);
        }
        Boolean nextEnabled =
                changes.containsKey("enabled") ? parseBoolean(changes.get("enabled")) : null;
        boolean updateAccount =
                changes.containsKey("enabled")
                        || ("update".equals(row.action()) && changes.containsKey("displayName"));
        Set<UUID> roleIds =
                changes.containsKey("roles") ? resolveCodes(changes.get("roles"), true) : null;
        Set<UUID> organizationIds =
                changes.containsKey("organizations")
                        ? resolveCodes(changes.get("organizations"), false)
                        : null;
        if (updateAccount || mergedProfile != null || roleIds != null || organizationIds != null) {
            var profileChange =
                    updateAccount
                            ? new UserEditorService.Profile(
                                    changes.getOrDefault("displayName", latest.displayName()),
                                    nextEnabled == null ? latest.enabled() : nextEnabled)
                            : null;
            editor.save(
                    id,
                    new UserEditorService.Change(
                            latest.version(),
                            profileChange,
                            roleIds,
                            organizationIds,
                            mergedProfile == null ? null : profile.schemaVersion(),
                            mergedProfile),
                    actor);
        }
    }

    private void authorizeChanges(UUID userId, Map<String, String> values, String actor) {
        boolean accountOrProfile =
                values.keySet().stream()
                        .anyMatch(
                                key -> !Set.of("username", "roles", "organizations").contains(key));
        if (accountOrProfile)
            guard.requireOperationUser(actor, userId, PlatformOperation.USER_UPDATE);
        if (values.containsKey("roles"))
            guard.requireOperationUser(actor, userId, PlatformOperation.USER_ASSIGN_ROLES);
        if (values.containsKey("organizations"))
            guard.requireOperationUser(actor, userId, PlatformOperation.USER_ASSIGN_ORGANIZATIONS);
    }

    private void revalidatePreview(UserImportStore.Preview preview, String actor) {
        requireImport(actor);
        rejectRolePreview();
        if (preview.previewVersion() <= 0
                || !preview.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
            conflict("importPreviewExpired");
        if (profileSchema.read().version() != preview.profileSchemaVersion())
            conflict("importVersionConflict");
        var importable =
                userData.importableFields(actor).stream()
                        .filter(TemplateUserDataPort.Field::writable)
                        .map(TemplateUserDataPort.Field::key)
                        .collect(java.util.stream.Collectors.toSet());
        for (RowPlan row : preview.rows()) {
            if (!importable.containsAll(row.changes().keySet())) conflict("importVersionConflict");
            if ("create".equals(row.action()))
                guard.requireOperationActionGlobal(actor, PlatformOperation.USER_CREATE);
            if ("update".equals(row.action())) {
                var visible = users.find(row.matchedUserId(), actor);
                if (visible.version() != row.expectedUserVersion())
                    conflict("importVersionConflict");
                authorizeChanges(visible.id(), row.changes(), actor);
            }
        }
    }

    Map<String, Long> codeVersions(String value, boolean roles, Set<String> errors) {
        if (value == null) return Map.of();
        Set<String> codes;
        try {
            codes = parseCodes(value);
        } catch (ResponseStatusException invalid) {
            errors.add(roles ? "invalidRoleCodes" : "invalidOrganizationCodes");
            return Map.of();
        }
        if (codes.isEmpty()) return Map.of();
        String table = roles ? "platform_role" : "platform_organization";
        var versions =
                jdbc
                        .sql(
                                "SELECT code,version FROM "
                                        + table
                                        + " WHERE code IN (:codes) AND enabled=true")
                        .param("codes", codes)
                        .query((rs, n) -> Map.entry(rs.getString("code"), rs.getLong("version")))
                        .list()
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue));
        if (!versions.keySet().containsAll(codes))
            errors.add(roles ? "invalidRoleCodes" : "invalidOrganizationCodes");
        return versions;
    }

    void validateCodeVersions(RowPlan row, boolean roles) {
        Map<String, Long> expected = roles ? row.roleVersions() : row.organizationVersions();
        if (expected.isEmpty()) return;
        String table = roles ? "platform_role" : "platform_organization";
        var actual =
                jdbc
                        .sql(
                                "SELECT code,version FROM "
                                        + table
                                        + " WHERE code IN (:codes) AND enabled=true")
                        .param("codes", expected.keySet())
                        .query((rs, n) -> Map.entry(rs.getString("code"), rs.getLong("version")))
                        .list()
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue));
        if (!actual.equals(expected)) conflict("importVersionConflict");
    }

    private Set<UUID> enabledRoleIds(String value) {
        Set<String> codes = parseCodes(value);
        if (codes.isEmpty() || jdbc == null) return Set.of();
        return jdbc.sql(
                        "SELECT id FROM platform_role WHERE code IN (:codes) AND enabled=true")
                .param("codes", codes)
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    Set<UUID> resolveCodes(String value, boolean roles) {
        Set<String> codes = parseCodes(value);
        if (codes.isEmpty()) return Set.of();
        String table = roles ? "platform_role" : "platform_organization";
        var found =
                jdbc
                        .sql(
                                "SELECT id,code FROM "
                                        + table
                                        + " WHERE code IN (:codes) AND enabled=true")
                        .param("codes", codes)
                        .query(
                                (rs, n) ->
                                        Map.entry(
                                                rs.getString("code"),
                                                rs.getObject("id", UUID.class)))
                        .list()
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue));
        if (!found.keySet().containsAll(codes))
            badRequest(roles ? "invalidRoleCodes" : "invalidOrganizationCodes");
        return codes.stream()
                .map(found::get)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> parseCodes(String value) {
        if (value == null || value.isBlank()) return Set.of();
        var result = new LinkedHashSet<String>();
        for (String raw : value.split(",", -1)) {
            String code = raw.strip();
            if (!code.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{1,99}")
                    || !result.add(code)
                    || result.size() > 100) badRequest("invalidImportCodes");
        }
        return Set.copyOf(result);
    }

    private PreviewView previewView(UserImportStore.Preview preview, String actor) {
        var rows =
                preview.rows().stream()
                        .map(
                                row -> {
                                    Map<String, String> before =
                                            row.matchedUserId() == null
                                                    ? Map.of()
                                                    : userData.readValues(
                                                            row.matchedUserId(), actor);
                                    var changes = new LinkedHashMap<String, ChangeView>();
                                    row.changes()
                                            .forEach(
                                                    (key, after) ->
                                                            changes.put(
                                                                    key,
                                                                    new ChangeView(
                                                                            before.get(key),
                                                                            after)));
                                    return new PreviewRow(
                                            row.rowNumber(),
                                            row.action(),
                                            row.matchedUserId(),
                                            row.expectedUserVersion(),
                                            Map.copyOf(changes),
                                            row.errors());
                                })
                        .toList();
        return new PreviewView(
                preview.id(),
                preview.previewVersion(),
                preview.fileSha256(),
                preview.expiresAt(),
                rows);
    }

    private TaskView taskView(UserImportStore.Task task) {
        var result = task.result();
        if (result == null || "running".equals(task.status()))
            return new TaskView(task.id(), "running", 0, 0, 0, List.of(), null);
        return new TaskView(
                task.id(),
                result.status(),
                result.created(),
                result.updated(),
                result.failed(),
                result.rows(),
                result.errorCode());
    }

    private void validateMapping(Map<String, String> mapping, String actor) {
        var writable =
                fields(actor).stream()
                        .map(TemplateUserDataPort.Field::key)
                        .collect(java.util.stream.Collectors.toSet());
        if (!writable.containsAll(mapping.values())
                || mapping.values().stream().distinct().count() != mapping.size()
                || mapping.containsValue("password")) badRequest("invalidImportMapping");
    }

    private List<ProfileFields.Field> importProfileFields() {
        var schema = profileSchema.read().data();
        var combined = new LinkedHashMap<String, ProfileFields.Field>();
        schema.forHost("account-profile").fields().stream()
                .filter(
                        field ->
                                !field.custom()
                                        && Set.of("realName", "birthDate", "region")
                                                .contains(field.key()))
                .forEach(field -> combined.put(field.key(), field));
        schema.forHost("admin-user-editor").fields().stream()
                .filter(ProfileFields.Field::custom)
                .forEach(field -> combined.put(field.key(), field));
        return List.copyOf(combined.values());
    }

    private void validateProfileInputs(Map<String, String> values, Set<String> errors) {
        var fields = new HashMap<String, ProfileFields.Field>();
        importProfileFields().forEach(field -> fields.put(field.key(), field));
        values.forEach(
                (key, value) -> {
                    var field = fields.get(key);
                    if (field == null) return;
                    int max = "TEXTAREA".equals(field.type()) ? 2_000 : 200;
                    if (value.length() > max) {
                        errors.add("invalidProfileValues");
                        return;
                    }
                    try {
                        switch (field.type()) {
                            case "NUMBER" -> {
                                if (!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))
                                    throw new IllegalArgumentException();
                                var number = new java.math.BigDecimal(value);
                                if (number.precision() > 30 || number.scale() > 10)
                                    throw new IllegalArgumentException();
                            }
                            case "DATE" -> {
                                if ("birthDate".equals(key)) {
                                    if (!value.matches("[0-9]{4}-(0[1-9]|1[0-2])")
                                            || java.time.YearMonth.parse(value)
                                                    .isAfter(java.time.YearMonth.now()))
                                        throw new IllegalArgumentException();
                                } else java.time.LocalDate.parse(value);
                            }
                            case "SELECT" -> {
                                if (field.usesDictionary()) UUID.fromString(value);
                                else if (!field.options().contains(value))
                                    throw new IllegalArgumentException();
                            }
                            default -> {}
                        }
                    } catch (RuntimeException invalid) {
                        errors.add("invalidProfileValues");
                    }
                });
    }

    private static String owner(String actor) {
        var current = AuthenticationRequestContext.current();
        if (current == null || current.userId() == null) forbidden("forbidden");
        return current.userId().toString();
    }

    private static Map<String, String> parseMapping(String raw) {
        if (raw == null || raw.isBlank()) badRequest("invalidImportMapping");
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            Map<?, ?> source = mapper.readValue(raw, Map.class);
            var result = new LinkedHashMap<String, String>();
            source.forEach(
                    (key, value) -> {
                        if (!(key instanceof String)
                                || !(value instanceof String)
                                || ((String) key).isBlank()
                                || ((String) value).isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "invalidImportMapping");
                        }
                        result.put((String) key, (String) value);
                    });
            return Map.copyOf(result);
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalidImportMapping", failure);
        }
    }

    private static List<List<String>> readWorkbook(byte[] bytes) {
        try {
            return DocumentWorkerClient.readWorkbook(bytes);
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidWorkbook", failure);
        }
    }

    private static void validateWorkbookFile(String filename, byte[] bytes) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            badRequest("unsupportedFile");
        if (bytes == null || bytes.length == 0) badRequest("unsupportedFile");
        if (bytes.length > MAX_FILE_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "fileTooLarge");
    }

    private void requireImport(String actor) {
        guard.requireOperation(actor, PlatformOperation.USER_IMPORT);
    }

    private void validateTaskAuthorization(String actor) {
        guard.requirePage(actor, PlatformPageId.USERS);
        guard.requireOperation(actor, PlatformOperation.USER_IMPORT);
    }

    private static void rejectRolePreview() {
        var current = AuthenticationRequestContext.current();
        if (current != null
                && current.authorization() != null
                && current.authorization().previewActive()) forbidden("forbidden");
    }

    private static RequestAuthorizationContext authorization() {
        var current = AuthenticationRequestContext.current();
        return current == null ? null : current.authorization();
    }

    private static boolean changesContainUserData(Map<String, String> values) {
        return values.keySet().stream()
                .anyMatch(key -> !Set.of("username", "roles", "organizations").contains(key));
    }

    static Boolean parseBoolean(String value) {
        if (value == null) return null;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true",
                    "1",
                    "yes",
                    "是",
                    "启用",
                    "啟用",
                    "正常",
                    "active",
                    "有効",
                    "활성",
                    "actif",
                    "activo",
                    "aktiv" ->
                    true;
            case "false",
                    "0",
                    "no",
                    "否",
                    "停用",
                    "disabled",
                    "無効",
                    "비활성",
                    "désactivé",
                    "desactivado",
                    "deaktiviert" ->
                    false;
            default -> null;
        };
    }

    private static String cell(List<String> row, int index) {
        return row == null || index < 0 || index >= row.size()
                ? ""
                : row.get(index) == null ? "" : row.get(index);
    }

    private static boolean hasNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static String errorCode(Throwable failure) {
        if (failure instanceof ResponseStatusException response && response.getReason() != null)
            return response.getReason();
        if (failure instanceof RowFailure row && row.code != null) return row.code;
        return "importFailed";
    }

    private static void badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static void forbidden(String code) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, code);
    }

    private static void conflict(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
