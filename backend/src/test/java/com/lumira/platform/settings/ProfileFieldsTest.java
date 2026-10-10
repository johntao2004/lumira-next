package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.identity.api.ProfileFields;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProfileFieldsTest {
    private ProfileFields.Field field(String key, String type, List<String> options) {
        return new ProfileFields.Field(
                key, "Custom", "", type, true, false, 5, "", "custom", "Custom", 100, true,
                options);
    }

    private ProfileFields schema(ProfileFields.Field... extra) {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        fields.addAll(Arrays.asList(extra));
        return new ProfileFields(fields);
    }

    @Test
    void imageIsDefaultAndLegacySchemasKeepTheirSettings() {
        var defaults = ProfileFields.defaults();
        assertEquals("IMAGE", defaults.avatar().orElseThrow().type());
        assertSame(defaults, defaults.withDefaultAvatar());
        var legacyFields =
                defaults.fields().stream()
                        .filter(f -> !f.key().equals("avatarUrl"))
                        .map(
                                f ->
                                        new ProfileFields.Field(
                                                f.key(),
                                                f.label(),
                                                f.description(),
                                                f.type(),
                                                f.visible(),
                                                f.required(),
                                                f.weight(),
                                                f.placeholder(),
                                                f.groupKey(),
                                                "基本资料",
                                                f.sortNo(),
                                                f.custom(),
                                                f.options()))
                        .toList();
        var legacy = new ProfileFields(legacyFields);
        var upgraded = legacy.withDefaultAvatar();
        assertEquals(legacyFields, upgraded.fields().subList(0, legacyFields.size()));
        assertEquals("基本资料", upgraded.avatar().orElseThrow().groupLabel());
        assertDoesNotThrow(upgraded::validate);
        var configuredImage =
                new ProfileFields.Field(
                        "avatarUrl",
                        "Custom image",
                        "Description",
                        "IMAGE",
                        false,
                        false,
                        12,
                        "",
                        "photos",
                        "Photos",
                        80,
                        false,
                        List.of());
        var configured = new ArrayList<>(legacyFields);
        configured.add(configuredImage);
        assertEquals(
                configuredImage,
                new ProfileFields(configured).withDefaultAvatar().avatar().orElseThrow());
    }

    @Test
    void persistedOrderControlsReadOrder() {
        var reordered = new ArrayList<>(ProfileFields.defaults().fields());
        Collections.reverse(reordered);
        assertEquals(ProfileFields.defaults(), new ProfileFields(reordered).sorted());
    }

    @Test
    void dictionaryChoicesAreExclusive() {
        var base = field("choice", "SELECT", List.of());
        var bound =
                new ProfileFields.Field(
                        base.key(),
                        base.label(),
                        base.description(),
                        base.type(),
                        base.visible(),
                        base.required(),
                        base.weight(),
                        base.placeholder(),
                        base.groupKey(),
                        base.groupLabel(),
                        base.sortNo(),
                        base.custom(),
                        base.options(),
                        "dictionary_code");
        assertDoesNotThrow(() -> schema(bound).validate());
        var invalid =
                new ProfileFields.Field(
                        base.key(),
                        base.label(),
                        base.description(),
                        "TEXT",
                        base.visible(),
                        base.required(),
                        base.weight(),
                        base.placeholder(),
                        base.groupKey(),
                        base.groupLabel(),
                        base.sortNo(),
                        base.custom(),
                        List.of(),
                        "dictionary_code");
        assertThrows(ProfileFields.Invalid.class, () -> schema(invalid).validate());
    }

    @Test
    void defaultsAndEveryCustomType() {
        assertDoesNotThrow(() -> ProfileFields.defaults().validate());
        for (String type : List.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT"))
            assertDoesNotThrow(
                    () ->
                            schema(
                                            field(
                                                    "customField",
                                                    type,
                                                    type.equals("SELECT")
                                                            ? List.of("One", "Two")
                                                            : List.of()))
                                    .validate());
    }

    @Test
    void protectsBuiltInsAndReservedKeys() {
        assertThrows(ProfileFields.Invalid.class, () -> new ProfileFields(List.of()).validate());
        for (String key :
                List.of(
                        "password",
                        "PassWord",
                        "EMAIL",
                        "roleIds",
                        "accountId",
                        "authorities",
                        "constructor",
                        "__proto__",
                        "displayName",
                        "invalid-key"))
            assertThrows(
                    ProfileFields.Invalid.class,
                    () -> schema(field(key, "TEXT", List.of())).validate());
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        fields.remove(0);
        fields.add(
                new ProfileFields.Field(
                        "displayName",
                        "Name",
                        "",
                        "TEXT",
                        false,
                        false,
                        5,
                        "",
                        "basic",
                        "Basic information",
                        10,
                        false,
                        List.of()));
        assertThrows(ProfileFields.Invalid.class, () -> new ProfileFields(fields).validate());
    }

    @Test
    void rejectsAmbiguousOptionsAndDuplicateKeys() {
        for (List<String> options : List.of(List.<String>of(), List.of("A", "A"), List.of(" A")))
            assertThrows(
                    ProfileFields.Invalid.class,
                    () -> schema(field("choice", "SELECT", options)).validate());
        assertThrows(
                ProfileFields.Invalid.class,
                () -> schema(field("text", "TEXT", List.of("Option"))).validate());
        assertThrows(
                ProfileFields.Invalid.class,
                () ->
                        schema(field("same", "TEXT", List.of()), field("same", "DATE", List.of()))
                                .validate());
    }

    @Test
    void boundsSchemaAndRejectsNullFields() {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        fields.add(null);
        assertThrows(ProfileFields.Invalid.class, () -> new ProfileFields(fields).validate());
        assertThrows(
                ProfileFields.Invalid.class,
                () ->
                        new ProfileFields(
                                        Collections.nCopies(101, field("many", "TEXT", List.of())))
                                .validate());
    }

    @Test
    void validatesRegisteredPlacementsAndPreservesLegacyHostsAndOrder() {
        var defaults = ProfileFields.defaults().withResolvedPlacements();
        var legacyCustom = field("legacyContact", "TEXT", List.of()).withResolvedPlacement();
        assertEquals("account-profile", legacyCustom.placements().getFirst().page());
        assertEquals("custom", legacyCustom.placements().getFirst().tab());
        assertEquals("basic", defaults.fields().getFirst().placements().getFirst().tab());
        assertTrue(
                defaults.fields().getFirst().placements().stream()
                        .anyMatch(
                                placement ->
                                        placement.page().equals("admin-user-editor")
                                                && placement.tab().equals("profile")));

        var validRequired =
                placedField(
                        "requiredContact",
                        true,
                        false,
                        List.of(
                                new ProfileFields.Placement("account-profile", "details", 20),
                                new ProfileFields.Placement("admin-user-editor", "profile", 10),
                                new ProfileFields.Placement(
                                        "public-registration", "additional", 30)));
        assertDoesNotThrow(() -> schema(validRequired).validate());

        var unknownRoute =
                placedField(
                        "routeField",
                        false,
                        false,
                        List.of(new ProfileFields.Placement("/arbitrary-route", "custom", 10)));
        assertThrows(ProfileFields.Invalid.class, () -> schema(unknownRoute).validate());

        var duplicateHost =
                placedField(
                        "duplicate",
                        false,
                        false,
                        List.of(
                                new ProfileFields.Placement("account-profile", "basic", 10),
                                new ProfileFields.Placement("account-profile", "custom", 20)));
        assertThrows(ProfileFields.Invalid.class, () -> schema(duplicateHost).validate());
    }

    @Test
    void registrationProjectionMustBeExplicitOptionalAndBounded() {
        var registrationOnlyRequired =
                placedField(
                        "registrationOnly",
                        true,
                        true,
                        List.of(
                                new ProfileFields.Placement(
                                        "public-registration", "additional", 10)));
        assertThrows(
                ProfileFields.Invalid.class, () -> schema(registrationOnlyRequired).validate());

        var noPlacementRequired = placedField("nowhereRequired", true, false, List.of());
        assertThrows(ProfileFields.Invalid.class, () -> schema(noPlacementRequired).validate());

        var hiddenRequiredBase = placedField("hiddenRequired", true, false, List.of());
        var hiddenRequired =
                new ProfileFields.Field(
                        hiddenRequiredBase.key(),
                        hiddenRequiredBase.label(),
                        hiddenRequiredBase.description(),
                        hiddenRequiredBase.type(),
                        false,
                        hiddenRequiredBase.required(),
                        hiddenRequiredBase.weight(),
                        hiddenRequiredBase.placeholder(),
                        hiddenRequiredBase.groupKey(),
                        hiddenRequiredBase.groupLabel(),
                        hiddenRequiredBase.sortNo(),
                        hiddenRequiredBase.custom(),
                        hiddenRequiredBase.options(),
                        hiddenRequiredBase.dictionaryCode(),
                        hiddenRequiredBase.publicRegistrationAllowed(),
                        hiddenRequiredBase.placements());
        assertDoesNotThrow(() -> schema(hiddenRequired).validate());

        var published = new ArrayList<ProfileFields.Field>();
        for (int index = 0; index < 21; index++) {
            published.add(
                    placedField(
                            "published" + index,
                            false,
                            true,
                            List.of(
                                    new ProfileFields.Placement(
                                            "public-registration", "additional", index))));
        }
        assertThrows(
                ProfileFields.Invalid.class,
                () -> schema(published.toArray(ProfileFields.Field[]::new)).validate());
    }

    private ProfileFields.Field placedField(
            String key,
            boolean required,
            boolean publicAllowed,
            List<ProfileFields.Placement> placements) {
        return new ProfileFields.Field(
                key,
                key,
                "",
                "TEXT",
                true,
                required,
                5,
                "",
                "custom",
                "Custom",
                10,
                true,
                List.of(),
                null,
                publicAllowed,
                placements);
    }
}
