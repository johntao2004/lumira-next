package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginCleanupTest {
    @Test
    void attemptsEveryReleaseAndPreservesPrimaryAndSuppressedFailures() {
        var completed = new ArrayList<String>();
        var first = new IllegalStateException("route release failed");
        var second = new IllegalStateException("manager stop failed");
        List<AutoCloseable> releases =
                List.of(
                        () -> {
                            completed.add("route");
                            throw first;
                        },
                        () -> completed.add("resources"),
                        () -> completed.add("context"),
                        () -> {
                            completed.add("stop");
                            throw second;
                        },
                        () -> completed.add("unload"));
        assertThat(catchThrowable(() -> PluginCleanup.closeAll(releases))).isSameAs(first);
        assertThat(first.getSuppressed()).containsExactly(second);
        assertThat(completed).containsExactly("route", "resources", "context", "stop", "unload");
    }

    @Test
    void fatalCallbackStillAttemptsRemainingReleases() {
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new AssertionError("fixture callback");
        assertThat(
                        catchThrowable(
                                () ->
                                        PluginCleanup.closeAll(
                                                List.of(
                                                        () -> {
                                                            throw failure;
                                                        },
                                                        () -> released.set(true)))))
                .isSameAs(failure);
        assertThat(released.get()).isTrue();
    }
}
