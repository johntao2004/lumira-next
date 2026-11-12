package com.lumira.plugins.directory.users;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.DocumentWorkerClient;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.TemplateUserDataPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserXlsxExportService {
    private static final int MAX_DATA_ROWS = 4_999; // One workbook row is reserved for headers.
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_CELL_CHARACTERS = 20_000;
    private static final long MAX_TOTAL_CHARACTERS = 2_000_000;

    public record Request(
            String scope,
            int page,
            int pageSize,
            List<UUID> userIds,
            String search,
            Map<String, String> filters,
            Map<String, String> filterModes,
            List<String> fields,
            Map<String, String> fieldLabels,
            Map<String, String> statusLabels) {}

    public record Field(String key, String label, String type, String source, boolean readable) {}

    private final AdministrationPort guard;
    private final UserRepository users;
    private final TemplateUserDataPort userData;
    private final UserXlsxExportLocalization localization;

    public UserXlsxExportService(
            AdministrationPort guard,
            UserRepository users,
            TemplateUserDataPort userData,
            UserXlsxExportLocalization localization) {
        this.guard = guard;
        this.users = users;
        this.userData = userData;
        this.localization = localization;
    }

    @Transactional(readOnly = true)
    public List<Field> fields(String actor) {
        guard.requireOperation(actor, PlatformOperation.USER_EXPORT);
        return userData.readableFields(actor).stream()
                .map(
                        field ->
                                new Field(
                                        field.key(),
                                        field.label(),
                                        field.type(),
                                        field.source(),
                                        field.readable()))
                .toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(Request input, String actor, RequestAuthorizationContext authorization) {
        guard.requireOperation(actor, PlatformOperation.USER_EXPORT);
        requireNotRolePreview();
        if (input == null
                || input.scope() == null
                || input.fields() == null
                || input.fields().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidExportRequest");
        var fieldCatalog = new LinkedHashMap<String, Field>();
        fields(actor).forEach(field -> fieldCatalog.put(field.key(), field));
        var selected = new ArrayList<String>(new java.util.LinkedHashSet<>(input.fields()));
        if (selected.size() != input.fields().size()
                || selected.size() > MAX_COLUMNS
                || selected.stream().anyMatch(key -> !fieldCatalog.containsKey(key)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fieldNotReadable");
        Map<String, String> messages =
                input.fieldLabels() == null || input.statusLabels() == null
                        ? localization.messages(actor)
                        : Map.of();
        List<String> headers =
                exportHeaders(
                        selected,
                        fieldCatalog,
                        input.fieldLabels() == null
                                ? localization.fieldLabels(selected, fieldCatalog, messages)
                                : input.fieldLabels());
        Map<String, String> statusLabels =
                exportStatusLabels(
                        input.statusLabels() == null
                                ? localization.statusLabels(messages)
                                : input.statusLabels());

        PageRequest page =
                new PageRequest(
                        Math.max(1, input.page()),
                        input.pageSize() < 1 ? 100 : input.pageSize(),
                        input.search(),
                        input.filters(),
                        input.filterModes());
        List<UserSummary> rows =
                switch (input.scope()) {
                    case "currentPage" ->
                            users.findPage(
                                    page,
                                    actor,
                                    authorization,
                                    PlatformOperation.USER_EXPORT.permission(),
                                    PlatformPageId.USERS.accessPermission());
                    case "filtered" ->
                            users.findExport(
                                    page,
                                    actor,
                                    MAX_DATA_ROWS + 1,
                                    authorization,
                                    PlatformOperation.USER_EXPORT.permission(),
                                    PlatformPageId.USERS.accessPermission());
                    case "selected" -> selected(input.userIds(), actor, authorization);
                    default ->
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "invalidExportScope");
                };
        if (rows.size() > MAX_DATA_ROWS)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "batchLimitExceeded");

        Set<String> fieldKeys = Set.copyOf(selected);
        var values =
                userData.readValuesBatch(
                        rows.stream().map(UserSummary::id).toList(), actor, fieldKeys);
        var workbookRows = new ArrayList<List<String>>(rows.size() + 1);
        workbookRows.add(headers);
        long characterCount = workbookRows.getFirst().stream().mapToLong(String::length).sum();
        for (UserSummary user : rows) {
            Map<String, String> userValues = values.getOrDefault(user.id(), Map.of());
            var row = new ArrayList<String>(selected.size());
            for (String key : selected) {
                String value =
                        exportCellValue(fieldCatalog.get(key), userValues.get(key), statusLabels);
                if (value.length() > MAX_CELL_CHARACTERS)
                    throw new ResponseStatusException(
                            HttpStatus.PAYLOAD_TOO_LARGE, "exportCellTooLarge");
                characterCount += value.length();
                if (characterCount > MAX_TOTAL_CHARACTERS
                        || ((long) workbookRows.size() + 1) * selected.size() > 100_000)
                    throw new ResponseStatusException(
                            HttpStatus.PAYLOAD_TOO_LARGE, "exportLimitExceeded");
                row.add(value);
            }
            workbookRows.add(List.copyOf(row));
        }
        return DocumentWorkerClient.writeWorkbook(List.copyOf(workbookRows));
    }

    static String exportCellValue(Field field, String value, Map<String, String> statusLabels) {
        String normalized = exportCellValue(field, value);
        if ("enabled".equals(field.key()) && "BOOLEAN".equals(field.type()))
            return statusLabels.getOrDefault(normalized, "");
        return normalized;
    }

    static Map<String, String> exportStatusLabels(Map<String, String> labels) {
        if (labels == null
                || !labels.keySet().equals(Set.of("true", "false"))
                || labels.values().stream().anyMatch(label -> !validDisplayLabel(label)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidExportRequest");
        return Map.copyOf(labels);
    }

    private static boolean validDisplayLabel(String label) {
        return label != null
                && !label.isBlank()
                && label.length() <= 200
                && label.codePoints().noneMatch(Character::isISOControl);
    }

    static String exportCellValue(Field field, String value) {
        if (value == null || value.isBlank()) return "";
        // A zero placeholder is not a name, date or free-text profile value.
        // Numeric zero, boolean false and selected option codes retain their meaning.
        if ("0".equals(value.strip()) && Set.of("TEXT", "TEXTAREA", "DATE").contains(field.type()))
            return "";
        return value;
    }

    static List<String> exportHeaders(
            List<String> selected, Map<String, Field> catalog, Map<String, String> labels) {
        if (labels == null
                || !labels.keySet().equals(Set.copyOf(selected))
                || labels.values().stream().anyMatch(label -> !validDisplayLabel(label)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidExportRequest");
        return selected.stream().map(labels::get).toList();
    }

    private List<UserSummary> selected(
            List<UUID> ids, String actor, RequestAuthorizationContext authorization) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > MAX_DATA_ROWS
                || ids.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(ids).size() != ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batchLimitExceeded");
        var rows =
                users.findSelected(
                        Set.copyOf(ids),
                        actor,
                        authorization,
                        PlatformOperation.USER_EXPORT.permission(),
                        PlatformPageId.USERS.accessPermission());
        if (rows.size() != ids.size())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        return rows;
    }

    private static void requireNotRolePreview() {
        var request = com.lumira.platform.identity.api.AuthenticationRequestContext.current();
        if (request != null
                && request.authorization() != null
                && request.authorization().previewActive())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
}
