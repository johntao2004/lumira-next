package com.lumira.platform.ai;

import java.util.List;
import java.util.UUID;

public record AiProviderProfile(
        UUID id,
        String name,
        ProviderKind providerKind,
        String baseUrl,
        String modelName,
        List<String> capabilityCodes,
        boolean enabled,
        long version) {
    public enum ProviderKind {
        OLLAMA,
        LM_STUDIO,
        OPENAI,
        DEEPSEEK,
        QWEN,
        ZHIPU,
        SILICONFLOW,
        OPENROUTER,
        MOONSHOT,
        CUSTOM_OPENAI,
        TYPESAFE_JEV;

        /** Capabilities exposed by this provider adapter's endpoint contract. */
        public List<String> providerCapabilityCodes() {
            return switch (this) {
                case OLLAMA,
                        LM_STUDIO,
                        OPENAI,
                        DEEPSEEK,
                        QWEN,
                        ZHIPU,
                        SILICONFLOW,
                        OPENROUTER,
                        MOONSHOT,
                        CUSTOM_OPENAI ->
                        List.of("CHAT");
                case TYPESAFE_JEV -> List.of("CHOICE", "SCORE", "NOUL");
            };
        }

        public String defaultBaseUrl() {
            return switch (this) {
                case OLLAMA -> "http://127.0.0.1:11434/v1";
                case LM_STUDIO -> "http://127.0.0.1:1234/v1";
                case OPENAI -> "https://api.openai.com/v1";
                case DEEPSEEK -> "https://api.deepseek.com";
                case QWEN -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
                case ZHIPU -> "https://open.bigmodel.cn/api/paas/v4";
                case SILICONFLOW -> "https://api.siliconflow.cn/v1";
                case OPENROUTER -> "https://openrouter.ai/api/v1";
                case MOONSHOT -> "https://api.moonshot.cn/v1";
                case CUSTOM_OPENAI -> "";
                case TYPESAFE_JEV -> DECISION_ORIGIN;
            };
        }
    }

    /**
     * The decision-model vendor is a fixed, administrator-enabled integration; no other origin is
     * accepted.
     */
    public static final String DECISION_ORIGIN = "https://api.typesafe.ai";

    public boolean isDecisionModel() {
        return providerKind == ProviderKind.TYPESAFE_JEV;
    }

    /**
     * The existing database column stores selected IDs as a comma-delimited value. Model IDs cannot
     * contain commas, so this remains compatible with rows written before multi-select.
     */
    public List<String> modelNames() {
        return decodeModelNames(modelName);
    }

    /** The first configured model remains the default for existing callers and API clients. */
    public String modelName() {
        return modelNames().stream().findFirst().orElse("");
    }

    public String encodedModelNames() {
        return String.join(",", modelNames());
    }

    public static List<String> decodeModelNames(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        List<String> values = List.of(encoded.split(",", -1));
        if (values.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("Stored AI provider model configuration is invalid");
        }
        return values;
    }

    /**
     * Capabilities declared by the provider adapter contract. These are provider-level endpoint
     * capabilities, not claims about every model returned by /models. Model-specific modalities
     * must come from provider metadata or an explicit model test.
     */
    public List<String> providerCapabilityCodes() {
        return providerKind.providerCapabilityCodes();
    }

    /**
     * Preserve the record component for persisted-profile construction, while making all existing
     * service callers observe adapter and explicitly supported model capabilities instead of legacy
     * flags.
     */
    public List<String> capabilityCodes() {
        return supportsUserTools(modelName())
                ? List.of("CHAT", "TOOL_CALLING")
                : providerKind.providerCapabilityCodes();
    }

    /** Explicit model contract: https://api-docs.deepseek.com/quick_start/pricing/ . */
    public boolean supportsUserTools(String selectedModel) {
        return supportsUserTools(providerKind, selectedModel);
    }

    public static boolean supportsUserTools(ProviderKind providerKind, String selectedModel) {
        return providerKind == ProviderKind.DEEPSEEK
                && ("deepseek-flash".equals(selectedModel)
                        || "deepseek-v4-pro".equals(selectedModel));
    }

    public boolean requiresCredential() {
        return providerKind != ProviderKind.OLLAMA && providerKind != ProviderKind.LM_STUDIO;
    }

    public boolean isOpenAiCompatibleCloud() {
        return switch (providerKind) {
            case OPENAI, DEEPSEEK, QWEN, ZHIPU, SILICONFLOW, OPENROUTER, MOONSHOT, CUSTOM_OPENAI ->
                    true;
            default -> false;
        };
    }
}
