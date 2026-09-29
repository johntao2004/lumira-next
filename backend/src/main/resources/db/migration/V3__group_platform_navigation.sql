-- owner=navigation
-- Split the platform menu into task-oriented catalogs while preserving every
-- page route, permission, module binding, and enabled state.
INSERT INTO platform_navigation_menu(
    id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,
    icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version,component_key
)
VALUES
    ('00000000-0000-0000-0000-000000000053',NULL,'user-management','userManagement','用户管理','CATALOG',NULL,NULL,NULL,NULL,false,NULL,20,true,true,false,1,NULL),
    ('00000000-0000-0000-0000-000000000054',NULL,'content-management','contentManagement','内容管理','CATALOG',NULL,NULL,NULL,NULL,false,NULL,30,true,true,false,1,NULL),
    ('00000000-0000-0000-0000-000000000055',NULL,'operations','operationsCenter','运营中心','CATALOG',NULL,NULL,NULL,NULL,false,NULL,40,true,true,false,1,NULL)
ON CONFLICT(menu_code) DO UPDATE SET
    parent_id = NULL,
    label_key = EXCLUDED.label_key,
    label_default = EXCLUDED.label_default,
    menu_type = 'CATALOG',
    section_key = NULL,
    path = NULL,
    icon = NULL,
    permission_key = NULL,
    global_only = false,
    module_id = NULL,
    sort_order = EXCLUDED.sort_order,
    enabled = true,
    built_in = true,
    deleted = false,
    version = platform_navigation_menu.version + 1,
    updated_at = now(),
    component_key = NULL;

UPDATE platform_navigation_menu
SET sort_order = 50, version = version + 1, updated_at = now()
WHERE menu_code = 'administration';

UPDATE platform_navigation_menu
SET parent_id = '00000000-0000-0000-0000-000000000053',
    sort_order = CASE menu_code
        WHEN 'users' THEN 10
        WHEN 'online-users' THEN 20
        WHEN 'roles' THEN 30
        WHEN 'organizations' THEN 40
    END,
    label_key = CASE WHEN menu_code = 'users' THEN 'userList' ELSE label_key END,
    label_default = CASE WHEN menu_code = 'users' THEN '用户列表' ELSE label_default END,
    version = version + 1,
    updated_at = now()
WHERE menu_code IN ('users','online-users','roles','organizations');

UPDATE platform_navigation_menu
SET parent_id = '00000000-0000-0000-0000-000000000054',
    sort_order = CASE menu_code
        WHEN 'files-all' THEN 10
        WHEN 'dictionaries' THEN 20
        WHEN 'sensitive-words' THEN 30
    END,
    version = version + 1,
    updated_at = now()
WHERE menu_code IN ('files-all','dictionaries','sensitive-words');

UPDATE platform_navigation_menu
SET parent_id = '00000000-0000-0000-0000-000000000055',
    sort_order = CASE menu_code
        WHEN 'monitoring' THEN 10
        WHEN 'notifications' THEN 20
        WHEN 'audit' THEN 30
        WHEN 'mail-deliveries' THEN 40
        WHEN 'sms-deliveries' THEN 50
        WHEN 'event-outbox' THEN 60
        WHEN 'feedback' THEN 70
    END,
    version = version + 1,
    updated_at = now()
WHERE menu_code IN ('monitoring','notifications','audit','mail-deliveries','sms-deliveries','event-outbox','feedback');

UPDATE platform_navigation_menu
SET parent_id = '00000000-0000-0000-0000-000000000003',
    sort_order = CASE menu_code
        WHEN 'modules' THEN 10
        WHEN 'branding' THEN 20
        WHEN 'smtp' THEN 30
        WHEN 'sms' THEN 40
        WHEN 'profile-fields' THEN 50
        WHEN 'agreements' THEN 60
        WHEN 'watermark' THEN 70
        WHEN 'maintenance' THEN 80
        WHEN 'security' THEN 90
        WHEN 'passkey' THEN 100
        WHEN 'localization' THEN 110
        WHEN 'navigation' THEN 120
    END,
    version = version + 1,
    updated_at = now()
WHERE menu_code IN ('modules','branding','smtp','sms','profile-fields','agreements','watermark','maintenance','security','passkey','localization','navigation');
