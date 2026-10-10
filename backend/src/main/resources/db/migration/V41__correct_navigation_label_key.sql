-- owner=platform
-- Align the built-in navigation entry with the localized navigation-management label.
UPDATE platform_navigation_menu
SET label_key = 'navigationManagement',
    version = version + 1,
    updated_at = now()
WHERE menu_code = 'navigation'
  AND built_in = true
  AND label_key = 'navigation';
