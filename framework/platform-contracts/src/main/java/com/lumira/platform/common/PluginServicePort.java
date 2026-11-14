package com.lumira.platform.common;

/**
 * 每个插件每种类型仅允许发布一个受管服务；发布前须校验归属插件身份。
 *
 * <p>One owned service of each type per plugin; identity is checked before runtime publication.
 */
public interface PluginServicePort {
    /** 返回拥有该服务实现的插件标识。 Returns the identifier of the plugin that owns this service. */
    String ownerPluginId();

    /** 返回该服务的稳定契约类型标识。 Returns the stable contract type identifier of this service. */
    String serviceType();
}
