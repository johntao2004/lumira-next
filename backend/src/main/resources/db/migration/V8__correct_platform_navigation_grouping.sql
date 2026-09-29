-- owner=navigation
-- Keep platform modules and navigation management alongside the system-settings
-- catalog under Platform Management. They are platform administration entries,
-- not system configuration pages.
UPDATE platform_navigation_menu
SET parent_id = (
        SELECT id
        FROM platform_navigation_menu
        WHERE menu_code = 'administration'
    ),
    sort_order = CASE menu_code
        WHEN 'modules' THEN 10
        WHEN 'navigation' THEN 30
    END,
    version = version + 1,
    updated_at = now()
WHERE menu_code IN ('modules', 'navigation');

UPDATE platform_navigation_menu
SET sort_order = 20,
    version = version + 1,
    updated_at = now()
WHERE menu_code = 'system-settings';
