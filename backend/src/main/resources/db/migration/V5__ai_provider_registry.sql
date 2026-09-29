-- owner=platform
CREATE TABLE platform_ai_provider (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    provider_kind varchar(32) NOT NULL,
    base_url varchar(512) NOT NULL,
    model_name varchar(256) NOT NULL,
    capability_codes jsonb NOT NULL DEFAULT '[]'::jsonb,
    enabled boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 1,
    created_by varchar(100) NOT NULL,
    updated_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_ai_provider_kind_ck CHECK (provider_kind IN ('OLLAMA','LM_STUDIO','TYPESAFE_JEV')),
    CONSTRAINT platform_ai_provider_capabilities_ck CHECK (jsonb_typeof(capability_codes) = 'array'),
    CONSTRAINT platform_ai_provider_version_ck CHECK (version > 0),
    CONSTRAINT platform_ai_provider_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT platform_ai_provider_model_ck CHECK (length(btrim(model_name)) BETWEEN 1 AND 256)
);

CREATE INDEX platform_ai_provider_enabled_idx
    ON platform_ai_provider (enabled, provider_kind, name);

INSERT INTO platform_navigation_menu(
    id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,
    icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version,component_key
)
VALUES
    ('00000000-0000-0000-0000-000000000056',NULL,'ai-management','aiManagement','AI 中心','CATALOG',NULL,NULL,NULL,NULL,false,NULL,15,true,true,false,1,NULL),
    ('00000000-0000-0000-0000-000000000057','00000000-0000-0000-0000-000000000056','ai-console','aiConsole','AI 对话','PAGE','ai-console','/dashboard/ai-console',NULL,'platform:settings:read',false,'platform',10,true,true,false,1,NULL),
    ('00000000-0000-0000-0000-000000000058','00000000-0000-0000-0000-000000000056','ai-models','aiModels','模型接入','PAGE','ai-models','/dashboard/ai-models',NULL,'platform:settings:read',false,'platform',20,true,true,false,1,NULL),
    ('00000000-0000-0000-0000-000000000059','00000000-0000-0000-0000-000000000056','ai-judgment','aiJudgment','判断模型测试','PAGE','ai-judgment','/dashboard/ai-judgment',NULL,'platform:settings:read',false,'platform',30,true,true,false,1,NULL),
    ('00000000-0000-0000-0000-000000000060','00000000-0000-0000-0000-000000000056','ai-capabilities','aiCapabilities','AI 功能调用','PAGE','ai-capabilities','/dashboard/ai-capabilities',NULL,'platform:settings:read',false,'platform',40,true,true,false,1,NULL)
ON CONFLICT(menu_code) DO UPDATE SET
    parent_id=EXCLUDED.parent_id,
    label_key=EXCLUDED.label_key,
    label_default=EXCLUDED.label_default,
    menu_type=EXCLUDED.menu_type,
    section_key=EXCLUDED.section_key,
    path=EXCLUDED.path,
    permission_key=EXCLUDED.permission_key,
    global_only=EXCLUDED.global_only,
    module_id=EXCLUDED.module_id,
    sort_order=EXCLUDED.sort_order,
    enabled=true,
    built_in=true,
    deleted=false,
    version=platform_navigation_menu.version+1,
    updated_at=now(),
    component_key=NULL;
