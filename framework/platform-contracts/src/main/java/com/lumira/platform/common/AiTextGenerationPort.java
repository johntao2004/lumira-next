package com.lumira.platform.common;

import java.util.*;

/**
 * 文本生成契约；凭据和提供方实现留在宿主。 Stable text-generation capability. Credentials and provider implementations
 * stay in the host.
 */
public interface AiTextGenerationPort {
    record Provider(UUID id, String name, String modelName) {}

    /** 列出当前可用的文本生成提供方，不返回凭据。 Lists available text-generation providers without credentials. */
    List<Provider> available();

    /**
     * 向指定提供方及模型提交消息并返回文本；调用方负责外发数据授权。 Submits messages to the provider/model and returns text;
     * callers authorize outbound data.
     */
    String chat(UUID providerId, String modelName, List<Map<String, String>> messages);
}
