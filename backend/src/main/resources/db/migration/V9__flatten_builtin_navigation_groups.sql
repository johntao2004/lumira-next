-- Keep every built-in module's pages directly under its top-level catalog.
-- This matches the sidebar, which presents all pages as children of one module.
UPDATE platform_navigation_menu
SET parent_id = (SELECT id FROM platform_navigation_menu WHERE menu_code = 'administration'),
    sort_order = CASE menu_code
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
        ELSE sort_order
    END,
    version = version + 1,
    updated_at = now()
WHERE menu_code IN (
    'branding', 'smtp', 'sms', 'profile-fields', 'agreements', 'watermark',
    'maintenance', 'security', 'passkey', 'localization'
);

UPDATE platform_navigation_menu
SET deleted = true,
    version = version + 1,
    updated_at = now()
WHERE menu_code = 'system-settings'
  AND NOT EXISTS (
      SELECT 1
      FROM platform_navigation_menu child
      WHERE child.parent_id = platform_navigation_menu.id
        AND child.deleted = false
  );
