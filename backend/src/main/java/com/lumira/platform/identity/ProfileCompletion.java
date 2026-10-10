package com.lumira.platform.identity;

import com.lumira.platform.identity.api.ProfileFields;
import java.util.*;

public final class ProfileCompletion {
    private ProfileCompletion() {}

    public record Item(String key, String label, boolean custom, boolean completed, int weight) {}

    public record Group(
            String key,
            String label,
            int totalWeight,
            int earnedWeight,
            int percent,
            List<Item> items) {}

    public record Score(int totalWeight, int earnedWeight, int percent, List<Group> groups) {}

    private static int percent(int earned, int total) {
        if (total == 0) return 0;
        if (earned == total) return 100;
        // Rounding must not report completion while any visible weight is missing.
        return Math.min(99, (int) Math.round(earned * 100.0 / total));
    }

    public static Score calculate(ProfileFields schema, Set<String> completed) {
        var groups = new LinkedHashMap<String, List<ProfileFields.Field>>();
        schema.fields().stream()
                .filter(ProfileFields.Field::visible)
                .sorted(
                        Comparator.comparingInt(ProfileFields.Field::sortNo)
                                .thenComparing(ProfileFields.Field::key))
                .forEach(f -> groups.computeIfAbsent(f.groupKey(), k -> new ArrayList<>()).add(f));
        var result = new ArrayList<Group>();
        int total = 0, earned = 0;
        for (var entry : groups.entrySet()) {
            var items =
                    entry.getValue().stream()
                            .map(
                                    f ->
                                            new Item(
                                                    f.key(),
                                                    f.label(),
                                                    f.custom(),
                                                    completed.contains(f.key()),
                                                    f.weight()))
                            .toList();
            int groupTotal = items.stream().mapToInt(Item::weight).sum(),
                    groupEarned =
                            items.stream().filter(Item::completed).mapToInt(Item::weight).sum();
            result.add(
                    new Group(
                            entry.getKey(),
                            entry.getValue().getFirst().groupLabel(),
                            groupTotal,
                            groupEarned,
                            percent(groupEarned, groupTotal),
                            items));
            total += groupTotal;
            earned += groupEarned;
        }
        return new Score(total, earned, percent(earned, total), List.copyOf(result));
    }
}
