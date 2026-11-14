package com.lumira.platform.common;

/**
 * 通过声明且可排空的 AI 依赖消费的受管文本生成服务。
 *
 * <p>Owned generation service consumed through a declared, drainable AI dependency.
 */
public interface AiTextGenerationServicePort extends PluginServicePort, AiTextGenerationPort {
    /**
     * 返回拥有文本生成服务的 AI 插件标识。 Returns the AI plugin identifier owning this text-generation service.
     */
    @Override
    default String ownerPluginId() {
        return "ai";
    }

    /** 返回文本生成服务的契约类型标识。 Returns the contract type identifier for text generation. */
    @Override
    default String serviceType() {
        return "text-generation";
    }
}
