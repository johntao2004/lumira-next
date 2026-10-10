-- Retire the standalone AI test/catalog pages while keeping the shared provider
-- adapters and AI assistant APIs available to platform consumers.
UPDATE platform_navigation_menu
SET enabled = false,
    deleted = true,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE menu_code IN ('ai-capabilities', 'ai-judgment')
  AND built_in = true
  AND deleted = false;
