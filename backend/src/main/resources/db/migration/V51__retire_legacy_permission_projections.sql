-- Convert the V50 opaque compatibility grants to ordinary authorities once, then remove every
-- opaque grant from the runtime tables. Projection metadata remains available only so published
-- plugin migrations can replay their historical inserts and call the migration-only normalizer.

CREATE TABLE platform_permission_migration_archive (
    source_authority character varying(120) NOT NULL,
    logical_authority character varying(120) NOT NULL DEFAULT '',
    normalized_authority character varying(120),
    principal_kind character varying(8) NOT NULL,
    principal_id uuid NOT NULL,
    archived_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_permission_migration_archive_principal_check
        CHECK (principal_kind IN ('ROLE', 'USER')),
    CONSTRAINT platform_permission_migration_archive_pkey
        PRIMARY KEY (source_authority, logical_authority, principal_kind, principal_id)
);

CREATE OR REPLACE FUNCTION platform_normalize_legacy_permission_projections()
RETURNS void
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    logical_row record;
    role_row record;
    user_row record;
    generated_role_id uuid;
    generated_role_code character varying(100);
    generated_role_name character varying(200);
    operation_rows integer;
    direct_rank integer;
    existing_rank integer;
    preexisting_rank integer;
    requested_rank integer;
    requested_scope character varying(20);
    all_direct boolean;
BEGIN
    CREATE TEMPORARY TABLE IF NOT EXISTS lumira_permission_migration_affected_role (
        role_id uuid PRIMARY KEY
    ) ON COMMIT DROP;
    CREATE TEMPORARY TABLE IF NOT EXISTS lumira_permission_migration_affected_user (
        user_id uuid PRIMARY KEY
    ) ON COMMIT DROP;
    CREATE TEMPORARY TABLE IF NOT EXISTS lumira_permission_migration_preexisting_role_authority (
        role_id uuid NOT NULL,
        authority character varying(120) NOT NULL,
        PRIMARY KEY (role_id, authority)
    ) ON COMMIT DROP;
    CREATE TEMPORARY TABLE IF NOT EXISTS lumira_permission_migration_preexisting_user_authority (
        user_id uuid NOT NULL,
        authority character varying(120) NOT NULL,
        PRIMARY KEY (user_id, authority)
    ) ON COMMIT DROP;
    CREATE TEMPORARY TABLE IF NOT EXISTS lumira_permission_migration_generated_role (
        user_id uuid NOT NULL,
        data_scope character varying(20) NOT NULL,
        role_id uuid NOT NULL,
        PRIMARY KEY (user_id, data_scope)
    ) ON COMMIT DROP;
    TRUNCATE TABLE lumira_permission_migration_affected_role;
    TRUNCATE TABLE lumira_permission_migration_affected_user;
    TRUNCATE TABLE lumira_permission_migration_preexisting_role_authority;
    TRUNCATE TABLE lumira_permission_migration_preexisting_user_authority;
    TRUNCATE TABLE lumira_permission_migration_generated_role;
    INSERT INTO lumira_permission_migration_preexisting_role_authority(role_id, authority)
    SELECT grants.role_id, grants.authority
      FROM platform_role_authority grants
     WHERE grants.authority NOT LIKE 'platform:__v50:%'
       AND NOT EXISTS (
           SELECT 1 FROM platform_permission_retired_authority retired
            WHERE retired.authority = grants.authority
       )
    ON CONFLICT DO NOTHING;
    INSERT INTO lumira_permission_migration_preexisting_user_authority(user_id, authority)
    SELECT grants.user_id, grants.authority
      FROM platform_authority grants
     WHERE grants.authority NOT LIKE 'platform:__v50:%'
       AND NOT EXISTS (
           SELECT 1 FROM platform_permission_retired_authority retired
            WHERE retired.authority = grants.authority
       )
    ON CONFLICT DO NOTHING;

    -- V50 runtime views already exclude retired raw names. Archive and remove them without
    -- replaying their historical mappings, which would reactivate previously inert rows. Plugin
    -- finalizers materialize their own scoped/current grants before marking old names retired.
    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT old.authority, coalesce(mapping.new_authority, ''), NULL,
           'ROLE', old.role_id
      FROM platform_role_authority old
      JOIN platform_permission_retired_authority retired
        ON retired.authority = old.authority
      LEFT JOIN platform_permission_legacy_mapping mapping
        ON mapping.old_authority = old.authority
     WHERE old.authority NOT LIKE 'platform:__v50:%'
    ON CONFLICT DO NOTHING;

    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT old.authority, coalesce(mapping.new_authority, ''), NULL,
           'USER', old.user_id
      FROM platform_authority old
      JOIN platform_permission_retired_authority retired
        ON retired.authority = old.authority
      LEFT JOIN platform_permission_legacy_mapping mapping
        ON mapping.old_authority = old.authority
     WHERE old.authority NOT LIKE 'platform:__v50:%'
    ON CONFLICT DO NOTHING;

    INSERT INTO lumira_permission_migration_affected_role(role_id)
    SELECT DISTINCT old.role_id
      FROM platform_role_authority old
      JOIN platform_permission_retired_authority retired
        ON retired.authority = old.authority
    ON CONFLICT DO NOTHING;

    INSERT INTO lumira_permission_migration_affected_user(user_id)
    SELECT DISTINCT old.user_id
      FROM platform_authority old
      JOIN platform_permission_retired_authority retired
        ON retired.authority = old.authority
    ON CONFLICT DO NOTHING;

    DELETE FROM platform_role_authority old
     USING platform_permission_retired_authority retired
     WHERE retired.authority = old.authority
       AND old.authority NOT LIKE 'platform:__v50:%';

    DELETE FROM platform_authority old
     USING platform_permission_retired_authority retired
     WHERE retired.authority = old.authority
       AND old.authority NOT LIKE 'platform:__v50:%';

    -- Archive every known component association before converting it. The logical authority is
    -- retained as the migration target even when another missing component makes the group
    -- incomplete for a particular actor.
    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT grants.authority, component.logical_authority, component.logical_authority,
           'ROLE', grants.role_id
      FROM platform_role_authority grants
      JOIN platform_permission_projection_component component
        ON component.source_authority = grants.authority
    ON CONFLICT DO NOTHING;

    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT grants.authority, component.logical_authority, component.logical_authority,
           'USER', grants.user_id
      FROM platform_authority grants
      JOIN platform_permission_projection_component component
        ON component.source_authority = grants.authority
    ON CONFLICT DO NOTHING;

    -- Opaque tokens without a registered component are audit-only and are never guessed into an
    -- ordinary permission.
    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT grants.authority, '', NULL, 'ROLE', grants.role_id
      FROM platform_role_authority grants
     WHERE grants.authority LIKE 'platform:__v50:%'
       AND NOT EXISTS (
           SELECT 1 FROM platform_permission_projection_component component
            WHERE component.source_authority = grants.authority
       )
    ON CONFLICT DO NOTHING;

    INSERT INTO platform_permission_migration_archive(
        source_authority, logical_authority, normalized_authority, principal_kind, principal_id
    )
    SELECT grants.authority, '', NULL, 'USER', grants.user_id
      FROM platform_authority grants
     WHERE grants.authority LIKE 'platform:__v50:%'
       AND NOT EXISTS (
           SELECT 1 FROM platform_permission_projection_component component
            WHERE component.source_authority = grants.authority
       )
    ON CONFLICT DO NOTHING;

    -- Scope definitions live on roles. Preserve complete same-role definitions, including disabled
    -- roles, but do not expose a scoped role as a legacy global-gated operation.
    FOR logical_row IN
        SELECT DISTINCT logical_authority
          FROM platform_permission_projection_component
         ORDER BY logical_authority
    LOOP
        FOR role_row IN
            SELECT role.id, role.data_scope
              FROM platform_role role
             WHERE EXISTS (
                       SELECT 1
                         FROM platform_permission_projection_component component
                        WHERE component.logical_authority = logical_row.logical_authority
                   )
               AND NOT EXISTS (
                       SELECT 1
                         FROM platform_permission_projection_component component
                        WHERE component.logical_authority = logical_row.logical_authority
                          AND (
                              (component.source_requires_global AND role.data_scope <> 'ALL')
                              OR NOT EXISTS (
                                  SELECT 1
                                    FROM platform_role_authority source
                                   WHERE source.role_id = role.id
                                     AND source.authority = component.source_authority
                              )
                          )
                   )
             ORDER BY role.id
        LOOP
            -- V50 can report a scoped logical operation as effective when every source is
            -- present, even though no target-scope source exists. Such an operation had no
            -- visible targets unless a global source made it ALL. Flattening it onto a scoped
            -- role would grant new SELF/organization/subtree visibility. Built-in metadata does
            -- not use this shape; fail the migration if future metadata does.
            IF role_row.data_scope <> 'ALL'
               AND NOT EXISTS (
                   SELECT 1
                     FROM platform_permission_projection_component component
                    WHERE component.logical_authority = logical_row.logical_authority
                      AND component.target_scope_source
               )
               AND EXISTS (
                   SELECT 1
                     FROM platform_permission_projection_component component
                    WHERE component.logical_authority = logical_row.logical_authority
                      AND NOT component.target_scope_source
                      AND NOT component.source_requires_global
               )
               AND NOT EXISTS (
                   SELECT 1
                     FROM lumira_permission_migration_preexisting_role_authority existing
                    WHERE existing.role_id = role_row.id
                      AND existing.authority = logical_row.logical_authority
               ) THEN
                RAISE EXCEPTION
                    'Cannot safely flatten scoped non-target legacy permission % on role %',
                    logical_row.logical_authority,
                    role_row.id;
            END IF;

            INSERT INTO platform_role_authority(role_id, authority)
            VALUES (role_row.id, logical_row.logical_authority)
            ON CONFLICT DO NOTHING;
            GET DIAGNOSTICS operation_rows = ROW_COUNT;
            IF operation_rows > 0 THEN
                INSERT INTO lumira_permission_migration_affected_role(role_id)
                VALUES (role_row.id)
                ON CONFLICT DO NOTHING;
            END IF;
        END LOOP;
    END LOOP;

    -- Direct-only complete source groups remain direct ALL grants. Mixed direct/role and
    -- cross-role residuals are synthesized as ordinary editable roles below.
    FOR user_row IN
        WITH component_state AS (
            SELECT account.id AS user_id,
                   component.logical_authority,
                   component.target_scope_source,
                   component.source_authority,
                   greatest(
                       CASE WHEN EXISTS (
                           SELECT 1 FROM platform_authority direct_source
                            WHERE direct_source.user_id = account.id
                              AND direct_source.authority = component.source_authority
                       ) THEN 4 ELSE 0 END,
                       coalesce((
                           SELECT max(CASE role.data_scope
                               WHEN 'SELF' THEN 1
                               WHEN 'ORGANIZATION' THEN 2
                               WHEN 'SUBTREE' THEN 3
                               WHEN 'ALL' THEN 4
                               ELSE 0
                           END)
                             FROM platform_user_role assigned
                             JOIN platform_role role
                               ON role.id = assigned.role_id AND role.enabled
                             JOIN platform_role_authority source
                               ON source.role_id = role.id
                              AND source.authority = component.source_authority
                            WHERE assigned.user_id = account.id
                              AND (NOT component.source_requires_global
                                   OR role.data_scope = 'ALL')
                       ), 0)
                   ) AS eligible_scope_rank,
                   EXISTS (
                       SELECT 1 FROM platform_authority direct_source
                        WHERE direct_source.user_id = account.id
                          AND direct_source.authority = component.source_authority
                   ) AS direct_source
              FROM platform_user account
              CROSS JOIN platform_permission_projection_component component
        ), logical_state AS (
            SELECT user_id, logical_authority,
                   bool_and(eligible_scope_rank > 0) AS complete,
                   bool_and(direct_source) AS all_direct,
                   bool_or(target_scope_source) AS has_target_scope_source,
                   min(eligible_scope_rank) FILTER (WHERE target_scope_source) AS target_scope_rank,
                   min(eligible_scope_rank) AS all_component_rank
              FROM component_state
             GROUP BY user_id, logical_authority
        )
        SELECT state.user_id, state.logical_authority,
               CASE WHEN state.has_target_scope_source
                    THEN state.target_scope_rank
                    ELSE state.all_component_rank
               END AS desired_scope_rank,
               state.has_target_scope_source,
               state.target_scope_rank,
               state.all_component_rank,
               state.all_direct,
               greatest(
                   CASE WHEN EXISTS (
                       SELECT 1 FROM platform_authority existing_direct
                        WHERE existing_direct.user_id = state.user_id
                          AND existing_direct.authority = state.logical_authority
                   ) THEN 4 ELSE 0 END,
                   coalesce((
                       SELECT max(CASE role.data_scope
                           WHEN 'SELF' THEN 1
                           WHEN 'ORGANIZATION' THEN 2
                           WHEN 'SUBTREE' THEN 3
                           WHEN 'ALL' THEN 4
                           ELSE 0
                       END)
                         FROM platform_user_role assigned
                         JOIN platform_role role
                           ON role.id = assigned.role_id AND role.enabled
                         JOIN platform_role_authority existing_role
                           ON existing_role.role_id = role.id
                          AND existing_role.authority = state.logical_authority
                        WHERE assigned.user_id = state.user_id
                   ), 0)
               ) AS existing_scope_rank,
               greatest(
                   CASE WHEN EXISTS (
                       SELECT 1 FROM lumira_permission_migration_preexisting_user_authority existing_direct
                        WHERE existing_direct.user_id = state.user_id
                          AND existing_direct.authority = state.logical_authority
                   ) THEN 4 ELSE 0 END,
                   coalesce((
                       SELECT max(CASE role.data_scope
                           WHEN 'SELF' THEN 1
                           WHEN 'ORGANIZATION' THEN 2
                           WHEN 'SUBTREE' THEN 3
                           WHEN 'ALL' THEN 4
                           ELSE 0
                       END)
                         FROM platform_user_role assigned
                         JOIN platform_role role
                           ON role.id = assigned.role_id AND role.enabled
                         JOIN lumira_permission_migration_preexisting_role_authority existing_role
                           ON existing_role.role_id = role.id
                          AND existing_role.authority = state.logical_authority
                        WHERE assigned.user_id = state.user_id
                   ), 0)
               ) AS preexisting_scope_rank,
               EXISTS (
                   SELECT 1
                     FROM platform_permission_projection_component component
                    WHERE component.logical_authority = state.logical_authority
                      AND NOT component.target_scope_source
                      AND NOT component.source_requires_global
               ) AS unsupported_nontarget_scope
          FROM logical_state state
         WHERE state.complete
         ORDER BY state.user_id, state.logical_authority
    LOOP
        requested_rank := user_row.desired_scope_rank;
        existing_rank := user_row.existing_scope_rank;
        preexisting_rank := user_row.preexisting_scope_rank;

        IF requested_rank IS NULL OR requested_rank < 1 OR requested_rank > 4 THEN
            RAISE EXCEPTION 'Invalid legacy permission scope for %', user_row.logical_authority;
        END IF;

        -- Without target-scope components, V50 exposed target visibility only for an ALL grant.
        -- A scoped logical grant here would turn an empty legacy target set into SELF/ORG access.
        IF NOT user_row.has_target_scope_source
           AND user_row.unsupported_nontarget_scope
           AND requested_rank < 4
           AND preexisting_rank < requested_rank THEN
            RAISE EXCEPTION
                'Cannot safely flatten scoped non-target legacy permission % for user %',
                user_row.logical_authority,
                user_row.user_id;
        END IF;

        -- A target source may independently grant ALL visibility while another non-global
        -- prerequisite exists only at a narrower scope. V50 then had ALL target visibility but
        -- did not report the logical operation globally. Flattening that tuple to an ALL grant
        -- would change the global decision. Narrow target scopes remain representable because
        -- their ordinary role scope preserves the same target predicate.
        IF user_row.has_target_scope_source
           AND user_row.target_scope_rank = 4
           AND user_row.all_component_rank < 4
           AND preexisting_rank < 4 THEN
            RAISE EXCEPTION
                'Cannot safely flatten mixed global legacy permission % for user %',
                user_row.logical_authority,
                user_row.user_id;
        END IF;

        IF existing_rank >= requested_rank THEN
            CONTINUE;
        END IF;

        IF user_row.all_direct THEN
            INSERT INTO platform_authority(user_id, authority)
            VALUES (user_row.user_id, user_row.logical_authority)
            ON CONFLICT DO NOTHING;
            GET DIAGNOSTICS operation_rows = ROW_COUNT;
            IF operation_rows > 0 THEN
                INSERT INTO lumira_permission_migration_affected_user(user_id)
                VALUES (user_row.user_id)
                ON CONFLICT DO NOTHING;
            END IF;
            CONTINUE;
        END IF;

        requested_scope := CASE requested_rank
            WHEN 1 THEN 'SELF'
            WHEN 2 THEN 'ORGANIZATION'
            WHEN 3 THEN 'SUBTREE'
            WHEN 4 THEN 'ALL'
            ELSE NULL
        END;
        -- Create one editable role per user/scope for this pass. Never reuse a role by a
        -- deterministic code: it may have been assigned to other accounts since creation.
        SELECT generated.role_id INTO generated_role_id
          FROM lumira_permission_migration_generated_role generated
         WHERE generated.user_id = user_row.user_id
           AND generated.data_scope = requested_scope;
        IF generated_role_id IS NULL THEN
            generated_role_id := gen_random_uuid();
            generated_role_code :=
                'migrated-permissions-' || replace(generated_role_id::text, '-', '')
                || '-' || lower(requested_scope);
            generated_role_name :=
                'Migrated permissions ' || substr(generated_role_id::text, 1, 8)
                || ' (' || requested_scope || ')';

            INSERT INTO platform_role(
                id, code, name, enabled, data_scope,
                file_quota_bytes_limit, file_quota_files_limit
            ) VALUES (
                generated_role_id, generated_role_code, generated_role_name, true, requested_scope,
                NULL, NULL
            );
            INSERT INTO lumira_permission_migration_generated_role(user_id, data_scope, role_id)
            VALUES (user_row.user_id, requested_scope, generated_role_id);
        END IF;

        INSERT INTO platform_role_authority(role_id, authority)
        VALUES (generated_role_id, user_row.logical_authority)
        ON CONFLICT DO NOTHING;
        GET DIAGNOSTICS operation_rows = ROW_COUNT;
        IF operation_rows > 0 THEN
            INSERT INTO lumira_permission_migration_affected_role(role_id)
            VALUES (generated_role_id)
            ON CONFLICT DO NOTHING;
        END IF;

        INSERT INTO platform_user_role(user_id, role_id)
        VALUES (user_row.user_id, generated_role_id)
        ON CONFLICT DO NOTHING;
        GET DIAGNOSTICS operation_rows = ROW_COUNT;
        IF operation_rows > 0 THEN
            INSERT INTO lumira_permission_migration_affected_user(user_id)
            VALUES (user_row.user_id)
            ON CONFLICT DO NOTHING;
        END IF;
    END LOOP;

    -- Remove every opaque runtime grant, including unknown/incomplete sources. Metadata itself is
    -- retained for append-only plugin finalizers and is never consulted by authorization views.
    INSERT INTO lumira_permission_migration_affected_role(role_id)
    SELECT DISTINCT role_id
      FROM platform_role_authority
     WHERE authority LIKE 'platform:__v50:%'
    ON CONFLICT DO NOTHING;
    INSERT INTO lumira_permission_migration_affected_user(user_id)
    SELECT DISTINCT user_id
      FROM platform_authority
     WHERE authority LIKE 'platform:__v50:%'
    ON CONFLICT DO NOTHING;

    DELETE FROM platform_role_authority WHERE authority LIKE 'platform:__v50:%';
    DELETE FROM platform_authority WHERE authority LIKE 'platform:__v50:%';

    INSERT INTO lumira_permission_migration_affected_user(user_id)
    SELECT DISTINCT assigned.user_id
      FROM platform_user_role assigned
      JOIN lumira_permission_migration_affected_role affected
        ON affected.role_id = assigned.role_id
    ON CONFLICT DO NOTHING;

    UPDATE platform_role role
       SET version = role.version + 1
      FROM lumira_permission_migration_affected_role affected
     WHERE affected.role_id = role.id;

    UPDATE platform_user account
       SET version = account.version + 1
      FROM lumira_permission_migration_affected_user affected
     WHERE affected.user_id = account.id;

    -- Keep sessions intact while invalidating saved role previews built against old grants.
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
      FROM lumira_permission_migration_affected_user affected
     WHERE affected.user_id = context.actor_id;
END;
$$;

REVOKE ALL ON FUNCTION platform_normalize_legacy_permission_projections() FROM PUBLIC;

SELECT platform_normalize_legacy_permission_projections();

-- Runtime authority is now entirely represented by ordinary direct and role grants.
CREATE OR REPLACE FUNCTION platform_role_logical_authorities(target_role_id uuid)
RETURNS TABLE(authority text)
LANGUAGE sql STABLE
AS $$
SELECT grants.authority::text
  FROM platform_role_authority grants
 WHERE grants.role_id = target_role_id
   AND grants.authority NOT LIKE 'platform:__v50:%'
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_retired_authority retired
        WHERE retired.authority = grants.authority
   )
$$;

CREATE OR REPLACE VIEW platform_effective_authority AS
SELECT grants.user_id, grants.authority::varchar
  FROM platform_authority grants
 WHERE grants.authority NOT LIKE 'platform:__v50:%'
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_retired_authority retired
        WHERE retired.authority = grants.authority
   )
UNION
SELECT assigned.user_id, grants.authority::varchar
  FROM platform_user_role assigned
  JOIN platform_role role ON role.id = assigned.role_id AND role.enabled
  JOIN platform_role_authority grants ON grants.role_id = role.id
 WHERE grants.authority NOT LIKE 'platform:__v50:%'
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_retired_authority retired
        WHERE retired.authority = grants.authority
   );

CREATE OR REPLACE VIEW platform_global_authority AS
SELECT grants.user_id, grants.authority::varchar
  FROM platform_authority grants
 WHERE grants.authority NOT LIKE 'platform:__v50:%'
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_retired_authority retired
        WHERE retired.authority = grants.authority
   )
UNION
SELECT assigned.user_id, grants.authority::varchar
  FROM platform_user_role assigned
  JOIN platform_role role
    ON role.id = assigned.role_id AND role.enabled AND role.data_scope = 'ALL'
  JOIN platform_role_authority grants ON grants.role_id = role.id
 WHERE grants.authority NOT LIKE 'platform:__v50:%'
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_retired_authority retired
        WHERE retired.authority = grants.authority
   );

CREATE OR REPLACE FUNCTION platform_visible_users_base(actor_name text, permission_name text)
RETURNS TABLE(user_id uuid)
LANGUAGE sql STABLE
AS $$
WITH RECURSIVE actor AS (
    SELECT id FROM platform_user WHERE username = actor_name AND enabled
), scopes AS (
    SELECT role.data_scope
      FROM platform_user_role assigned
      JOIN actor ON actor.id = assigned.user_id
      JOIN platform_role role ON role.id = assigned.role_id AND role.enabled
      JOIN platform_role_authority grants
        ON grants.role_id = role.id AND grants.authority = permission_name
), own_orgs AS (
    SELECT organization.id
      FROM platform_user_organization membership
      JOIN actor ON actor.id = membership.user_id
      JOIN platform_organization organization
        ON organization.id = membership.organization_id AND organization.enabled
), descendants AS (
    SELECT id FROM own_orgs
    UNION
    SELECT organization.id
      FROM platform_organization organization
      JOIN descendants parent ON organization.parent_id = parent.id
     WHERE organization.enabled
), global_scope AS (
    SELECT EXISTS (
               SELECT 1 FROM platform_authority direct
               JOIN actor ON actor.id = direct.user_id
                WHERE direct.authority = permission_name
           ) OR EXISTS (SELECT 1 FROM scopes WHERE data_scope = 'ALL') AS allowed
)
SELECT target.id
  FROM platform_user target
 WHERE target.deleted_at IS NULL
   AND permission_name NOT LIKE 'platform:__v50:%'
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_retired_authority retired
        WHERE retired.authority = permission_name
   )
   AND (
       (SELECT allowed FROM global_scope)
       OR (target.id IN (SELECT id FROM actor) AND EXISTS (SELECT 1 FROM scopes))
       OR EXISTS (
           SELECT 1 FROM platform_user_organization membership
            WHERE membership.user_id = target.id
              AND (
                  (EXISTS (SELECT 1 FROM scopes WHERE data_scope = 'ORGANIZATION')
                      AND membership.organization_id IN (SELECT id FROM own_orgs))
                  OR (EXISTS (SELECT 1 FROM scopes WHERE data_scope = 'SUBTREE')
                      AND membership.organization_id IN (SELECT id FROM descendants))
              )
       )
   )
$$;

CREATE OR REPLACE FUNCTION platform_visible_users(actor_name text, permission_name text)
RETURNS TABLE(user_id uuid)
LANGUAGE sql STABLE
AS $$
SELECT visible.user_id
  FROM platform_visible_users_base(actor_name, permission_name) visible
$$;

CREATE OR REPLACE FUNCTION platform_role_preview_visible_users(
    actor_name text,
    permission_name text,
    target_role_id uuid,
    target_role_version bigint
) RETURNS TABLE(user_id uuid)
LANGUAGE sql STABLE
AS $$
WITH RECURSIVE actor AS (
    SELECT id FROM platform_user
     WHERE username = actor_name AND enabled AND deleted_at IS NULL
), target AS (
    SELECT role.data_scope
      FROM platform_role role
     WHERE role.id = target_role_id AND role.enabled AND role.version = target_role_version
       AND EXISTS (
           SELECT 1 FROM platform_role_authority grants
            WHERE grants.role_id = role.id
              AND grants.authority = permission_name
              AND grants.authority NOT LIKE 'platform:__v50:%'
              AND NOT EXISTS (
                  SELECT 1 FROM platform_permission_retired_authority retired
                   WHERE retired.authority = grants.authority
              )
       )
), own_orgs AS (
    SELECT organization.id
      FROM platform_user_organization membership
      JOIN actor ON actor.id = membership.user_id
      JOIN platform_organization organization
        ON organization.id = membership.organization_id AND organization.enabled
), descendants AS (
    SELECT id FROM own_orgs
    UNION
    SELECT organization.id
      FROM platform_organization organization
      JOIN descendants parent ON organization.parent_id = parent.id
     WHERE organization.enabled
), target_visible AS (
    SELECT target_user.id
      FROM platform_user target_user
      CROSS JOIN target scope
     WHERE target_user.deleted_at IS NULL
       AND (
           scope.data_scope = 'ALL'
           OR target_user.id IN (SELECT id FROM actor)
           OR (scope.data_scope IN ('ORGANIZATION', 'SUBTREE') AND EXISTS (
               SELECT 1 FROM platform_user_organization membership
                WHERE membership.user_id = target_user.id
                  AND membership.organization_id IN (
                      SELECT id FROM own_orgs
                      UNION
                      SELECT id FROM descendants WHERE scope.data_scope = 'SUBTREE'
                  )
           ))
       )
)
SELECT real.user_id
  FROM platform_visible_users(actor_name, permission_name) real
 WHERE target_role_id IS NULL
UNION
SELECT real.user_id
  FROM platform_visible_users(actor_name, permission_name) real
  JOIN target_visible target ON target.id = real.user_id
$$;
