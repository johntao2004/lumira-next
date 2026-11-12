package com.lumira.platform.common;

import java.util.List;

/** Current persisted business references that prevent disabling or removing a provider. */
public interface PluginDependenciesPort {
    List<String> blockers(String providerId);
}
