-- owner=navigation
INSERT INTO platform_navigation_menu(
    id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,
    icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version,component_key
)
VALUES (
    '00000000-0000-0000-0000-000000000061',
    '00000000-0000-0000-0000-000000000055',
    'api-docs','apiDocs','后台 API 文档','PAGE','api-docs','/dashboard/api-docs',
    'ApiOutlined','platform:audit:read',true,'monitoring',15,true,true,false,1,NULL
)
ON CONFLICT(menu_code) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    label_key = EXCLUDED.label_key,
    label_default = EXCLUDED.label_default,
    menu_type = EXCLUDED.menu_type,
    section_key = EXCLUDED.section_key,
    path = EXCLUDED.path,
    icon = EXCLUDED.icon,
    permission_key = EXCLUDED.permission_key,
    global_only = EXCLUDED.global_only,
    module_id = EXCLUDED.module_id,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    built_in = EXCLUDED.built_in,
    deleted = EXCLUDED.deleted,
    version = platform_navigation_menu.version + 1,
    updated_at = now(),
    component_key = EXCLUDED.component_key;
