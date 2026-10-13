-- The old aggregate is distinct from the current action codes. Preserve it as the former manager
-- capability, while leaving independent read-all/update/reply grants untouched.
WITH permission_map(source, target) AS (
    VALUES
        ('platform:feedback:manage', 'platform:feedback:access'),
        ('platform:feedback:manage', 'platform:feedback:read-all'),
        ('platform:feedback:manage', 'platform:feedback:update'),
        ('platform:feedback:manage', 'platform:feedback:reply')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:feedback:manage', 'platform:feedback:access'),
        ('platform:feedback:manage', 'platform:feedback:read-all'),
        ('platform:feedback:manage', 'platform:feedback:update'),
        ('platform:feedback:manage', 'platform:feedback:reply')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF to_regprocedure('public.platform_normalize_legacy_permission_projections()') IS NULL THEN
        RAISE EXCEPTION 'feedback permission finalization requires platform migration V51';
    END IF;

    INSERT INTO platform_permission_retired_authority(authority)
    VALUES ('platform:feedback:manage')
    ON CONFLICT DO NOTHING;

    PERFORM public.platform_normalize_legacy_permission_projections();
END
$$;
