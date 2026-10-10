-- Keep every security settings tab in the server-owned route tree so the
-- shared workspace can resolve complete breadcrumbs on direct tab navigation.
-- The frontend still collapses these entries under the existing security tab.
-- owner=navigation
INSERT INTO platform_navigation_menu(
    id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,
    icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version,component_key
)
SELECT seed.id::uuid, administration.id, seed.menu_code, seed.label_key, seed.label_default,
       'PAGE', seed.section_key, seed.path, NULL, 'platform:settings:read', false, NULL,
       seed.sort_order, true, true, false, 1, NULL
FROM (VALUES
    ('f15212df-d192-491d-a0c6-1c1cbf10171d', 'authentication', 'authenticationMethods', '认证方式', 'authentication', '/dashboard/authentication', 101),
    ('ca740762-2005-4b8d-8a4a-87af93a096ce', 'wechat', 'wechatSettings', '微信登录设置', 'wechat', '/dashboard/wechat', 102),
    ('0e348dcc-ac99-4f40-a844-e14486e77663', 'captcha', 'captchaSettingsTitle', '人机验证设置', 'captcha', '/dashboard/captcha', 103),
    ('230e98d2-91d3-4793-b9a8-98f4d96ca209', 'registration', 'registrationSettingsTitle', '公开注册设置', 'registration', '/dashboard/registration', 104)
) AS seed(id, menu_code, label_key, label_default, section_key, path, sort_order)
JOIN platform_navigation_menu administration
  ON administration.menu_code = 'administration'
 AND administration.enabled = true
 AND administration.deleted = false
ON CONFLICT(menu_code) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    label_key = EXCLUDED.label_key,
    label_default = EXCLUDED.label_default,
    menu_type = EXCLUDED.menu_type,
    section_key = EXCLUDED.section_key,
    path = EXCLUDED.path,
    icon = NULL,
    permission_key = EXCLUDED.permission_key,
    global_only = false,
    module_id = NULL,
    sort_order = EXCLUDED.sort_order,
    enabled = true,
    built_in = true,
    deleted = false,
    version = platform_navigation_menu.version + 1,
    updated_at = now(),
    component_key = NULL;
