package com.lumira.platform.common;

/**
 * 语言注册契约；宿主不直接读取插件自有表。 Stable language-registration boundary; the host does not read plugin-owned
 * tables.
 */
public interface LocaleRegistryPort {
    /** 判断指定语言是否已启用。 Reports whether the requested locale is enabled. */
    boolean enabled(String locale);
}
