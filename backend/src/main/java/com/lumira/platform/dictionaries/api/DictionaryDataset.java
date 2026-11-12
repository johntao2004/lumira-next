package com.lumira.platform.dictionaries.api;

import java.util.*;

/** Versioned transfer format; references use stable values rather than database UUIDs. */
public record DictionaryDataset(
        Integer schemaVersion,
        String code,
        String name,
        Boolean enabled,
        String structure,
        String remark,
        List<Entry> items) {
    public record Entry(
            String value,
            String label,
            String parentValue,
            Integer sortOrder,
            Boolean enabled,
            String remark) {}

    public static final int MAX_ITEMS = 20_000;

    public List<Entry> validatedOrder() {
        if (schemaVersion == null || schemaVersion != 1 || items == null) throw invalid();
        if (items.size() > MAX_ITEMS) throw new Invalid(true);
        Map<String, Entry> byValue = new LinkedHashMap<>();
        for (var item : items) {
            if (item == null
                    || item.value() == null
                    || item.value().isBlank()
                    || item.value().length() > 200
                    || !item.value().equals(item.value().strip())
                    || item.label() == null
                    || item.label().isBlank()
                    || item.label().length() > 200
                    || item.sortOrder() == null
                    || item.enabled() == null
                    || (item.remark() != null && item.remark().length() > 1000)) throw invalid();
            if (byValue.putIfAbsent(item.value(), item) != null) throw invalid();
        }
        Map<String, List<Entry>> children = new HashMap<>();
        var queue = new ArrayDeque<Entry>();
        for (var item : items) {
            if (item.parentValue() == null) queue.add(item);
            else {
                if (!"TREE".equals(structure) || !byValue.containsKey(item.parentValue()))
                    throw invalid();
                children.computeIfAbsent(item.parentValue(), key -> new ArrayList<>()).add(item);
            }
        }
        List<Entry> ordered = new ArrayList<>(items.size());
        while (!queue.isEmpty()) {
            var next = queue.remove();
            ordered.add(next);
            queue.addAll(children.getOrDefault(next.value(), List.of()));
        }
        if (ordered.size() != items.size()) throw invalid();
        return ordered;
    }

    public static final class Invalid extends IllegalArgumentException {
        private final boolean tooLarge;

        private Invalid(boolean tooLarge) {
            super(tooLarge ? "dictionaryDatasetTooLarge" : "invalidDictionaryDataset");
            this.tooLarge = tooLarge;
        }

        public boolean tooLarge() {
            return tooLarge;
        }
    }

    private static Invalid invalid() {
        return new Invalid(false);
    }
}
