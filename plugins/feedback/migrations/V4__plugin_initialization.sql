-- Final-state initialization for feedback on the platform B52 host.
-- Existing completed installations validate published history without replaying this SQL.

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

-- Keep historical feedback reads owner-only while retiring the old global read-all code.
-- V4 writes its source dispositions to the host migration archive and only materializes safe
-- permissions from live grants; archived or inert scoped action sources are never replayed.

DO $$
BEGIN
    IF to_regclass('public.platform_permission_migration_archive') IS NULL
       OR to_regclass('public.platform_permission_retired_authority') IS NULL
       OR to_regclass('public.platform_effective_authority') IS NULL
       OR to_regclass('public.platform_role_preview_context') IS NULL
       OR to_regprocedure('public.platform_normalize_legacy_permission_projections()') IS NULL THEN
        RAISE EXCEPTION 'feedback scope migration requires platform migration V51';
    END IF;
END
$$;

-- A durable completion marker makes replaying this SQL a no-op. Flyway remains the normal
-- execution path; the marker also prevents a manual retry from creating another user role.
CREATE TABLE IF NOT EXISTS plugin_feedback_permission_migration (
    migration_key character varying(120) PRIMARY KEY,
    completed_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
DECLARE
    feedback_access CONSTANT character varying(120) := 'platform:feedback:access';
    feedback_read CONSTANT character varying(120) := 'platform:feedback:read';
    feedback_read_all CONSTANT character varying(120) := 'platform:feedback:read-all';
    feedback_update CONSTANT character varying(120) := 'platform:feedback:update';
    feedback_reply CONSTANT character varying(120) := 'platform:feedback:reply';
    completion_key CONSTANT character varying(120) := 'V4_unify_feedback_data_scope';
BEGIN
    IF EXISTS (
        SELECT 1
          FROM plugin_feedback_permission_migration completed
         WHERE completed.migration_key = completion_key
    ) THEN
        RETURN;
    END IF;

    -- Capture role and direct grants before changing READ or retiring READ_ALL. In particular,
    -- this keeps an ALL-scope UPDATE/REPLY grant from being confused with a broad legacy READ
    -- when both occupied the same role.
    CREATE TEMPORARY TABLE lumira_feedback_v4_role_snapshot ON COMMIT DROP AS
    SELECT grants.role_id, grants.authority, role.data_scope, role.enabled AS role_enabled
      FROM platform_role_authority grants
      JOIN platform_role role ON role.id = grants.role_id
     WHERE grants.authority IN (
               feedback_access, feedback_read, feedback_read_all,
               feedback_update, feedback_reply
           );

    CREATE TEMPORARY TABLE lumira_feedback_v4_direct_snapshot ON COMMIT DROP AS
    SELECT grants.user_id, grants.authority
      FROM platform_authority grants
     WHERE grants.authority IN (
               feedback_access, feedback_read, feedback_read_all,
               feedback_update, feedback_reply
           );

    CREATE TEMPORARY TABLE lumira_feedback_v4_read_users (
        user_id uuid PRIMARY KEY
    ) ON COMMIT DROP;

    -- Only accounts that already had both effective page access and effective ordinary READ
    -- receive the supplemental SELF role. This snapshot is taken before global action grants
    -- below add READ, so action-only users never gain a page permission from this role.
    INSERT INTO lumira_feedback_v4_read_users(user_id)
    SELECT DISTINCT effective_read.user_id
      FROM platform_effective_authority effective_read
      JOIN platform_effective_authority effective_access
        ON effective_access.user_id = effective_read.user_id
       AND effective_access.authority = feedback_access
     WHERE effective_read.authority = feedback_read;

    CREATE TEMPORARY TABLE lumira_feedback_v4_affected_role (
        role_id uuid PRIMARY KEY
    ) ON COMMIT DROP;
    CREATE TEMPORARY TABLE lumira_feedback_v4_affected_user (
        user_id uuid PRIMARY KEY
    ) ON COMMIT DROP;
    CREATE TEMPORARY TABLE lumira_feedback_v4_generated_role (
        user_id uuid PRIMARY KEY,
        role_id uuid NOT NULL UNIQUE
    ) ON COMMIT DROP;

    -- Reuse the host audit archive. Keep the original source and its post-migration disposition;
    -- archived V51 projection rows are never read back as migration input.
    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT source.authority,
           source.authority,
           CASE
               WHEN source.authority = feedback_access THEN feedback_access
               WHEN source.authority = feedback_read AND source.data_scope = 'SELF'
                   THEN feedback_read
               WHEN source.authority = feedback_read_all AND source.data_scope = 'ALL'
                   THEN feedback_read
               WHEN source.authority IN (feedback_update, feedback_reply)
                    AND source.data_scope = 'ALL' THEN source.authority
               ELSE NULL
           END,
           'ROLE',
           source.role_id
      FROM lumira_feedback_v4_role_snapshot source
    ON CONFLICT DO NOTHING;

    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT source.authority,
           source.authority,
           CASE
               WHEN source.authority = feedback_access THEN feedback_access
               WHEN source.authority = feedback_read
                    AND EXISTS (
                        SELECT 1 FROM lumira_feedback_v4_read_users eligible
                         WHERE eligible.user_id = source.user_id
                    ) THEN feedback_read
               WHEN source.authority = feedback_read_all THEN feedback_read
               WHEN source.authority IN (feedback_update, feedback_reply) THEN source.authority
               ELSE NULL
           END,
           'USER',
           source.user_id
      FROM lumira_feedback_v4_direct_snapshot source
    ON CONFLICT DO NOTHING;

    -- Record the implicit ordinary read preserved by a globally effective legacy action.
    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT source.authority, feedback_read, feedback_read, 'ROLE', source.role_id
      FROM lumira_feedback_v4_role_snapshot source
     WHERE source.authority IN (feedback_read_all, feedback_update, feedback_reply)
       AND source.data_scope = 'ALL'
    UNION ALL
    SELECT source.authority, feedback_read, feedback_read, 'USER', source.user_id
      FROM lumira_feedback_v4_direct_snapshot source
     WHERE source.authority IN (feedback_read_all, feedback_update, feedback_reply)
    ON CONFLICT DO NOTHING;

    -- READ_ALL is retired as a permission name. UPDATE and REPLY remain independent permissions;
    -- old grants in a non-ALL role were ineffective and are deleted so they cannot become active
    -- merely because their scope semantics change later.
    INSERT INTO platform_permission_retired_authority(authority)
    VALUES (feedback_read_all)
    ON CONFLICT DO NOTHING;

    WITH removed AS (
        DELETE FROM platform_role_authority grants
         USING lumira_feedback_v4_role_snapshot source
         WHERE grants.role_id = source.role_id
           AND grants.authority = source.authority
           AND (
               source.authority = feedback_read_all
               OR (source.authority = feedback_read AND source.data_scope <> 'SELF')
               OR (source.authority IN (feedback_update, feedback_reply)
                   AND source.data_scope <> 'ALL')
           )
        RETURNING grants.role_id
    )
    INSERT INTO lumira_feedback_v4_affected_role(role_id)
    SELECT DISTINCT removed.role_id FROM removed
    ON CONFLICT DO NOTHING;

    WITH removed AS (
        DELETE FROM platform_authority grants
         USING lumira_feedback_v4_direct_snapshot source
         WHERE grants.user_id = source.user_id
           AND grants.authority = source.authority
           AND source.authority IN (feedback_read, feedback_read_all)
        RETURNING grants.user_id
    )
    INSERT INTO lumira_feedback_v4_affected_user(user_id)
    SELECT DISTINCT removed.user_id FROM removed
    ON CONFLICT DO NOTHING;

    -- Preserve implicit read only where the source action was historically global. Taking the
    -- values from the snapshot avoids deleting a READ just inserted for an ALL action in the same
    -- role.
    WITH added AS (
        INSERT INTO platform_role_authority(role_id, authority)
        SELECT DISTINCT source.role_id, feedback_read
          FROM lumira_feedback_v4_role_snapshot source
         WHERE source.data_scope = 'ALL'
           AND source.authority IN (feedback_read_all, feedback_update, feedback_reply)
        ON CONFLICT DO NOTHING
        RETURNING role_id
    )
    INSERT INTO lumira_feedback_v4_affected_role(role_id)
    SELECT DISTINCT added.role_id FROM added
    ON CONFLICT DO NOTHING;

    WITH added AS (
        INSERT INTO platform_authority(user_id, authority)
        SELECT DISTINCT source.user_id, feedback_read
          FROM lumira_feedback_v4_direct_snapshot source
         WHERE source.authority IN (feedback_read_all, feedback_update, feedback_reply)
        ON CONFLICT DO NOTHING
        RETURNING user_id
    )
    INSERT INTO lumira_feedback_v4_affected_user(user_id)
    SELECT DISTINCT added.user_id FROM added
    ON CONFLICT DO NOTHING;

    -- A surviving SELF READ or an ALL/direct READ materialized from a global legacy action
    -- already includes the owner's record. Do not add a second SELF grant that could survive when
    -- the original read source is later revoked.
    DELETE FROM lumira_feedback_v4_read_users eligible
     WHERE EXISTS (
         SELECT 1
           FROM platform_effective_authority remaining_read
          WHERE remaining_read.user_id = eligible.user_id
            AND remaining_read.authority = feedback_read
     );

    -- Generate one ordinary, independently editable SELF role per account. Its only grant is
    -- READ; the account keeps page authorization through its existing ACCESS source.
    IF EXISTS (
        SELECT 1
          FROM lumira_feedback_v4_read_users eligible
          JOIN platform_role existing
            ON existing.code = 'migrated-feedback-read-' || replace(eligible.user_id::text, '-', '')
    ) THEN
        RAISE EXCEPTION 'feedback migration role code is already in use';
    END IF;

    WITH inserted AS (
        INSERT INTO platform_role(id, code, name, enabled, data_scope)
        SELECT gen_random_uuid(),
               'migrated-feedback-read-' || replace(eligible.user_id::text, '-', ''),
               'Migrated feedback read ' || substr(eligible.user_id::text, 1, 8),
               true,
               'SELF'
          FROM lumira_feedback_v4_read_users eligible
        RETURNING id
    )
    INSERT INTO lumira_feedback_v4_affected_role(role_id)
    SELECT inserted.id FROM inserted
    ON CONFLICT DO NOTHING;

    INSERT INTO lumira_feedback_v4_generated_role(user_id, role_id)
    SELECT eligible.user_id, role.id
      FROM lumira_feedback_v4_read_users eligible
      JOIN platform_role role
        ON role.code = 'migrated-feedback-read-' || replace(eligible.user_id::text, '-', '');

    WITH added AS (
        INSERT INTO platform_role_authority(role_id, authority)
        SELECT generated.role_id, feedback_read
          FROM lumira_feedback_v4_generated_role generated
        ON CONFLICT DO NOTHING
        RETURNING role_id
    )
    INSERT INTO lumira_feedback_v4_affected_role(role_id)
    SELECT added.role_id FROM added
    ON CONFLICT DO NOTHING;

    WITH added AS (
        INSERT INTO platform_user_role(user_id, role_id)
        SELECT generated.user_id, generated.role_id
          FROM lumira_feedback_v4_generated_role generated
        ON CONFLICT DO NOTHING
        RETURNING user_id
    )
    INSERT INTO lumira_feedback_v4_affected_user(user_id)
    SELECT added.user_id FROM added
    ON CONFLICT DO NOTHING;

    INSERT INTO lumira_feedback_v4_affected_user(user_id)
    SELECT DISTINCT assigned.user_id
      FROM platform_user_role assigned
      JOIN lumira_feedback_v4_affected_role affected
        ON affected.role_id = assigned.role_id
    ON CONFLICT DO NOTHING;

    UPDATE platform_role role
       SET version = role.version + 1
      FROM lumira_feedback_v4_affected_role affected
     WHERE affected.role_id = role.id;

    UPDATE platform_user account
       SET version = account.version + 1
      FROM lumira_feedback_v4_affected_user affected
     WHERE affected.user_id = account.id;

    -- Invalidate role previews built from any affected actor's old authority snapshot.
    UPDATE platform_role_preview_context context
       SET revision = gen_random_uuid(),
           state = 'NONE',
           role_id = NULL,
           role_code = NULL,
           role_name = NULL,
           role_version = NULL,
           data_scope = NULL,
           started_at = NULL,
           expires_at = NULL,
           limited_by_actor = false,
           blocked_reason = NULL,
           actor_authority_digest = NULL,
           role_authority_digest = NULL,
           updated_at = CURRENT_TIMESTAMP
      FROM lumira_feedback_v4_affected_user affected
     WHERE affected.user_id = context.actor_id;

    INSERT INTO plugin_feedback_permission_migration(migration_key)
    VALUES (completion_key)
    ON CONFLICT DO NOTHING;
END
$$;
