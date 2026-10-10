package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileRegistrationLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProfileRegistrationLimitsTest {
    @Test
    void boundsEachDictionaryFieldAtTwoHundredOptions() {
        var field = dictionaryField("dictionaryField", "districts");
        assertTrue(
                ProfileRegistrationLimits.withinOptionLimits(
                        List.of(field), Map.of("districts", 200L)));
        assertFalse(
                ProfileRegistrationLimits.withinOptionLimits(
                        List.of(field), Map.of("districts", 201L)));
    }

    @Test
    void boundsTheRepeatedOutputSlotsAtOneThousandOptions() {
        var fields = new ArrayList<ProfileFields.Field>();
        for (int index = 0; index < 5; index++) fields.add(staticField("static" + index, 200));
        assertTrue(ProfileRegistrationLimits.withinOptionLimits(fields, Map.of()));
        fields.add(staticField("oneMore", 1));
        assertFalse(ProfileRegistrationLimits.withinOptionLimits(fields, Map.of()));

        var repeatedDictionary = new ArrayList<ProfileFields.Field>();
        for (int index = 0; index < 6; index++)
            repeatedDictionary.add(dictionaryField("shared" + index, "districts"));
        assertFalse(
                ProfileRegistrationLimits.withinOptionLimits(
                        repeatedDictionary, Map.of("districts", 200L)));
    }

    private static ProfileFields.Field dictionaryField(String key, String code) {
        return publicField(key, List.of(), code);
    }

    private static ProfileFields.Field staticField(String key, int count) {
        var options = new ArrayList<String>();
        for (int index = 0; index < count; index++) options.add("option-" + index);
        return publicField(key, options, null);
    }

    private static ProfileFields.Field publicField(String key, List<String> options, String code) {
        return new ProfileFields.Field(
                key,
                key,
                "",
                "SELECT",
                true,
                false,
                5,
                "",
                "custom",
                "Custom",
                10,
                true,
                options,
                code,
                true,
                List.of(new ProfileFields.Placement("public-registration", "additional", 10)));
    }
}
