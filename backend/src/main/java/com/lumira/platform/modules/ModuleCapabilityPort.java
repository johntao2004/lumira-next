package com.lumira.platform.modules;

import java.util.List;

/** Read-only capability snapshot exposed to platform consumers such as navigation and monitoring. */
@FunctionalInterface
public interface ModuleCapabilityPort {
    List<String> enabledModules();

    /** Total registered modules, including required modules without a state row. */
    default int totalModules() {
        return enabledModules().size();
    }
}
