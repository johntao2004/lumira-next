-- owner=navigation
-- Keep platform navigation management after the system-configuration pages.
UPDATE platform_navigation_menu
SET sort_order = 120,
    version = version + 1,
    updated_at = now()
WHERE menu_code = 'navigation';
