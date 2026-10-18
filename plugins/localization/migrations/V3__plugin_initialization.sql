-- Final-state initialization for localization on the platform B52 host.
-- Existing completed installations validate published history without replaying this SQL.

-- The old aggregate is distinct from current operation codes. Preserve its scope while keeping
-- page access independent for grants that already target a single current operation.
WITH permission_map(source, target) AS (
    VALUES
        ('platform:localization:write', 'platform:localization:access'),
        ('platform:localization:write', 'platform:localization:create'),
        ('platform:localization:write', 'platform:localization:update'),
        ('platform:localization:write', 'platform:localization:delete'),
        ('platform:localization:write', 'platform:localization:publish')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:localization:write', 'platform:localization:access'),
        ('platform:localization:write', 'platform:localization:create'),
        ('platform:localization:write', 'platform:localization:update'),
        ('platform:localization:write', 'platform:localization:delete'),
        ('platform:localization:write', 'platform:localization:publish')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF to_regprocedure('public.platform_normalize_legacy_permission_projections()') IS NULL THEN
        RAISE EXCEPTION 'localization permission finalization requires platform migration V51';
    END IF;

    INSERT INTO platform_permission_retired_authority(authority)
    VALUES ('platform:localization:write')
    ON CONFLICT DO NOTHING;

    PERFORM public.platform_normalize_legacy_permission_projections();
END
$$;
