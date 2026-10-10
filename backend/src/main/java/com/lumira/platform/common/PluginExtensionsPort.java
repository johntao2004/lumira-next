package com.lumira.platform.common;

import java.util.List;
import java.util.function.Function;

/** Calls are scoped to a runtime lease; callers must not retain plugin objects. */
public interface PluginExtensionsPort {
    <T, R> List<R> extensions(Class<T> type, Function<T, R> call);
}
