package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.time.YearMonth;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    public record Input(
            String displayName,
            String realName,
            String birthMonth,
            String region,
            Long version,
            Long schemaVersion,
            Long profileValuesSchemaVersion,
            Map<String, String> profileValues) {}

    public record View(
            UUID id,
            String username,
            String displayName,
            String realName,
            String birthMonth,
            String region,
            long version,
            long schemaVersion,
            List<ProfileFields.Field> fields) {}

    private final ProfileRepository repository;
    private final AuditPort audit;
    private final ProfileSchemaPort schemas;
    private final JdbcClient jdbc;
    private final ProfileValuesService profileValues;
    private final AuthorizationContextBoundaryPort authorizationBoundary;

    public ProfileService(
            ProfileRepository repository,
            AuditPort audit,
            ProfileSchemaPort schemas,
            JdbcClient jdbc,
            ProfileValuesService profileValues) {
        this(repository, audit, schemas, jdbc, profileValues, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProfileService(
            ProfileRepository repository,
            AuditPort audit,
            ProfileSchemaPort schemas,
            JdbcClient jdbc,
            ProfileValuesService profileValues,
            AuthorizationContextBoundaryPort authorizationBoundary) {
        this.repository = repository;
        this.audit = audit;
        this.schemas = schemas;
        this.jdbc = jdbc;
        this.profileValues = profileValues;
        this.authorizationBoundary = authorizationBoundary;
    }

    private View view(ProfileRepository.Profile p, ProfileSchemaPort.Snapshot s) {
        return new View(
                p.id(),
                p.username(),
                p.displayName(),
                p.realName(),
                p.birthMonth(),
                p.region(),
                p.version(),
                s.version(),
                s.data().fields().stream()
                        .filter(
                                f ->
                                        !f.custom()
                                                && f.visible()
                                                && !f.key().equals("avatarUrl")
                                                && f.placementsOrLegacy().stream()
                                                        .anyMatch(
                                                                place ->
                                                                        place.page()
                                                                                .equals(
                                                                                        "account-profile")))
                        .sorted(
                                Comparator.comparingInt(
                                                (ProfileFields.Field f) ->
                                                        f.placementsOrLegacy().stream()
                                                                .filter(
                                                                        place ->
                                                                                place.page()
                                                                                        .equals(
                                                                                                "account-profile"))
                                                                .map(
                                                                        ProfileFields.Placement
                                                                                ::sortNo)
                                                                .filter(Objects::nonNull)
                                                                .findFirst()
                                                                .orElse(f.sortNo()))
                                        .thenComparing(ProfileFields.Field::key))
                        .map(field -> field.projectedForHost("account-profile"))
                        .toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View find(String actor) {
        return view(repository.find(actor, false), schemas.read());
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidProfile");
    }

    private String value(ProfileFields schema, String key, String incoming, String previous) {
        var field =
                schema.fields().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
        String text = incoming == null ? "" : incoming.strip();
        if (!field.visible()
                || field.placementsOrLegacy().stream()
                        .noneMatch(place -> place.page().equals("account-profile"))) {
            if (incoming != null && !text.equals(previous)) invalid();
            return previous;
        }
        if (text.length() > 200 || (field.required() && text.isBlank())) invalid();
        return text;
    }

    @Transactional
    public View update(String actor, Input input) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        if (input == null
                || (input.profileValues() != null && input.profileValuesSchemaVersion() == null)
                || (input.profileValues() == null && input.profileValuesSchemaVersion() != null))
            invalid();
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(481026)")
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
        var old = repository.find(actor, true);
        var schema = schemas.read();
        if (input.version() == null
                || input.version() != old.version()
                || (input.schemaVersion() == null
                        ? schema.version() != 0
                        : input.schemaVersion() != schema.version()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        String
                display =
                        value(schema.data(), "displayName", input.displayName(), old.displayName()),
                real = value(schema.data(), "realName", input.realName(), old.realName()),
                region = value(schema.data(), "region", input.region(), old.region()),
                birth = value(schema.data(), "birthDate", input.birthMonth(), old.birthMonth());
        if (!birth.isEmpty()) {
            try {
                if (!birth.matches("\\d{4}-\\d{2}")
                        || YearMonth.parse(birth).isAfter(YearMonth.now()))
                    throw new IllegalArgumentException();
            } catch (RuntimeException error) {
                invalid();
            }
        }
        repository.update(actor, display, real, birth, region);
        if (input.profileValues() != null)
            profileValues.writeForAccount(
                    old.id(), actor, input.profileValuesSchemaVersion(), input.profileValues());
        audit.append(actor, "profile.update", old.id());
        return view(repository.find(actor, false), schema);
    }
}
