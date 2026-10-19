/**
 * 登记插件任务和可关闭资源，仅在发布后启动任务，并通过调用租约及逆序释放配合卸载排空。 Owns plugin tasks and closeable resources, starting
 * tasks only after publication and coordinating uninstall draining through call leases and
 * reverse-order release.
 */
package com.lumira.platform.plugins;

import com.lumira.plugin.sdk.PluginResources;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 发布前不启动任务；HTTP 与扩展调用共用同一准入控制。 No work starts until publication. Admission is shared with HTTP and
 * extension calls.
 */
final class PluginOwnedResources implements PluginResources, AutoCloseable {
    private final ScheduledExecutorService executor;
    private final java.util.function.Supplier<AutoCloseable> lease;
    private final Map<String, AutoCloseable> owned = new LinkedHashMap<>();
    private final List<Runnable> starts = new ArrayList<>();
    private boolean started, closed;

    PluginOwnedResources(String id, java.util.function.Supplier<AutoCloseable> lease) {
        this.lease = lease;
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("lumira-plugin-task-" + id).factory());
    }

    @Override
    public synchronized AutoCloseable schedule(String name, Duration interval, Runnable task) {
        if (closed || interval == null || interval.toMillis() < 10 || owned.containsKey(name))
            throw new IllegalArgumentException("invalidPluginResource");
        final ScheduledFuture<?>[] future = {null};
        AutoCloseable cancel =
                () -> {
                    if (future[0] != null) future[0].cancel(false);
                };
        own(name, cancel);
        Runnable start =
                () ->
                        future[0] =
                                executor.scheduleWithFixedDelay(
                                        () -> {
                                            try (var admission = lease.get()) {
                                                task.run();
                                            } catch (
                                                    org.springframework.web.server
                                                                    .ResponseStatusException
                                                            stopped) {
                                            } catch (Exception e) {
                                                System.getLogger(getClass().getName())
                                                        .log(
                                                                System.Logger.Level.WARNING,
                                                                "Plugin task failed: " + name,
                                                                e);
                                            }
                                        },
                                        interval.toMillis(),
                                        interval.toMillis(),
                                        TimeUnit.MILLISECONDS);
        if (started) start.run();
        else starts.add(start);
        return cancel;
    }

    @Override
    public synchronized <T extends AutoCloseable> T own(String name, T resource) {
        if (closed || name == null || name.isBlank() || owned.putIfAbsent(name, resource) != null)
            throw new IllegalArgumentException("invalidPluginResource");
        return resource;
    }

    synchronized void start() {
        started = true;
        starts.forEach(Runnable::run);
        starts.clear();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        executor.shutdown();
        var registrations = new ArrayList<>(owned.values());
        Collections.reverse(registrations);
        for (var r : registrations)
            try {
                r.close();
            } catch (Exception e) {
                System.getLogger(getClass().getName())
                        .log(System.Logger.Level.WARNING, "Plugin resource release failed", e);
            }
        owned.clear();
        starts.clear();
    }
}
