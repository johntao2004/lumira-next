package com.lumira.platform.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模块描述符：记录依赖及必需标记；core() 保留审计固定核心例外。 Module descriptor for dependencies and required status; core()
 * preserves the fixed audit exception.
 */
public record PlatformModule(
        String id, String name, String version, List<String> dependencies, boolean required) {
    public PlatformModule(String id, String name, String version, List<String> dependencies) {
        this(id, name, version, dependencies, false);
    }

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,99}");

    public PlatformModule {
        Objects.requireNonNull(id, "module id");
        Objects.requireNonNull(name, "module name");
        Objects.requireNonNull(version, "module version");
        Objects.requireNonNull(dependencies, "module dependencies");
        if (!ID.matcher(id).matches())
            throw new IllegalArgumentException("Invalid module id: " + id);
        if (name.isBlank() || version.isBlank())
            throw new IllegalArgumentException("Module name and version are required");
        dependencies = List.copyOf(dependencies);
        if (dependencies.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Module dependency cannot be null: " + id);
        if (dependencies.contains(id))
            throw new IllegalArgumentException("Module cannot depend on itself: " + id);
        if (dependencies.size() != new HashSet<>(dependencies).size())
            throw new IllegalArgumentException("Duplicate module dependency: " + id);
    }

    /**
     * 必需模块与审计查询模块属于固定核心能力。 Required modules and the audit query module are fixed core capabilities.
     */
    public boolean core() {
        return required || "audit-console".equals(id);
    }
}
