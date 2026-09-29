-- owner=platform
ALTER TABLE platform_ai_provider
    DROP CONSTRAINT platform_ai_provider_kind_ck;

ALTER TABLE platform_ai_provider
    ADD CONSTRAINT platform_ai_provider_kind_ck
    CHECK (provider_kind IN (
        'OLLAMA',
        'LM_STUDIO',
        'OPENAI',
        'DEEPSEEK',
        'QWEN',
        'ZHIPU',
        'SILICONFLOW',
        'OPENROUTER',
        'MOONSHOT',
        'CUSTOM_OPENAI',
        'TYPESAFE_JEV'
    ));
