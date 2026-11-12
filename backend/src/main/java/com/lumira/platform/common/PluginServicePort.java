package com.lumira.platform.common;

/** One owned service of each type per plugin; identity is checked before runtime publication. */
public interface PluginServicePort {
    String ownerPluginId();

    String serviceType();
}
