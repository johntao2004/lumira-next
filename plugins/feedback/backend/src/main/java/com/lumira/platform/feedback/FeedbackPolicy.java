package com.lumira.platform.feedback;

import java.util.*;

/**
 * Immutable policy snapshot; adapters must resolve configurable dictionaries, not client-supplied
 * rules.
 */
public record FeedbackPolicy(
        Map<String, Boolean> statuses,
        Set<String> priorities,
        Set<String> categories,
        String initialStatus,
        String defaultPriority) {
    public FeedbackPolicy {
        statuses = Map.copyOf(statuses);
        priorities = Set.copyOf(priorities);
        categories = Set.copyOf(categories);
        if (statuses.isEmpty()
                || priorities.isEmpty()
                || !statuses.containsKey(initialStatus)
                || !priorities.contains(defaultPriority)
                || statuses.keySet().stream().anyMatch(k -> !k.matches("[A-Z][A-Z0-9_]{0,31}"))
                || priorities.stream().anyMatch(k -> !k.matches("[A-Z][A-Z0-9_]{0,31}"))
                || categories.stream().anyMatch(k -> !k.matches("[A-Z][A-Z0-9_]{0,31}")))
            throw new IllegalArgumentException("Invalid feedback policy");
    }
}
