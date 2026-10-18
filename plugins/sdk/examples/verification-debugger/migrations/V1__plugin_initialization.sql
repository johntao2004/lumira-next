-- Final-state initialization for verification-debugger on the platform B52 host.
-- Existing completed installations validate published history without replaying this SQL.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM platform_navigation_menu
         WHERE menu_code = 'operations'
           AND menu_type = 'CATALOG'
           AND NOT deleted
    ) THEN
        RAISE EXCEPTION 'The operations navigation catalog is required by verification-debugger';
    END IF;
END;
$$;

INSERT INTO platform_navigation_menu(
    id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,
    component_key,icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted
)
SELECT
    '00000000-0000-4000-8000-000000000950'::uuid,
    operations.id,
    'verification-debugger',
    'verificationDebuggerTitle',
    '验证调试器',
    'PAGE',
    'verification-debugger',
    '/dashboard/verification-debugger',
    'verification-debugger',
    NULL,
    'platform:verification-debugger:access',
    true,
    'verification-debugger',
    950,
    true,
    false,
    false
  FROM platform_navigation_menu operations
 WHERE operations.menu_code = 'operations'
   AND operations.menu_type = 'CATALOG'
   AND NOT operations.deleted
ON CONFLICT (menu_code) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    label_key = EXCLUDED.label_key,
    label_default = EXCLUDED.label_default,
    menu_type = EXCLUDED.menu_type,
    section_key = EXCLUDED.section_key,
    path = EXCLUDED.path,
    component_key = EXCLUDED.component_key,
    icon = EXCLUDED.icon,
    permission_key = EXCLUDED.permission_key,
    global_only = EXCLUDED.global_only,
    module_id = EXCLUDED.module_id,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    built_in = EXCLUDED.built_in,
    deleted = EXCLUDED.deleted,
    version = platform_navigation_menu.version + 1,
    updated_at = now()
WHERE platform_navigation_menu.module_id = 'verification-debugger';
