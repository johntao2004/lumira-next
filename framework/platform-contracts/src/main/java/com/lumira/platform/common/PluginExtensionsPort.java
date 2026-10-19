package com.lumira.platform.common;

import java.util.List;
import java.util.function.Function;

/**
 * 调用受运行时租约保护；调用方不得保留插件对象。 Calls are scoped to a runtime lease; callers must not retain plugin
 * objects.
 */
public interface PluginExtensionsPort {
    /**
     * 在有效租约内逐个调用指定类型扩展并收集结果；不得从回调泄漏插件对象。 Invokes typed extensions under leases and collects
     * results; callbacks must not leak plugin objects.
     */
    <T, R> List<R> extensions(Class<T> type, Function<T, R> call);
}
