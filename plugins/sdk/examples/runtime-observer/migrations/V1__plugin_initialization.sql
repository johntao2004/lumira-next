-- Final-state initialization for runtime-observer on the platform B52 host.
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
        RAISE EXCEPTION 'The operations navigation catalog is required by runtime-observer';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM platform_navigation_menu
         WHERE menu_code = 'runtime-observer'
           AND COALESCE(module_id, '') <> 'runtime-observer'
    ) THEN
        RAISE EXCEPTION 'The runtime-observer navigation code is owned by another module';
    END IF;
END;
$$;

INSERT INTO platform_navigation_menu(
    id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,
    component_key,icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted
)
SELECT
    '00000000-0000-4000-8000-000000000951'::uuid,
    operations.id,
    'runtime-observer',
    'runtimeObserverTitle',
    '运行展示屏',
    'PAGE',
    'runtime-observer',
    '/dashboard/runtime-observer',
    'runtime-observer',
    NULL,
    'platform:runtime-observer:access',
    true,
    'runtime-observer',
    951,
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
WHERE platform_navigation_menu.module_id = 'runtime-observer';
