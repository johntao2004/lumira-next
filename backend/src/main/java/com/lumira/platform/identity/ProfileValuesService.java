package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileRegistrationLimits;
import com.lumira.platform.identity.api.ProfileRegistrationPort;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import com.lumira.platform.identity.api.UserProfileValuesPort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProfileValuesService implements ProfileRegistrationPort, UserProfileValuesPort {
    public record Input(Long version, Long schemaVersion, Map<String, String> values) {}

    public record View(
            long version,
            long schemaVersion,
            List<ProfileFields.Field> fields,
            Map<String, String> values,
            Map<String, DictionarySelection> dictionarySelections) {}

    public record DictionarySelection(UUID itemId, String label, boolean enabled) {}

    private final ProfileDictionaryPort dictionaries;
    private final JdbcClient jdbc;
    private final ProfileRepository profiles;
    private final ProfileSchemaPort schemas;
    private final ObjectMapper mapper;
    private final AuditPort audit;
    private final AuthorizationContextBoundaryPort authorizationBoundary;

    public ProfileValuesService(
            JdbcClient jdbc,
            ProfileRepository profiles,
            ProfileSchemaPort schemas,
            ObjectMapper mapper,
            AuditPort audit,
            ProfileDictionaryPort dictionaries) {
        this(jdbc, profiles, schemas, mapper, audit, dictionaries, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProfileValuesService(
            JdbcClient jdbc,
            ProfileRepository profiles,
            ProfileSchemaPort schemas,
            ObjectMapper mapper,
            AuditPort audit,
            ProfileDictionaryPort dictionaries,
            AuthorizationContextBoundaryPort authorizationBoundary) {
        this.dictionaries = dictionaries;
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.schemas = schemas;
        this.mapper = mapper;
        this.audit = audit;
        this.authorizationBoundary = authorizationBoundary;
    }

    Map<String, String> storedValues(UUID id) {
        String json =
                jdbc.sql("SELECT profile_values::text FROM platform_user WHERE id=:id")
                        .param("id", id)
                        .query(String.class)
                        .single();
        return mapper.readValue(
                json,
                mapper.getTypeFactory()
                        .constructMapType(LinkedHashMap.class, String.class, String.class));
    }

    private static boolean placedOnPage(ProfileFields.Field field, String page) {
        return field.placementsOrLegacy().stream()
                .anyMatch(placement -> placement.page().equals(page));
    }

    private static int pageOrder(ProfileFields.Field field, String page) {
        var placement =
                field.placementsOrLegacy().stream()
                        .filter(item -> item.page().equals(page))
                        .findFirst();
        return placement
                .map(
                        item ->
                                switch (item.tab()) {
                                    case "basic" -> 0;
                                    case "details" -> 1;
                                    case "custom" -> 2;
                                    default -> 0;
                                })
                .orElse(0);
    }

    private static List<ProfileFields.Field> fieldsForPage(
            ProfileSchemaPort.Snapshot schema, String page, String tab, boolean customOnly) {
        return schema.data().fields().stream()
                .filter(field -> field.visible() && (!customOnly || field.custom()))
                .filter(
                        field ->
                                field.placementsOrLegacy().stream()
                                        .anyMatch(
                                                placement ->
                                                        placement.page().equals(page)
                                                                && (tab == null
                                                                        || placement
                                                                                .tab()
                                                                                .equals(tab))))
                .sorted(
                        Comparator.comparingInt(
                                        (ProfileFields.Field field) -> pageOrder(field, page))
                                .thenComparingInt(
                                        field ->
                                                field.placementSortNo(
                                                        page, tabForPage(field, page)))
                                .thenComparing(ProfileFields.Field::key))
                .toList();
    }

    private static String tabForPage(ProfileFields.Field field, String page) {
        return field.placementsOrLegacy().stream()
                .filter(placement -> placement.page().equals(page))
                .map(ProfileFields.Placement::tab)
                .findFirst()
                .orElse("");
    }

    private View view(
            long version,
            ProfileSchemaPort.Snapshot schema,
            Map<String, String> values,
            String page,
            String tab,
            boolean customOnly) {
        var visibleFields = fieldsForPage(schema, page, tab, customOnly);
        var projectedFields =
                visibleFields.stream().map(field -> fieldForHost(field, page, tab)).toList();
        var visibleValues = ProfileValues.visibleForPlacement(schema.data(), values, page, tab);
        return new View(
                version,
                schema.version(),
                projectedFields,
                visibleValues,
                dictionarySelections(
                        new ProfileFields(projectedFields), visibleValues, dictionaries));
    }

    private static ProfileFields.Field fieldForHost(
            ProfileFields.Field field, String page, String tab) {
        var placements =
                field.placementsOrLegacy().stream()
                        .filter(
                                placement ->
                                        placement.page().equals(page)
                                                && (tab == null || placement.tab().equals(tab)))
                        .toList();
        return new ProfileFields.Field(
                field.key(),
                field.label(),
                field.description(),
                field.type(),
                field.visible(),
                field.required(),
                field.weight(),
                field.placeholder(),
                field.groupKey(),
                field.groupLabel(),
                field.sortNo(),
                field.custom(),
                field.options(),
                field.dictionaryCode(),
                false,
                placements);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(String actor) {
        var profile = profiles.find(actor, false);
        return view(
                profile.version(),
                schemas.read(),
                storedValues(profile.id()),
                "account-profile",
                null,
                true);
    }

    @Transactional
    public View write(String actor, Input input) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(481026)")
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
        var profile = profiles.find(actor, true);
        var schema = schemas.read();
        if (input.version() == null
                || input.version() != profile.version()
                || input.schemaVersion() == null
                || input.schemaVersion() != schema.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        var stored = storedValues(profile.id());
        var editable = fieldsForPage(schema, "account-profile", null, true);
        var merged = ProfileValues.merge(editable, stored, input.values(), true);
        validateDictionarySelections(new ProfileFields(editable), stored, merged, dictionaries);
        persistValues(profile.id(), editable, merged);
        audit.append(actor, "profile.values", profile.id());
        return view(
                profiles.find(actor, false).version(),
                schema,
                merged,
                "account-profile",
                null,
                true);
    }

    private void persistValues(
            UUID accountId, List<ProfileFields.Field> editable, Map<String, String> values) {
        jdbc.sql(
                        "UPDATE platform_user SET profile_values=CAST(:values AS jsonb),version=version+1 WHERE id=:id")
                .param("values", mapper.writeValueAsString(values))
                .param("id", accountId)
                .update();
        jdbc.sql(
                        "DELETE FROM platform_profile_dictionary_selection WHERE user_id=:user AND field_key IN (SELECT jsonb_array_elements_text(CAST(:keys AS jsonb)))")
                .param("user", accountId)
                .param(
                        "keys",
                        mapper.writeValueAsString(
                                editable.stream().map(ProfileFields.Field::key).toList()))
                .update();
        var references =
                editable.stream()
                        .filter(ProfileFields.Field::usesDictionary)
                        .filter(field -> values.containsKey(field.key()))
                        .map(
                                field ->
                                        Map.of(
                                                "field_key",
                                                field.key(),
                                                "item_id",
                                                values.get(field.key())))
                        .toList();
        jdbc.sql(
                        "INSERT INTO platform_profile_dictionary_selection(user_id,field_key,item_id) SELECT :user,r.field_key,r.item_id FROM jsonb_to_recordset(CAST(:refs AS jsonb)) AS r(field_key text,item_id uuid)")
                .param("user", accountId)
                .param("refs", mapper.writeValueAsString(references))
                .update();
    }

    /** Join a built-in profile edit so the shared account profile form commits atomically. */
    @Transactional
    void writeForAccount(
            UUID accountId, String actor, long schemaVersion, Map<String, String> values) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(481026)")
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
        accountVersion(accountId, true);
        var schema = schemas.read();
        if (schema.version() != schemaVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        var fields = fieldsForPage(schema, "account-profile", null, true);
        var stored = storedValues(accountId);
        var merged = ProfileValues.merge(fields, stored, values, true);
        validateDictionarySelections(new ProfileFields(fields), stored, merged, dictionaries);
        if (merged.equals(stored)) return;
        persistValues(accountId, fields, merged);
        audit.append(actor, "profile.values", accountId);
    }

    private static List<ProfileFields.Field> registrationFields(ProfileSchemaPort.Snapshot schema) {
        var fields = ProfileRegistrationLimits.publicFields(schema.data());
        if (fields.size() > ProfileRegistrationLimits.MAX_FIELDS)
            throw registrationSchemaUnavailable();
        return fields.stream()
                .sorted(
                        Comparator.comparingInt(
                                        (ProfileFields.Field field) ->
                                                field.placementSortNo(
                                                        "public-registration", "additional"))
                                .thenComparing(ProfileFields.Field::key))
                .toList();
    }

    private static ResponseStatusException registrationSchemaUnavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "registrationProfileSchemaUnavailable");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RegistrationSchema registrationSchema() {
        var schema = schemas.read();
        var fields = registrationFields(schema);
        var codes = ProfileRegistrationLimits.dictionaryCodes(fields);
        var expectedCounts =
                codes.isEmpty() ? Map.<String, Long>of() : dictionaries.enabledOptionCounts(codes);
        if (!ProfileRegistrationLimits.withinOptionLimits(fields, expectedCounts))
            throw registrationSchemaUnavailable();
        var dictionaryOptions =
                codes.isEmpty()
                        ? Map.<String, List<ProfileDictionaryPort.PublicOption>>of()
                        : dictionaries.enabledOptions(
                                codes, ProfileRegistrationLimits.OPTION_QUERY_LIMIT);
        var returnedCounts = new LinkedHashMap<String, Long>();
        codes.forEach(
                code ->
                        returnedCounts.put(
                                code,
                                (long) dictionaryOptions.getOrDefault(code, List.of()).size()));
        if (!ProfileRegistrationLimits.withinOptionLimits(fields, returnedCounts)
                || codes.stream()
                        .anyMatch(
                                code ->
                                        !Objects.equals(
                                                expectedCounts.get(code),
                                                returnedCounts.get(code)))) {
            throw registrationSchemaUnavailable();
        }
        var projected = new ArrayList<PublicField>();
        for (var field : fields) {
            List<ProfileRegistrationPort.PublicOption> options;
            if (field.usesDictionary()) {
                options =
                        dictionaryOptions.getOrDefault(field.dictionaryCode(), List.of()).stream()
                                .map(
                                        option ->
                                                new ProfileRegistrationPort.PublicOption(
                                                        option.value(), option.label()))
                                .toList();
            } else {
                options =
                        field.options().stream()
                                .map(
                                        value ->
                                                new ProfileRegistrationPort.PublicOption(
                                                        value, value))
                                .toList();
            }
            projected.add(
                    new PublicField(
                            field.key(),
                            field.label(),
                            field.description(),
                            field.type(),
                            false,
                            field.placeholder(),
                            options));
        }
        var response = new RegistrationSchema(schema.version(), List.copyOf(projected));
        if (mapper.writeValueAsBytes(response).length > ProfileRegistrationLimits.MAX_SCHEMA_BYTES)
            throw registrationSchemaUnavailable();
        return response;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateAndStoreByAccount(
            UUID accountId, long schemaVersion, Map<String, String> values) {
        if (accountId == null || values == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidProfileValues");
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(481026)")
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
        var schema = schemas.read();
        if (schemaVersion != schema.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        var fields = registrationFields(schema);
        var stored = storedValues(accountId);
        var editable =
                fields.stream().map(ProfileValuesService::optionalRegistrationField).toList();
        var merged = ProfileValues.merge(editable, stored, values, false);
        validateDictionarySelections(new ProfileFields(fields), stored, merged, dictionaries);
        if (!merged.equals(stored)) persistValues(accountId, fields, merged);
    }

    private static ProfileFields.Field optionalRegistrationField(ProfileFields.Field field) {
        return new ProfileFields.Field(
                field.key(),
                field.label(),
                field.description(),
                field.type(),
                field.visible(),
                false,
                field.weight(),
                field.placeholder(),
                field.groupKey(),
                field.groupLabel(),
                field.sortNo(),
                field.custom(),
                field.options(),
                field.dictionaryCode(),
                field.publicRegistrationAllowed(),
                field.placements());
    }

    private long accountVersion(UUID accountId, boolean lock) {
        return jdbc.sql(
                        "SELECT version FROM platform_user WHERE id=:id AND deleted_at IS NULL"
                                + (lock ? " FOR UPDATE" : ""))
                .param("id", accountId)
                .query(Long.class)
                .optional()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "userNotFound"));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserProfileValuesPort.EditorSnapshot readForEditor(UUID accountId) {
        var schema = schemas.read();
        var fields = fieldsForPage(schema, "admin-user-editor", "profile", true);
        var projectedFields =
                fields.stream()
                        .map(field -> fieldForHost(field, "admin-user-editor", "profile"))
                        .toList();
        var values =
                ProfileValues.visibleForPlacement(
                        schema.data(), storedValues(accountId), "admin-user-editor", "profile");
        var dictionarySelections =
                dictionarySelections(new ProfileFields(fields), values, dictionaries)
                        .entrySet()
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey,
                                        entry ->
                                                new UserProfileValuesPort.DictionarySelection(
                                                        entry.getValue().itemId(),
                                                        entry.getValue().label(),
                                                        entry.getValue().enabled())));
        return new UserProfileValuesPort.EditorSnapshot(
                accountVersion(accountId, false),
                schema.version(),
                projectedFields,
                values,
                dictionarySelections);
    }

    @Override
    @Transactional
    public long writeForEditor(
            UUID accountId,
            String actor,
            long expectedVersion,
            long schemaVersion,
            Map<String, String> values) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(481026)")
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
        long currentVersion = accountVersion(accountId, true);
        var schema = schemas.read();
        if (currentVersion != expectedVersion || schema.version() != schemaVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        var fields = fieldsForPage(schema, "admin-user-editor", "profile", true);
        var stored = storedValues(accountId);
        var merged = ProfileValues.merge(fields, stored, values, true);
        validateDictionarySelections(new ProfileFields(fields), stored, merged, dictionaries);
        if (merged.equals(stored)) return currentVersion;
        persistValues(accountId, fields, merged);
        audit.append(actor, "profile.values", accountId);
        return currentVersion + 1;
    }

    static void validateDictionarySelections(
            ProfileFields schema,
            Map<String, String> stored,
            Map<String, String> merged,
            ProfileDictionaryPort dictionaries) {
        var unchanged = new ArrayList<ProfileDictionaryPort.Selection>();
        var changed = new ArrayList<ProfileDictionaryPort.Selection>();
        for (var field : schema.fields()) {
            if (!field.custom()
                    || !field.visible()
                    || !field.usesDictionary()
                    || !merged.containsKey(field.key())) continue;
            var selection =
                    new ProfileDictionaryPort.Selection(
                            field.dictionaryCode(), UUID.fromString(merged.get(field.key())));
            if (sameUuid(stored.get(field.key()), selection.id())) unchanged.add(selection);
            else changed.add(selection);
        }
        if (!dictionaries.existingSelections(unchanged).containsAll(unchanged)
                || !dictionaries.enabledSelections(changed).containsAll(changed))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidProfileValues");
    }

    static Map<String, DictionarySelection> dictionarySelections(
            ProfileFields schema, Map<String, String> values, ProfileDictionaryPort dictionaries) {
        var requestedByField = new LinkedHashMap<String, ProfileDictionaryPort.Selection>();
        for (var field : schema.fields()) {
            if (!field.custom()
                    || !field.visible()
                    || !field.usesDictionary()
                    || !values.containsKey(field.key())) continue;
            try {
                requestedByField.put(
                        field.key(),
                        new ProfileDictionaryPort.Selection(
                                field.dictionaryCode(), UUID.fromString(values.get(field.key()))));
            } catch (IllegalArgumentException ignored) {
            }
        }
        var metadata = dictionaries.selectionMetadata(requestedByField.values());
        var result = new LinkedHashMap<String, DictionarySelection>();
        requestedByField.forEach(
                (fieldKey, selection) -> {
                    var item = metadata.get(selection);
                    if (item != null)
                        result.put(
                                fieldKey,
                                new DictionarySelection(
                                        selection.id(), item.label(), item.enabled()));
                });
        return Map.copyOf(result);
    }

    private static boolean sameUuid(String value, UUID id) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).equals(id);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
