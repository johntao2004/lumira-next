package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProfileValuesTest {
    private ProfileFields schema(String type, boolean required, boolean visible) {
        return new ProfileFields(
                List.of(
                        new ProfileFields.Field(
                                "custom",
                                "Custom",
                                "",
                                type,
                                visible,
                                required,
                                5,
                                "",
                                "custom",
                                "Custom",
                                10,
                                true,
                                List.of("A", "B"))));
    }

    @Test
    void preservesHiddenAndRetiredValues() {
        assertEquals(
                Map.of("custom", "old", "retired", "keep"),
                ProfileValues.merge(
                        schema("TEXT", false, false),
                        Map.of("custom", "old", "retired", "keep"),
                        Map.of()));
        assertEquals(
                Map.of(),
                ProfileValues.visible(schema("TEXT", false, false), Map.of("custom", "old")));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        ProfileValues.merge(
                                schema("TEXT", false, false), Map.of(), Map.of("custom", "new")));
    }

    @Test
    void requiresVisibleValuesAndRejectsUnknownKeys() {
        assertThrows(
                ResponseStatusException.class,
                () ->
                        ProfileValues.merge(
                                schema("TEXT", true, true), Map.of("custom", "old"), Map.of()));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        ProfileValues.merge(
                                schema("TEXT", false, true), Map.of(), Map.of("password", "new")));
        assertEquals(
                Map.of(),
                ProfileValues.merge(
                        schema("TEXT", false, true), Map.of("custom", "old"), Map.of()));
    }

    @Test
    void validatesDatesNumbersAndChoices() {
        for (var entry : Map.of("DATE", "2026-02-30", "NUMBER", "NaN", "SELECT", "C").entrySet())
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            ProfileValues.merge(
                                    schema(entry.getKey(), false, true),
                                    Map.of(),
                                    Map.of("custom", entry.getValue())));
        for (var entry : Map.of("DATE", "2024-02-29", "NUMBER", "-12.50", "SELECT", "A").entrySet())
            assertEquals(
                    entry.getValue(),
                    ProfileValues.merge(
                                    schema(entry.getKey(), true, true),
                                    Map.of(),
                                    Map.of("custom", entry.getValue()))
                            .get("custom"));
    }

    @Test
    void boundsAndTrimsText() {
        assertEquals(
                "text",
                ProfileValues.merge(
                                schema("TEXT", true, true), Map.of(), Map.of("custom", " text "))
                        .get("custom"));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        ProfileValues.merge(
                                schema("TEXT", true, true),
                                Map.of(),
                                Map.of("custom", "x".repeat(201))));
        assertEquals(
                2000,
                ProfileValues.merge(
                                schema("TEXTAREA", true, true),
                                Map.of(),
                                Map.of("custom", "x".repeat(2000)))
                        .get("custom")
                        .length());
    }

    @Test
    void renamingTheDisplayedLabelPreservesValuesByStableKey() {
        String key = "profileField_0123456789abcdef0123456789abcdef";
        var before =
                new ProfileFields(
                        List.of(
                                new ProfileFields.Field(
                                        key,
                                        "Emergency contact",
                                        "Who to contact",
                                        "TEXT",
                                        true,
                                        false,
                                        5,
                                        "Name",
                                        "custom",
                                        "Additional information",
                                        100,
                                        true,
                                        List.of())));
        var after =
                new ProfileFields(
                        List.of(
                                new ProfileFields.Field(
                                        key,
                                        "Contact person",
                                        "Who to contact",
                                        "TEXT",
                                        true,
                                        false,
                                        5,
                                        "Name",
                                        "custom",
                                        "Additional information",
                                        100,
                                        true,
                                        List.of())));
        var stored = ProfileValues.merge(before, Map.of(), Map.of(key, "Alex"));
        assertEquals("Alex", ProfileValues.visible(after, stored).get(key));
        assertEquals("Morgan", ProfileValues.merge(after, stored, Map.of(key, "Morgan")).get(key));
    }

    @Test
    void
            unchangedDictionaryValuesUseCurrentDictionaryMembershipWhileNewValuesRequireEnabledItems() {
        var item = UUID.fromString("c9426817-f81e-430d-8bee-58d14879a541");
        var other = UUID.fromString("d9426817-f81e-430d-8bee-58d14879a541");
        var prior = Map.of("custom", item.toString());
        var retained = Map.of("custom", item.toString());
        var dictionarySchema = dictionarySchema("choices");
        var disabledExisting =
                new ProfileDictionaryPort() {
                    @Override
                    public Set<Selection> enabledSelections(Collection<Selection> requested) {
                        return Set.of();
                    }

                    @Override
                    public Set<Selection> existingSelections(Collection<Selection> requested) {
                        return requested.stream()
                                .filter(selection -> selection.code().equals("choices"))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    }

                    @Override
                    public Map<Selection, SelectionMetadata> selectionMetadata(
                            Collection<Selection> requested) {
                        return Map.of();
                    }
                };
        assertDoesNotThrow(
                () ->
                        ProfileValuesService.validateDictionarySelections(
                                dictionarySchema, prior, retained, disabledExisting));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        ProfileValuesService.validateDictionarySelections(
                                dictionarySchema,
                                prior,
                                Map.of("custom", other.toString()),
                                disabledExisting));

        var changedDictionarySchema = dictionarySchema("replacement");
        assertThrows(
                ResponseStatusException.class,
                () ->
                        ProfileValuesService.validateDictionarySelections(
                                changedDictionarySchema, prior, retained, disabledExisting));
    }

    @Test
    void clearingDictionaryValuesSkipsLookupButEnabledReplacementIsStillValidated() {
        var item = UUID.fromString("c9426817-f81e-430d-8bee-58d14879a541");
        var replacement = UUID.fromString("d9426817-f81e-430d-8bee-58d14879a541");
        var previous = Map.of("custom", item.toString());
        var enabledReplacement =
                new ProfileDictionaryPort() {
                    @Override
                    public Set<Selection> enabledSelections(Collection<Selection> requested) {
                        return Set.copyOf(requested);
                    }

                    @Override
                    public Set<Selection> existingSelections(Collection<Selection> requested) {
                        return Set.copyOf(requested);
                    }

                    @Override
                    public Map<Selection, SelectionMetadata> selectionMetadata(
                            Collection<Selection> requested) {
                        return Map.of();
                    }
                };
        assertDoesNotThrow(
                () ->
                        ProfileValuesService.validateDictionarySelections(
                                dictionarySchema("choices"),
                                previous,
                                Map.of(),
                                enabledReplacement));
        assertDoesNotThrow(
                () ->
                        ProfileValuesService.validateDictionarySelections(
                                dictionarySchema("choices"),
                                previous,
                                Map.of("custom", replacement.toString()),
                                enabledReplacement));
        var disabledReplacement =
                new ProfileDictionaryPort() {
                    @Override
                    public Set<Selection> enabledSelections(Collection<Selection> requested) {
                        return Set.of();
                    }

                    @Override
                    public Set<Selection> existingSelections(Collection<Selection> requested) {
                        return Set.copyOf(requested);
                    }

                    @Override
                    public Map<Selection, SelectionMetadata> selectionMetadata(
                            Collection<Selection> requested) {
                        return Map.of();
                    }
                };
        assertThrows(
                ResponseStatusException.class,
                () ->
                        ProfileValuesService.validateDictionarySelections(
                                dictionarySchema("choices"),
                                previous,
                                Map.of("custom", replacement.toString()),
                                disabledReplacement));
    }

    @Test
    void dictionarySnapshotMetadataIsLimitedToVisibleCurrentCodeSelections() {
        var id = UUID.fromString("c9426817-f81e-430d-8bee-58d14879a541");
        var selection = new ProfileDictionaryPort.Selection("choices", id);
        var schema =
                new ProfileFields(
                        List.of(
                                dictionaryField("visibleChoice", true, "choices"),
                                dictionaryField("hiddenChoice", false, "choices"),
                                dictionaryField("changedCode", true, "replacement")));
        var queried = new ArrayList<Set<ProfileDictionaryPort.Selection>>();
        var lookup =
                new ProfileDictionaryPort() {
                    @Override
                    public Set<Selection> enabledSelections(Collection<Selection> requested) {
                        return Set.copyOf(requested);
                    }

                    @Override
                    public Set<Selection> existingSelections(Collection<Selection> requested) {
                        return Set.copyOf(requested);
                    }

                    @Override
                    public Map<Selection, SelectionMetadata> selectionMetadata(
                            Collection<Selection> requested) {
                        queried.add(Set.copyOf(requested));
                        return Map.of(selection, new SelectionMetadata("Disabled option", false));
                    }
                };
        var values =
                Map.of(
                        "visibleChoice",
                        id.toString(),
                        "hiddenChoice",
                        id.toString(),
                        "changedCode",
                        id.toString());
        var result = ProfileValuesService.dictionarySelections(schema, values, lookup);
        assertEquals(
                Map.of(
                        "visibleChoice",
                        new ProfileValuesService.DictionarySelection(id, "Disabled option", false)),
                result);
        assertEquals(
                List.of(Set.of(selection, new ProfileDictionaryPort.Selection("replacement", id))),
                queried);
    }

    private ProfileFields dictionarySchema(String code) {
        return new ProfileFields(
                List.of(
                        new ProfileFields.Field(
                                "custom", "Custom", "", "SELECT", true, false, 5, "", "custom",
                                "Custom", 10, true, List.of(), code)));
    }

    private ProfileFields.Field dictionaryField(String key, boolean visible, String code) {
        return new ProfileFields.Field(
                key, key, "", "SELECT", visible, false, 5, "", "custom", "Custom", 10, true,
                List.of(), code);
    }
}
