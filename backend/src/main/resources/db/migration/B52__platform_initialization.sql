-- Lumira platform B52 initialization baseline
-- PostgreSQL 18; consolidated final state of historical migrations V1..V52.
-- Includes only stable platform initialization seeds; no users, business records, or secrets.
-- Volatile seed revisions and timestamps are normalized to deterministic values.

--
-- PostgreSQL database dump
--


-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: platform_bump_sensitive_word_revision(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_bump_sensitive_word_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE public.platform_sensitive_word_revision
       SET revision = revision + 1
     WHERE singleton = true;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: platform_file_track_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_file_track_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.owner_id IS NOT NULL THEN
  NEW.orphaned_at=NULL;
 ELSIF TG_OP='INSERT' THEN
  NEW.orphaned_at=clock_timestamp();
 ELSIF OLD.owner_id IS NOT NULL OR NEW.orphaned_at IS NULL THEN
  NEW.orphaned_at=clock_timestamp();
 END IF;
 RETURN NEW;
END $$;


--
-- Name: platform_has_recovery_administrators(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_has_recovery_administrators() RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
SELECT EXISTS (
    SELECT 1 FROM platform_recovery_capability capability
    JOIN platform_user account ON account.id = capability.user_id
    WHERE account.enabled AND account.deleted_at IS NULL
)
$$;


--
-- Name: platform_is_last_recovery_administrator(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_is_last_recovery_administrator(target uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
SELECT EXISTS (
    SELECT 1 FROM platform_recovery_capability own
     WHERE own.user_id = target
       AND NOT EXISTS (
           SELECT 1 FROM platform_recovery_capability other
           JOIN platform_user account ON account.id = other.user_id
            WHERE account.enabled AND account.deleted_at IS NULL
              AND account.id <> target
       )
)
$$;


--
-- Name: platform_normalize_legacy_permission_projections(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_normalize_legacy_permission_projections() RETURNS void
    LANGUAGE plpgsql
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


--
-- Name: platform_role_logical_authorities(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_role_logical_authorities(target_role_id uuid) RETURNS TABLE(authority text)
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


--
-- Name: platform_role_preview_visible_organizations(text, text, uuid, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_role_preview_visible_organizations(actor_name text, permission_name text, target_role_id uuid, target_role_version bigint) RETURNS TABLE(organization_id uuid)
    LANGUAGE sql STABLE
    AS $$
WITH RECURSIVE actor AS (
    SELECT id FROM platform_user WHERE username = actor_name AND enabled AND deleted_at IS NULL
), target AS (
    SELECT r.data_scope
      FROM platform_role r
      JOIN platform_role_authority a ON a.role_id = r.id AND a.authority = permission_name
     WHERE r.id = target_role_id AND r.enabled AND r.version = target_role_version
), own_orgs AS (
    SELECT o.id
      FROM platform_user_organization uo
      JOIN actor a ON a.id = uo.user_id
      JOIN platform_organization o ON o.id = uo.organization_id AND o.enabled
), descendants AS (
    SELECT id FROM own_orgs
    UNION
    SELECT o.id
      FROM platform_organization o
      JOIN descendants d ON o.parent_id = d.id
     WHERE o.enabled
), target_visible AS (
    SELECT o.id
      FROM platform_organization o
      CROSS JOIN target t
     WHERE o.enabled
       AND (t.data_scope = 'ALL'
         OR (t.data_scope IN ('SELF', 'ORGANIZATION') AND o.id IN (SELECT id FROM own_orgs))
         OR (t.data_scope = 'SUBTREE' AND o.id IN (SELECT id FROM descendants)))
)
SELECT real.organization_id
  FROM platform_visible_organizations(actor_name, permission_name) real
 WHERE target_role_id IS NULL
UNION
SELECT real.organization_id
  FROM platform_visible_organizations(actor_name, permission_name) real
  JOIN target_visible target ON target.id = real.organization_id
$$;


--
-- Name: platform_role_preview_visible_users(text, text, uuid, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_role_preview_visible_users(actor_name text, permission_name text, target_role_id uuid, target_role_version bigint) RETURNS TABLE(user_id uuid)
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


--
-- Name: platform_visible_organizations(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_visible_organizations(actor_name text, permission_name text) RETURNS TABLE(organization_id uuid)
    LANGUAGE sql STABLE
    AS $$
 WITH RECURSIVE actor AS (
   SELECT id FROM platform_user WHERE username=actor_name AND enabled
 ), scopes AS (
   SELECT r.data_scope FROM platform_user_role ur JOIN actor a ON a.id=ur.user_id
   JOIN platform_role r ON r.id=ur.role_id AND r.enabled
   JOIN platform_role_authority ra ON ra.role_id=r.id AND ra.authority=permission_name
 ), own_orgs AS (
   SELECT o.id FROM platform_user_organization uo JOIN actor a ON a.id=uo.user_id
   JOIN platform_organization o ON o.id=uo.organization_id AND o.enabled
 ), descendants AS (
   SELECT id FROM own_orgs
   UNION
   SELECT o.id FROM platform_organization o JOIN descendants d ON o.parent_id=d.id WHERE o.enabled
 ), global_scope AS (
   SELECT EXISTS(SELECT 1 FROM platform_authority pa JOIN actor a ON a.id=pa.user_id WHERE pa.authority=permission_name)
     OR EXISTS(SELECT 1 FROM scopes WHERE data_scope='ALL') AS allowed
 )
 SELECT o.id
 FROM platform_organization o
 WHERE (
     (SELECT allowed FROM global_scope)
     OR o.id IN (SELECT id FROM own_orgs) AND EXISTS(SELECT 1 FROM scopes WHERE data_scope IN ('SELF','ORGANIZATION'))
     OR o.id IN (SELECT id FROM descendants) AND EXISTS(SELECT 1 FROM scopes WHERE data_scope='SUBTREE')
   );
$$;


--
-- Name: platform_visible_users(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_visible_users(actor_name text, permission_name text) RETURNS TABLE(user_id uuid)
    LANGUAGE sql STABLE
    AS $$
SELECT visible.user_id
  FROM platform_visible_users_base(actor_name, permission_name) visible
$$;


--
-- Name: platform_visible_users_base(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_visible_users_base(actor_name text, permission_name text) RETURNS TABLE(user_id uuid)
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


SET default_table_access_method = heap;

--
-- Name: platform_account_agreement_acceptance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_agreement_acceptance (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    request_id uuid NOT NULL,
    accepted_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: platform_account_draft; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_draft (
    id uuid NOT NULL,
    owner_id uuid NOT NULL,
    draft_key character varying(128) NOT NULL,
    revision uuid NOT NULL,
    payload text NOT NULL,
    payload_bytes integer NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_account_draft_check CHECK (((payload_bytes >= 1) AND (payload_bytes <= 1000000) AND (payload_bytes = octet_length(payload)))),
    CONSTRAINT platform_account_draft_draft_key_check CHECK (((draft_key)::text ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'::text))
);


--
-- Name: platform_account_email; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_email (
    user_id uuid NOT NULL,
    email character varying(254),
    verified_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    pending_email character varying(254),
    token_hash character varying(64),
    expires_at timestamp with time zone,
    requested_at timestamp with time zone,
    request_day date,
    request_count integer DEFAULT 0 NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    CONSTRAINT platform_account_email_check CHECK (((email IS NULL) = (verified_at IS NULL))),
    CONSTRAINT platform_account_email_check1 CHECK (((pending_email IS NULL) = (token_hash IS NULL))),
    CONSTRAINT platform_account_email_check2 CHECK (((pending_email IS NULL) = (expires_at IS NULL))),
    CONSTRAINT platform_account_email_failures_check CHECK (((failures >= 0) AND (failures <= 20))),
    CONSTRAINT platform_account_email_request_count_check CHECK (((request_count >= 0) AND (request_count <= 10000))),
    CONSTRAINT platform_account_email_token_hash_check CHECK (((token_hash IS NULL) OR ((token_hash)::text ~ '^[a-f0-9]{64}$'::text))),
    CONSTRAINT platform_account_email_version_check CHECK ((version >= 0))
);


--
-- Name: platform_account_media; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_media (
    user_id uuid NOT NULL,
    avatar_file_id uuid,
    version bigint DEFAULT 0 NOT NULL,
    file_state character varying(20) GENERATED ALWAYS AS ('READY'::character varying) STORED,
    media_type character varying(100) GENERATED ALWAYS AS ('image/png'::character varying) STORED,
    CONSTRAINT platform_account_media_version_check CHECK ((version >= 0))
);


--
-- Name: platform_account_phone; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_phone (
    user_id uuid NOT NULL,
    phone character varying(16),
    verified_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    pending_phone character varying(16),
    token_hash character varying(64),
    expires_at timestamp with time zone,
    requested_at timestamp with time zone,
    request_day date,
    request_count integer DEFAULT 0 NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    CONSTRAINT platform_account_phone_check CHECK (((phone IS NULL) = (verified_at IS NULL))),
    CONSTRAINT platform_account_phone_check1 CHECK (((pending_phone IS NULL) = (token_hash IS NULL))),
    CONSTRAINT platform_account_phone_check2 CHECK (((pending_phone IS NULL) = (expires_at IS NULL))),
    CONSTRAINT platform_account_phone_failures_check CHECK (((failures >= 0) AND (failures <= 20))),
    CONSTRAINT platform_account_phone_request_count_check CHECK (((request_count >= 0) AND (request_count <= 10000))),
    CONSTRAINT platform_account_phone_token_hash_check CHECK (((token_hash IS NULL) OR ((token_hash)::text ~ '^[a-f0-9]{64}$'::text))),
    CONSTRAINT platform_account_phone_version_check CHECK ((version >= 0))
);


--
-- Name: platform_agreement_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_agreement_snapshot (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version bigint NOT NULL,
    user_agreement_markdown text NOT NULL,
    privacy_agreement_markdown text NOT NULL,
    content_hash character(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_agreement_snapshot_content_hash_check CHECK ((content_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_agreement_snapshot_privacy_size_check CHECK ((octet_length(privacy_agreement_markdown) <= 100000)),
    CONSTRAINT platform_agreement_snapshot_user_size_check CHECK ((octet_length(user_agreement_markdown) <= 100000)),
    CONSTRAINT platform_agreement_snapshot_version_check CHECK ((version >= 1))
);


--
-- Name: platform_ai_conversation_share; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_ai_conversation_share (
    id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    title character varying(200) NOT NULL,
    messages jsonb NOT NULL,
    created_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_ai_conversation_share_token_hash_check CHECK (((token_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT platform_ai_conversation_share_title_check CHECK (((length(btrim((title)::text)) >= 1) AND (length(btrim((title)::text)) <= 200))),
    CONSTRAINT platform_ai_conversation_share_messages_check CHECK ((CASE WHEN (jsonb_typeof(messages) = 'array'::text) THEN ((jsonb_array_length(messages) >= 1) AND (jsonb_array_length(messages) <= 200)) ELSE false END))
);


--
-- Name: platform_ai_pending_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_ai_pending_action (
    id uuid NOT NULL,
    actor character varying(100) NOT NULL,
    kind character varying(32) NOT NULL,
    user_id uuid,
    version bigint DEFAULT 0 NOT NULL,
    username character varying(100),
    display_name character varying(200),
    enabled boolean,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    target_username character varying(100),
    target_display_name character varying(200),
    target_enabled boolean,
    CONSTRAINT platform_ai_pending_action_kind_check CHECK (((kind)::text = ANY (ARRAY[('CREATE_USER'::character varying)::text, ('UPDATE_USER'::character varying)::text, ('DELETE_USER'::character varying)::text]))),
    CONSTRAINT platform_ai_pending_action_target_snapshot_check CHECK ((((target_username IS NULL) AND (target_display_name IS NULL) AND (target_enabled IS NULL)) OR ((target_username IS NOT NULL) AND (target_display_name IS NOT NULL) AND (target_enabled IS NOT NULL)))),
    CONSTRAINT platform_ai_pending_action_version_check CHECK ((version >= 0))
);


--
-- Name: platform_ai_provider; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_ai_provider (
    id uuid NOT NULL,
    name character varying(120) NOT NULL,
    provider_kind character varying(32) NOT NULL,
    base_url character varying(512) NOT NULL,
    model_name character varying(256) NOT NULL,
    capability_codes jsonb DEFAULT '[]'::jsonb NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_ai_provider_capabilities_ck CHECK ((jsonb_typeof(capability_codes) = 'array'::text)),
    CONSTRAINT platform_ai_provider_kind_ck CHECK (((provider_kind)::text = ANY ((ARRAY['OLLAMA'::character varying, 'LM_STUDIO'::character varying, 'OPENAI'::character varying, 'DEEPSEEK'::character varying, 'QWEN'::character varying, 'ZHIPU'::character varying, 'SILICONFLOW'::character varying, 'OPENROUTER'::character varying, 'MOONSHOT'::character varying, 'CUSTOM_OPENAI'::character varying, 'TYPESAFE_JEV'::character varying])::text[]))),
    CONSTRAINT platform_ai_provider_model_ck CHECK (((length(btrim((model_name)::text)) >= 1) AND (length(btrim((model_name)::text)) <= 256))),
    CONSTRAINT platform_ai_provider_name_ck CHECK (((length(btrim((name)::text)) >= 1) AND (length(btrim((name)::text)) <= 120))),
    CONSTRAINT platform_ai_provider_version_ck CHECK ((version > 0))
);


--
-- Name: platform_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_audit (
    id uuid NOT NULL,
    actor character varying(100) NOT NULL,
    action character varying(100) NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_address character varying(45),
    user_agent character varying(256)
);


--
-- Name: platform_authentication_policy_lock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_authentication_policy_lock (
    id smallint NOT NULL,
    CONSTRAINT platform_authentication_policy_lock_id_check CHECK ((id = 1))
);


--
-- Name: platform_authority; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_authority (
    user_id uuid NOT NULL,
    authority character varying(120) NOT NULL
);


--
-- Name: platform_bootstrap_password_change_requirement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_bootstrap_password_change_requirement (
    user_id uuid NOT NULL,
    primary_credential_revision uuid CONSTRAINT platform_bootstrap_password_primary_credential_revisio_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP CONSTRAINT platform_bootstrap_password_change_requirem_created_at_not_null NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT platform_bootstrap_password_change_completion_check CHECK (((completed_at IS NULL) OR (completed_at >= created_at)))
);


--
-- Name: platform_branding_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_branding_file (
    file_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone
);


--
-- Name: platform_captcha_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_captcha_challenge (
    id uuid NOT NULL,
    session_digest character(64) NOT NULL,
    action character varying(40) NOT NULL,
    attempt_id character varying(128),
    policy_revision character varying(128) NOT NULL,
    settings_version bigint NOT NULL,
    provider_app_id character varying(20) NOT NULL,
    accessible_alternative boolean DEFAULT false NOT NULL,
    challenge_type character varying(16) NOT NULL,
    state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    claim_id uuid,
    claimed_at timestamp with time zone,
    attempts smallint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    verification_digest character(64),
    CONSTRAINT platform_captcha_attempt_id_check CHECK (((attempt_id IS NULL) OR ((length((attempt_id)::text) >= 1) AND (length((attempt_id)::text) <= 128)))),
    CONSTRAINT platform_captcha_challenge_action_check CHECK (((action)::text = ANY ((ARRAY['PASSWORD_LOGIN'::character varying, 'PASSKEY_LOGIN'::character varying, 'WECHAT_LOGIN'::character varying, 'LOGIN_CODE_SEND'::character varying, 'LOGIN_CODE_VERIFY'::character varying, 'REGISTRATION_SEND'::character varying, 'REGISTRATION_VERIFY'::character varying, 'REGISTRATION_COMPLETE'::character varying, 'PASSWORD_RECOVERY_REQUEST'::character varying, 'PASSWORD_RECOVERY_VERIFY'::character varying, 'PASSWORD_RECOVERY_CONFIRM'::character varying, 'POST_PRIMARY_AUTH'::character varying])::text[]))),
    CONSTRAINT platform_captcha_challenge_attempts_check CHECK (((attempts >= 0) AND (attempts <= 5))),
    CONSTRAINT platform_captcha_challenge_challenge_type_check CHECK (((challenge_type)::text = ANY ((ARRAY['IMAGE_TEXT'::character varying, 'SLIDER'::character varying, 'AUDIO'::character varying])::text[]))),
    CONSTRAINT platform_captcha_challenge_policy_revision_check CHECK (((length((policy_revision)::text) >= 1) AND (length((policy_revision)::text) <= 128))),
    CONSTRAINT platform_captcha_challenge_session_digest_check CHECK ((session_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_captcha_challenge_settings_version_check CHECK ((settings_version >= 1)),
    CONSTRAINT platform_captcha_challenge_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFYING'::character varying, 'VERIFIED'::character varying, 'LOCKED'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT platform_captcha_claim_state_check CHECK ((((state)::text = 'VERIFYING'::text) = (claim_id IS NOT NULL))),
    CONSTRAINT platform_captcha_verification_digest_check CHECK (((verification_digest IS NULL) OR (verification_digest ~ '^[a-f0-9]{64}$'::text)))
);


--
-- Name: platform_captcha_proof; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_captcha_proof (
    id uuid NOT NULL,
    challenge_id uuid NOT NULL,
    session_digest character(64) NOT NULL,
    action character varying(40) NOT NULL,
    attempt_id character varying(128),
    policy_revision character varying(128) NOT NULL,
    issued_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    CONSTRAINT platform_captcha_proof_action_check CHECK (((action)::text = ANY ((ARRAY['PASSWORD_LOGIN'::character varying, 'PASSKEY_LOGIN'::character varying, 'WECHAT_LOGIN'::character varying, 'LOGIN_CODE_SEND'::character varying, 'LOGIN_CODE_VERIFY'::character varying, 'REGISTRATION_SEND'::character varying, 'REGISTRATION_VERIFY'::character varying, 'REGISTRATION_COMPLETE'::character varying, 'PASSWORD_RECOVERY_REQUEST'::character varying, 'PASSWORD_RECOVERY_VERIFY'::character varying, 'PASSWORD_RECOVERY_CONFIRM'::character varying, 'POST_PRIMARY_AUTH'::character varying])::text[]))),
    CONSTRAINT platform_captcha_proof_attempt_id_check CHECK (((attempt_id IS NULL) OR ((length((attempt_id)::text) >= 1) AND (length((attempt_id)::text) <= 128)))),
    CONSTRAINT platform_captcha_proof_policy_revision_check CHECK (((length((policy_revision)::text) >= 1) AND (length((policy_revision)::text) <= 128))),
    CONSTRAINT platform_captcha_proof_session_digest_check CHECK ((session_digest ~ '^[a-f0-9]{64}$'::text))
);


--
-- Name: platform_captcha_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_captcha_settings (
    id smallint NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    revision uuid DEFAULT gen_random_uuid() NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    challenge_type character varying(16) DEFAULT 'IMAGE_TEXT'::character varying NOT NULL,
    access_key_id character varying(128) DEFAULT ''::character varying NOT NULL,
    primary_app_id character varying(20) DEFAULT ''::character varying NOT NULL,
    accessible_app_id character varying(20) DEFAULT ''::character varying NOT NULL,
    timeout_seconds smallint DEFAULT 5 NOT NULL,
    actions jsonb DEFAULT '{}'::jsonb NOT NULL,
    updated_by character varying(100) DEFAULT 'system'::character varying NOT NULL,
    change_reason character varying(1000) DEFAULT 'CAPTCHA policy initialized.'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_captcha_actions_object CHECK ((jsonb_typeof(actions) = 'object'::text)),
    CONSTRAINT platform_captcha_settings_challenge_type_check CHECK (((challenge_type)::text = ANY ((ARRAY['IMAGE_TEXT'::character varying, 'SLIDER'::character varying])::text[]))),
    CONSTRAINT platform_captcha_settings_id_check CHECK ((id = 1)),
    CONSTRAINT platform_captcha_settings_timeout_seconds_check CHECK (((timeout_seconds >= 1) AND (timeout_seconds <= 5))),
    CONSTRAINT platform_captcha_settings_version_check CHECK ((version >= 1))
);


--
-- Name: platform_content_policy_hit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_content_policy_hit (
    id uuid NOT NULL,
    actor character varying(100) NOT NULL,
    source character varying(300) NOT NULL,
    action character varying(16) NOT NULL,
    match_count integer NOT NULL,
    role_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT platform_content_policy_hit_action_check CHECK (((action)::text = ANY ((ARRAY['BLOCK'::character varying, 'LOG_ONLY'::character varying])::text[]))),
    CONSTRAINT platform_content_policy_hit_match_count_check CHECK ((match_count > 0))
);


--
-- Name: TABLE platform_content_policy_hit; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.platform_content_policy_hit IS 'Moderation metadata only; never stores submitted text or credentials';


--
-- Name: platform_dictionary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_dictionary (
    id uuid NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    structure character varying(8) DEFAULT 'FLAT'::character varying NOT NULL,
    remark character varying(1000) DEFAULT ''::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT platform_dictionary_structure_check CHECK (((structure)::text = ANY (ARRAY[('FLAT'::character varying)::text, ('TREE'::character varying)::text])))
);


--
-- Name: platform_dictionary_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_dictionary_item (
    id uuid NOT NULL,
    dictionary_id uuid NOT NULL,
    parent_id uuid,
    value character varying(200) NOT NULL,
    label character varying(200) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    remark character varying(1000) DEFAULT ''::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT platform_dictionary_item_check CHECK ((id IS DISTINCT FROM parent_id))
);


--
-- Name: platform_permission_retired_authority; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_permission_retired_authority (
    authority character varying(120) NOT NULL
);


--
-- Name: platform_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_role (
    id uuid NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    data_scope character varying(20) DEFAULT 'ALL'::character varying NOT NULL,
    file_quota_bytes_limit bigint,
    file_quota_files_limit bigint,
    CONSTRAINT platform_role_data_scope_check CHECK (((data_scope)::text = ANY (ARRAY[('SELF'::character varying)::text, ('ORGANIZATION'::character varying)::text, ('SUBTREE'::character varying)::text, ('ALL'::character varying)::text]))),
    CONSTRAINT platform_role_file_quota_bytes_nonnegative CHECK (((file_quota_bytes_limit IS NULL) OR (file_quota_bytes_limit >= 0))),
    CONSTRAINT platform_role_file_quota_files_nonnegative CHECK (((file_quota_files_limit IS NULL) OR (file_quota_files_limit >= 0)))
);


--
-- Name: platform_role_authority; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_role_authority (
    role_id uuid NOT NULL,
    authority character varying(100) NOT NULL
);


--
-- Name: platform_user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_user_role (
    user_id uuid NOT NULL,
    role_id uuid NOT NULL
);


--
-- Name: platform_effective_authority; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.platform_effective_authority AS
 SELECT grants.user_id,
    (grants.authority)::character varying AS authority
   FROM public.platform_authority grants
  WHERE (((grants.authority)::text !~~ 'platform:__v50:%'::text) AND (NOT (EXISTS ( SELECT 1
           FROM public.platform_permission_retired_authority retired
          WHERE ((retired.authority)::text = (grants.authority)::text)))))
UNION
 SELECT assigned.user_id,
    (grants.authority)::character varying AS authority
   FROM ((public.platform_user_role assigned
     JOIN public.platform_role role ON (((role.id = assigned.role_id) AND role.enabled)))
     JOIN public.platform_role_authority grants ON ((grants.role_id = role.id)))
  WHERE (((grants.authority)::text !~~ 'platform:__v50:%'::text) AND (NOT (EXISTS ( SELECT 1
           FROM public.platform_permission_retired_authority retired
          WHERE ((retired.authority)::text = (grants.authority)::text)))));


--
-- Name: platform_event_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_event_operation (
    operation_id uuid NOT NULL,
    operation_type character varying(64) NOT NULL,
    business_type character varying(64) NOT NULL,
    business_id uuid NOT NULL,
    business_version bigint,
    audit_operation_id uuid,
    outcome_code character varying(64) DEFAULT 'EVENTS_PENDING'::character varying NOT NULL,
    expected_event_count integer DEFAULT 0 NOT NULL,
    expected_inbox_count integer DEFAULT 0 NOT NULL,
    inbox_created integer DEFAULT 0 NOT NULL,
    inbox_skipped integer DEFAULT 0 NOT NULL,
    skipped_by_reason jsonb DEFAULT '{}'::jsonb NOT NULL,
    completed_event_count integer DEFAULT 0 NOT NULL,
    known_event_count integer DEFAULT 0 NOT NULL,
    result_known boolean DEFAULT false NOT NULL,
    expired_event_count integer DEFAULT 0 NOT NULL,
    history_status character varying(16) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_event_operation_business_version_check CHECK ((business_version IS NULL) OR (business_version > 0)),
    CONSTRAINT platform_event_operation_counts_check CHECK ((expected_event_count >= 0) AND (expected_inbox_count >= 0) AND (inbox_created >= 0) AND (inbox_skipped >= 0) AND (completed_event_count >= 0) AND (known_event_count >= 0) AND (expired_event_count >= 0)),
    CONSTRAINT platform_event_operation_history_status_check CHECK (((history_status)::text = ANY (ARRAY['AVAILABLE'::character varying, 'PARTIAL'::character varying, 'EXPIRED'::character varying, 'NO_EVENTS'::character varying]::text[]))),
    CONSTRAINT platform_event_operation_type_check CHECK (((operation_type)::text ~ '^[a-z][a-z0-9._:-]{0,63}$'::text)),
    CONSTRAINT platform_event_operation_business_type_check CHECK (((business_type)::text ~ '^[A-Z][A-Z0-9_]{0,63}$'::text)),
    CONSTRAINT platform_event_operation_outcome_check CHECK (((outcome_code)::text ~ '^[A-Z][A-Z0-9_]{0,63}$'::text)),
    CONSTRAINT platform_event_operation_skip_reason_check CHECK ((jsonb_typeof(skipped_by_reason) = 'object'::text))
);


--
-- Name: platform_event_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_event_outbox (
    id uuid NOT NULL,
    operation_id uuid,
    source_type character varying(64) NOT NULL,
    event_type character varying(128) NOT NULL,
    event_key character varying(191) NOT NULL,
    payload jsonb NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    generation integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_error character varying(1000),
    delivered_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_event_outbox_attempts_check CHECK (((attempts >= 0) AND (attempts <= 8))),
    CONSTRAINT platform_event_outbox_generation_check CHECK ((generation >= 0)),
    CONSTRAINT platform_event_outbox_check CHECK ((((status)::text = 'PROCESSING'::text) = ((lease_token IS NOT NULL) AND (lease_until IS NOT NULL)))),
    CONSTRAINT platform_event_outbox_check1 CHECK (((lease_token IS NULL) = (lease_until IS NULL))),
    CONSTRAINT platform_event_outbox_check2 CHECK ((((status)::text = 'DELIVERED'::text) = (delivered_at IS NOT NULL))),
    CONSTRAINT platform_event_outbox_event_key_check CHECK (((event_key)::text <> ''::text)),
    CONSTRAINT platform_event_outbox_event_type_check CHECK (((event_type)::text ~ '^[a-z][a-z0-9._:-]{0,127}$'::text)),
    CONSTRAINT platform_event_outbox_payload_check CHECK (((octet_length((payload)::text) >= 2) AND (octet_length((payload)::text) <= 262144))),
    CONSTRAINT platform_event_outbox_source_type_check CHECK (((source_type)::text ~ '^[a-z][a-z0-9._:-]{0,63}$'::text)),
    CONSTRAINT platform_event_outbox_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text, ('DELIVERED'::character varying)::text, ('FAILED'::character varying)::text, ('DEAD_LETTER'::character varying)::text])))
);


--
-- Name: platform_event_trace; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_event_trace (
    trace_sequence bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    event_id uuid NOT NULL,
    audit_operation_id uuid,
    generation integer DEFAULT 0 NOT NULL,
    attempt integer DEFAULT 0 NOT NULL,
    phase character varying(24) NOT NULL,
    status character varying(16) NOT NULL,
    occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    next_retry_at timestamp with time zone,
    error_code character varying(64),
    result_code character varying(64),
    expected_count integer,
    inbox_created integer,
    inbox_skipped integer,
    skipped_by_reason jsonb DEFAULT '{}'::jsonb NOT NULL,
    actor character varying(100),
    reason character varying(1000),
    CONSTRAINT platform_event_trace_attempt_check CHECK ((attempt >= 0) AND (generation >= 0)),
    CONSTRAINT platform_event_trace_counts_check CHECK ((expected_count IS NULL OR expected_count >= 0) AND (inbox_created IS NULL OR inbox_created >= 0) AND (inbox_skipped IS NULL OR inbox_skipped >= 0)),
    CONSTRAINT platform_event_trace_phase_check CHECK (((phase)::text = ANY (ARRAY['CLAIMED'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'NO_CONSUMER'::character varying, 'LEASE_RECOVERED'::character varying, 'REPLAY_APPROVED'::character varying]::text[]))),
    CONSTRAINT platform_event_trace_status_check CHECK (((status)::text = ANY (ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'DELIVERED'::character varying, 'FAILED'::character varying, 'DEAD_LETTER'::character varying]::text[]))),
    CONSTRAINT platform_event_trace_result_check CHECK ((jsonb_typeof(skipped_by_reason) = 'object'::text))
);


--
-- Name: platform_event_replay_approval; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_event_replay_approval (
    id uuid NOT NULL,
    event_id uuid NOT NULL,
    approved_by character varying(100) NOT NULL,
    reason character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_event_replay_approval_actor_check CHECK ((length(TRIM(BOTH FROM approved_by)) > 0)),
    CONSTRAINT platform_event_replay_approval_reason_check CHECK ((length(TRIM(BOTH FROM reason)) > 0))
);


--
-- Name: platform_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_feedback (
    id uuid NOT NULL,
    owner_id uuid,
    title character varying(160) NOT NULL,
    detail_html text NOT NULL,
    priority character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    admin_reply character varying(4000),
    handled_by uuid,
    handled_at timestamp with time zone,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    category character varying(100),
    CONSTRAINT platform_feedback_category_check CHECK (((category IS NULL) OR ((category)::text ~ '^[A-Z][A-Z0-9_]{0,31}$'::text))),
    CONSTRAINT platform_feedback_detail_html_check CHECK ((octet_length(detail_html) <= 800000)),
    CONSTRAINT platform_feedback_title_check CHECK ((length(TRIM(BOTH FROM title)) > 0)),
    CONSTRAINT platform_feedback_version_check CHECK ((version > 0))
);


--
-- Name: COLUMN platform_feedback.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.platform_feedback.category IS 'Stable value from work_order_feedback_category; NULL preserves legacy uncategorized feedback.';


--
-- Name: platform_feedback_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_feedback_file (
    feedback_id uuid NOT NULL,
    file_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: platform_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_file (
    id uuid NOT NULL,
    owner_id uuid,
    storage_backend character varying(32) NOT NULL,
    original_name character varying(255) NOT NULL,
    state character varying(16) DEFAULT 'UPLOADING'::character varying NOT NULL,
    size_bytes bigint,
    sha256 character varying(64),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    reserved_bytes bigint DEFAULT 20971520 NOT NULL,
    upload_expires_at timestamp with time zone DEFAULT (now() + '00:15:00'::interval) NOT NULL,
    orphaned_at timestamp with time zone,
    media_type character varying(100) DEFAULT 'application/octet-stream'::character varying NOT NULL,
    legacy_local_cleanup_pending boolean DEFAULT false NOT NULL,
    trashed_at timestamp with time zone,
    CONSTRAINT platform_file_check CHECK (((size_bytes IS NULL) = (sha256 IS NULL))),
    CONSTRAINT platform_file_check1 CHECK ((((state)::text <> 'READY'::text) OR ((size_bytes IS NOT NULL) AND (sha256 IS NOT NULL)))),
    CONSTRAINT platform_file_media_type_check CHECK (((media_type)::text = ANY (ARRAY[('application/octet-stream'::character varying)::text, ('image/png'::character varying)::text]))),
    CONSTRAINT platform_file_original_name_check CHECK ((length((original_name)::text) > 0)),
    CONSTRAINT platform_file_orphan_owner_consistency CHECK (((owner_id IS NULL) = (orphaned_at IS NOT NULL))),
    CONSTRAINT platform_file_reserved_bytes_check CHECK ((reserved_bytes >= 0)),
    CONSTRAINT platform_file_sha256_check CHECK (((sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT platform_file_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT platform_file_size_within_reservation CHECK (((size_bytes IS NULL) OR (size_bytes <= reserved_bytes))),
    CONSTRAINT platform_file_state_check CHECK (((state)::text = ANY ((ARRAY['UPLOADING'::character varying, 'READY'::character varying, 'TRASHED'::character varying, 'DELETING'::character varying])::text[]))),
    CONSTRAINT platform_file_trash_timestamp_check CHECK ((((state)::text = 'TRASHED'::text) = (trashed_at IS NOT NULL))),
    CONSTRAINT platform_file_version_check CHECK ((version >= 0))
);


--
-- Name: platform_file_blob_chunk; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_file_blob_chunk (
    file_id uuid NOT NULL,
    chunk_index integer NOT NULL,
    payload bytea NOT NULL,
    retire_after timestamp with time zone,
    CONSTRAINT platform_file_blob_chunk_index_check CHECK ((chunk_index >= 0)),
    CONSTRAINT platform_file_blob_chunk_payload_check CHECK (((octet_length(payload) >= 1) AND (octet_length(payload) <= 65536)))
);


--
-- Name: platform_global_authentication_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_global_authentication_policy (
    id smallint NOT NULL,
    version bigint NOT NULL,
    revision uuid DEFAULT gen_random_uuid() NOT NULL,
    enabled_methods text[] DEFAULT ARRAY['PASSWORD'::text, 'PASSKEY'::text] NOT NULL,
    mfa_required boolean DEFAULT false NOT NULL,
    updated_by character varying(100) DEFAULT 'system'::character varying NOT NULL,
    change_reason character varying(1000) DEFAULT 'Initial authentication policy.'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_global_auth_methods_check CHECK ((((cardinality(enabled_methods) >= 1) AND (cardinality(enabled_methods) <= 5)) AND (enabled_methods <@ ARRAY['PASSWORD'::text, 'EMAIL_OTP'::text, 'SMS_OTP'::text, 'PASSKEY'::text, 'WECHAT'::text]))),
    CONSTRAINT platform_global_authentication_policy_id_check CHECK ((id = 1)),
    CONSTRAINT platform_global_authentication_policy_version_check CHECK ((version >= 1))
);


--
-- Name: platform_global_authority; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.platform_global_authority AS
 SELECT grants.user_id,
    (grants.authority)::character varying AS authority
   FROM public.platform_authority grants
  WHERE (((grants.authority)::text !~~ 'platform:__v50:%'::text) AND (NOT (EXISTS ( SELECT 1
           FROM public.platform_permission_retired_authority retired
          WHERE ((retired.authority)::text = (grants.authority)::text)))))
UNION
 SELECT assigned.user_id,
    (grants.authority)::character varying AS authority
   FROM ((public.platform_user_role assigned
     JOIN public.platform_role role ON (((role.id = assigned.role_id) AND role.enabled AND ((role.data_scope)::text = 'ALL'::text))))
     JOIN public.platform_role_authority grants ON ((grants.role_id = role.id)))
  WHERE (((grants.authority)::text !~~ 'platform:__v50:%'::text) AND (NOT (EXISTS ( SELECT 1
           FROM public.platform_permission_retired_authority retired
          WHERE ((retired.authority)::text = (grants.authority)::text)))));


--
-- Name: platform_localization_entry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_localization_entry (
    id uuid NOT NULL,
    message_key character varying(200) NOT NULL,
    namespace character varying(80) DEFAULT 'platform'::character varying NOT NULL,
    default_message character varying(2000) NOT NULL,
    translations jsonb DEFAULT '{}'::jsonb NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_localization_entry_created_by_check CHECK ((length(TRIM(BOTH FROM created_by)) > 0)),
    CONSTRAINT platform_localization_entry_default_message_check CHECK ((length(TRIM(BOTH FROM default_message)) > 0)),
    CONSTRAINT platform_localization_entry_message_key_check CHECK (((message_key)::text ~ '^[A-Za-z][A-Za-z0-9_.:-]{0,199}$'::text)),
    CONSTRAINT platform_localization_entry_namespace_check CHECK (((namespace)::text ~ '^[a-z][a-z0-9_.-]{0,79}$'::text)),
    CONSTRAINT platform_localization_entry_translations_check CHECK ((jsonb_typeof(translations) = 'object'::text)),
    CONSTRAINT platform_localization_entry_updated_by_check CHECK ((length(TRIM(BOTH FROM updated_by)) > 0)),
    CONSTRAINT platform_localization_entry_version_check CHECK ((version > 0))
);


--
-- Name: platform_localization_locale; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_localization_locale (
    locale_code character varying(63) NOT NULL,
    display_name character varying(100) NOT NULL,
    source_locale character varying(63) NOT NULL,
    fallback_locale character varying(63) NOT NULL,
    built_in boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_localization_locale_code_check CHECK (((locale_code)::text ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'::text)),
    CONSTRAINT platform_localization_locale_created_by_check CHECK ((length(btrim((created_by)::text)) > 0)),
    CONSTRAINT platform_localization_locale_name_check CHECK (((length(btrim((display_name)::text)) >= 1) AND (length(btrim((display_name)::text)) <= 100))),
    CONSTRAINT platform_localization_locale_updated_by_check CHECK ((length(btrim((updated_by)::text)) > 0)),
    CONSTRAINT platform_localization_locale_version_check CHECK ((version > 0))
);


--
-- Name: platform_localization_release; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_localization_release (
    locale_code character varying(63) NOT NULL,
    release_version bigint DEFAULT 0 NOT NULL,
    published_by character varying(100) NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_localization_release_locale_code_check CHECK (((locale_code)::text ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'::text)),
    CONSTRAINT platform_localization_release_published_by_check CHECK ((length(TRIM(BOTH FROM published_by)) > 0)),
    CONSTRAINT platform_localization_release_release_version_check CHECK ((release_version >= 0))
);


--
-- Name: platform_login_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_login_challenge (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    provider_id character varying(64) NOT NULL,
    session_hash character(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    requirement_version uuid,
    primary_method character varying(16) DEFAULT 'PASSWORD'::character varying NOT NULL,
    state character varying(32) DEFAULT 'MFA_REQUIRED'::character varying NOT NULL,
    policy_revision uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    captcha_policy_revision character varying(128),
    captcha_satisfied boolean DEFAULT false NOT NULL,
    password_change_reason character varying(32),
    CONSTRAINT platform_login_challenge_attempts_check CHECK (((attempts >= 0) AND (attempts <= 5))),
    CONSTRAINT platform_login_challenge_captcha_revision_check CHECK (((captcha_policy_revision IS NULL) OR ((length((captcha_policy_revision)::text) >= 1) AND (length((captcha_policy_revision)::text) <= 128)))),
    CONSTRAINT platform_login_challenge_method_check CHECK (((primary_method)::text = ANY ((ARRAY['PASSWORD'::character varying, 'EMAIL_OTP'::character varying, 'SMS_OTP'::character varying, 'PASSKEY'::character varying, 'WECHAT'::character varying])::text[]))),
    CONSTRAINT platform_login_challenge_password_change_reason_check CHECK (((((state)::text = 'PASSWORD_CHANGE_REQUIRED'::text) AND (password_change_reason IS NOT NULL) AND ((password_change_reason)::text = ANY ((ARRAY['BOOTSTRAP'::character varying, 'TEMPORARY_RESET'::character varying])::text[]))) OR (((state)::text <> 'PASSWORD_CHANGE_REQUIRED'::text) AND (password_change_reason IS NULL)))),
    CONSTRAINT platform_login_challenge_state_check CHECK (((state)::text = ANY ((ARRAY['MFA_REQUIRED'::character varying, 'ENROLLMENT_REQUIRED'::character varying, 'RECOVERY_REQUIRED'::character varying, 'CAPTCHA_REQUIRED'::character varying, 'PASSWORD_CHANGE_REQUIRED'::character varying])::text[])))
);


--
-- Name: platform_mail_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_mail_delivery (
    id uuid NOT NULL,
    payload character varying(90000) NOT NULL,
    state character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_result character varying(40),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_mail_delivery_attempts_check CHECK (((attempts >= 0) AND (attempts <= 5))),
    CONSTRAINT platform_mail_delivery_check CHECK ((((state)::text = ANY (ARRAY[('LEASED'::character varying)::text, ('SENDING'::character varying)::text])) = ((lease_token IS NOT NULL) AND (lease_until IS NOT NULL)))),
    CONSTRAINT platform_mail_delivery_check1 CHECK (((lease_token IS NULL) = (lease_until IS NULL))),
    CONSTRAINT platform_mail_delivery_last_result_check CHECK (((last_result)::text = ANY (ARRAY[('ACCEPTED'::character varying)::text, ('AUTHENTICATION_FAILED'::character varying)::text, ('CONNECTION_FAILED'::character varying)::text, ('DELIVERY_UNCERTAIN'::character varying)::text, ('CONFIGURATION_UNAVAILABLE'::character varying)::text, ('LEASE_EXPIRED'::character varying)::text]))),
    CONSTRAINT platform_mail_delivery_payload_check CHECK (((payload)::text ~ '^v1:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'::text)),
    CONSTRAINT platform_mail_delivery_state_check CHECK (((state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('LEASED'::character varying)::text, ('SENDING'::character varying)::text, ('ACCEPTED'::character varying)::text, ('FAILED'::character varying)::text, ('UNCERTAIN'::character varying)::text, ('EXPIRED'::character varying)::text])))
);


--
-- Name: platform_mfa_offline_recovery_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_mfa_offline_recovery_audit (
    event_id uuid NOT NULL,
    target_user_id uuid NOT NULL,
    target_username character varying(100) NOT NULL,
    operator character varying(100) NOT NULL,
    reason character varying(500) NOT NULL,
    delivery_method character varying(16) NOT NULL,
    ticket_expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_mfa_offline_recovery_audit_delivery_method_check CHECK (((delivery_method)::text = ANY ((ARRAY['MAIL'::character varying, 'FILE'::character varying])::text[]))),
    CONSTRAINT platform_mfa_offline_recovery_audit_operator_check CHECK (((length(TRIM(BOTH FROM operator)) >= 1) AND (length(TRIM(BOTH FROM operator)) <= 100))),
    CONSTRAINT platform_mfa_offline_recovery_audit_reason_check CHECK (((length(TRIM(BOTH FROM reason)) >= 8) AND (length(TRIM(BOTH FROM reason)) <= 500)))
);


--
-- Name: platform_mfa_recovery_request_rate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_mfa_recovery_request_rate (
    user_id uuid NOT NULL,
    requested_at timestamp with time zone,
    request_day date,
    request_count integer DEFAULT 0 NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    failure_day date,
    CONSTRAINT platform_mfa_recovery_request_rate_failures_check CHECK (((failures >= 0) AND (failures <= 20))),
    CONSTRAINT platform_mfa_recovery_request_rate_request_count_check CHECK (((request_count >= 0) AND (request_count <= 1000)))
);


--
-- Name: platform_mfa_recovery_ticket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_mfa_recovery_ticket (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_digest character(64) NOT NULL,
    factor_credential_revision uuid CONSTRAINT platform_mfa_recovery_ticke_factor_credential_revision_not_null NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_mfa_recovery_ticket_state_check CHECK ((NOT ((consumed_at IS NOT NULL) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT platform_mfa_recovery_ticket_token_digest_check CHECK ((token_digest ~ '^[a-f0-9]{64}$'::text))
);


--
-- Name: platform_module_package; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_module_package (
    id uuid NOT NULL,
    module_id character varying(100) NOT NULL,
    package_version character varying(64) NOT NULL,
    schema_version character varying(64) NOT NULL,
    api_version character varying(64) NOT NULL,
    artifact_digest character varying(64) NOT NULL,
    manifest_digest character varying(64) NOT NULL,
    artifact_ref character varying(2048) NOT NULL,
    manifest jsonb NOT NULL,
    status character varying(16) DEFAULT 'STAGED'::character varying NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    installed_by character varying(100) NOT NULL,
    install_reason character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT platform_module_package_api_version_check CHECK (((api_version)::text ~ '^[A-Za-z][A-Za-z0-9._/-]{0,63}$'::text)),
    CONSTRAINT platform_module_package_artifact_digest_check CHECK (((artifact_digest)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_module_package_artifact_ref_check CHECK ((((artifact_ref)::text ~ '^(https|oci|s3)://[^[:space:]]+$'::text) AND ((artifact_ref)::text !~ '[.][.]'::text))),
    CONSTRAINT platform_module_package_install_reason_check CHECK ((length(TRIM(BOTH FROM install_reason)) > 0)),
    CONSTRAINT platform_module_package_installed_by_check CHECK ((length(TRIM(BOTH FROM installed_by)) > 0)),
    CONSTRAINT platform_module_package_manifest_check CHECK ((jsonb_typeof(manifest) = 'object'::text)),
    CONSTRAINT platform_module_package_manifest_digest_check CHECK (((manifest_digest)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_module_package_module_id_check CHECK (((module_id)::text ~ '^[a-z][a-z0-9-]*$'::text)),
    CONSTRAINT platform_module_package_package_version_check CHECK (((package_version)::text ~ '^[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.-]+)?$'::text)),
    CONSTRAINT platform_module_package_revision_check CHECK ((revision > 0)),
    CONSTRAINT platform_module_package_schema_version_check CHECK (((schema_version)::text ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'::text)),
    CONSTRAINT platform_module_package_status_check CHECK (((status)::text = ANY (ARRAY[('STAGED'::character varying)::text, ('ACTIVE'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: platform_module_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_module_revision (
    module_state_id uuid NOT NULL,
    version bigint NOT NULL,
    enabled boolean NOT NULL,
    changed_by character varying(100) NOT NULL,
    change_reason character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_module_revision_change_reason_check CHECK ((length(TRIM(BOTH FROM change_reason)) > 0)),
    CONSTRAINT platform_module_revision_changed_by_check CHECK ((length(TRIM(BOTH FROM changed_by)) > 0)),
    CONSTRAINT platform_module_revision_version_check CHECK ((version > 0))
);


--
-- Name: platform_module_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_module_state (
    id uuid NOT NULL,
    module_id character varying(100) NOT NULL,
    enabled boolean NOT NULL,
    version bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_module_state_module_id_check CHECK (((module_id)::text ~ '^[a-z][a-z0-9-]*$'::text)),
    CONSTRAINT platform_module_state_version_check CHECK ((version > 0))
);


--
-- Name: platform_navigation_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_navigation_menu (
    id uuid NOT NULL,
    parent_id uuid,
    menu_code character varying(128) NOT NULL,
    label_key character varying(128) NOT NULL,
    label_default character varying(200) NOT NULL,
    menu_type character varying(16) NOT NULL,
    section_key character varying(64),
    path character varying(512),
    icon character varying(80),
    permission_key character varying(120),
    global_only boolean DEFAULT false NOT NULL,
    module_id character varying(100),
    sort_order integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    built_in boolean DEFAULT false NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    component_key character varying(128),
    CONSTRAINT platform_navigation_component_shape_ck CHECK ((((menu_type)::text <> 'CATALOG'::text) OR (component_key IS NULL))),
    CONSTRAINT platform_navigation_menu_component_key_check CHECK (((component_key IS NULL) OR ((component_key)::text ~ '^[a-z][a-z0-9._:-]{1,127}$'::text))),
    CONSTRAINT platform_navigation_menu_label_default_check CHECK ((length(TRIM(BOTH FROM label_default)) > 0)),
    CONSTRAINT platform_navigation_menu_label_key_check CHECK (((label_key)::text ~ '^[a-z][a-zA-Z0-9._:-]{0,127}$'::text)),
    CONSTRAINT platform_navigation_menu_menu_code_check CHECK (((menu_code)::text ~ '^[a-z][a-z0-9._:-]{1,127}$'::text)),
    CONSTRAINT platform_navigation_menu_menu_type_check CHECK (((menu_type)::text = ANY (ARRAY[('CATALOG'::character varying)::text, ('PAGE'::character varying)::text, ('ACTION'::character varying)::text]))),
    CONSTRAINT platform_navigation_menu_module_id_check CHECK (((module_id IS NULL) OR ((module_id)::text ~ '^[a-z][a-z0-9-]{0,99}$'::text))),
    CONSTRAINT platform_navigation_menu_path_check CHECK (((path IS NULL) OR (((path)::text ~~ '/dashboard/%'::text) AND ((path)::text !~~ '%?%'::text) AND ((path)::text !~~ '%#%'::text)))),
    CONSTRAINT platform_navigation_menu_permission_key_check CHECK (((permission_key IS NULL) OR ((permission_key)::text ~ '^[a-z][a-z0-9:._-]{0,119}$'::text))),
    CONSTRAINT platform_navigation_menu_section_key_check CHECK (((section_key IS NULL) OR ((section_key)::text ~ '^[a-z][a-z0-9-]{0,63}$'::text))),
    CONSTRAINT platform_navigation_menu_sort_order_check CHECK (((sort_order >= 0) AND (sort_order <= 1000000))),
    CONSTRAINT platform_navigation_menu_version_check CHECK ((version > 0)),
    CONSTRAINT platform_navigation_parent_self_ck CHECK ((parent_id IS DISTINCT FROM id)),
    CONSTRAINT platform_navigation_shape_ck CHECK (((((menu_type)::text = 'CATALOG'::text) AND (path IS NULL) AND (section_key IS NULL)) OR (((menu_type)::text = ANY (ARRAY[('PAGE'::character varying)::text, ('ACTION'::character varying)::text])) AND ((path IS NOT NULL) OR (section_key IS NOT NULL)))))
);


--
-- Name: platform_notification; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_notification (
    id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    event_id uuid,
    dedupe_key character varying(191) NOT NULL,
    kind character varying(80) NOT NULL,
    title_key character varying(128) NOT NULL,
    body_key character varying(128) NOT NULL,
    params jsonb NOT NULL,
    href character varying(512),
    read_at timestamp with time zone,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_notification_body_key_check CHECK (((body_key)::text ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'::text)),
    CONSTRAINT platform_notification_dedupe_key_check CHECK (((dedupe_key)::text ~ '^[A-Za-z][A-Za-z0-9._:-]{0,190}$'::text)),
    CONSTRAINT platform_notification_href_check CHECK (((href IS NULL) OR (((href)::text ~~ '/%'::text) AND ((href)::text !~~ '//%'::text)))),
    CONSTRAINT platform_notification_kind_check CHECK (((kind)::text ~ '^[A-Za-z][A-Za-z0-9._:-]{0,79}$'::text)),
    CONSTRAINT platform_notification_params_check CHECK (((octet_length((params)::text) >= 2) AND (octet_length((params)::text) <= 8192))),
    CONSTRAINT platform_notification_title_key_check CHECK (((title_key)::text ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'::text))
);


--
-- Name: platform_organization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_organization (
    id uuid NOT NULL,
    parent_id uuid,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    CONSTRAINT platform_organization_check CHECK ((parent_id IS DISTINCT FROM id)),
    CONSTRAINT platform_organization_sort_no_check CHECK ((sort_no >= 0))
);


--
-- Name: platform_passkey_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_passkey_challenge (
    id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    challenge bytea NOT NULL,
    user_id uuid,
    user_handle bytea,
    session_id character varying(200) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    primary_credential_revision uuid,
    captcha_policy_revision character varying(128),
    captcha_satisfied boolean DEFAULT false NOT NULL,
    reauthentication_challenge_id uuid,
    CONSTRAINT platform_passkey_captcha_revision_check CHECK (((captcha_policy_revision IS NULL) OR ((length((captcha_policy_revision)::text) >= 1) AND (length((captcha_policy_revision)::text) <= 128)))),
    CONSTRAINT platform_passkey_challenge_bytes_check CHECK (((octet_length(challenge) >= 16) AND (octet_length(challenge) <= 128))),
    CONSTRAINT platform_passkey_challenge_kind_check CHECK (((kind)::text = ANY ((ARRAY['REGISTRATION'::character varying, 'AUTHENTICATION'::character varying, 'REAUTHENTICATION'::character varying])::text[]))),
    CONSTRAINT platform_passkey_challenge_user_consistency_check CHECK ((((kind)::text = 'REGISTRATION'::text) = ((user_id IS NOT NULL) AND (user_handle IS NOT NULL)))),
    CONSTRAINT platform_passkey_reauthentication_binding_check CHECK ((((kind)::text = 'REAUTHENTICATION'::text) = (reauthentication_challenge_id IS NOT NULL)))
);


--
-- Name: platform_passkey_credential; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_passkey_credential (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    user_handle bytea NOT NULL,
    credential_id bytea NOT NULL,
    public_key_cose bytea NOT NULL,
    sign_count bigint DEFAULT 0 NOT NULL,
    transports character varying(255) DEFAULT ''::character varying NOT NULL,
    backup_eligible boolean DEFAULT false NOT NULL,
    backup_state boolean DEFAULT false NOT NULL,
    label character varying(128) DEFAULT '通行密钥'::character varying NOT NULL,
    last_used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_passkey_credential_bytes_check CHECK (((octet_length(user_handle) >= 16) AND (octet_length(user_handle) <= 128) AND (octet_length(credential_id) >= 16) AND (octet_length(credential_id) <= 1024) AND (octet_length(public_key_cose) >= 16) AND (octet_length(public_key_cose) <= 4096))),
    CONSTRAINT platform_passkey_credential_label_check CHECK ((length(TRIM(BOTH FROM label)) > 0)),
    CONSTRAINT platform_passkey_credential_sign_count_check CHECK ((sign_count >= 0))
);


--
-- Name: platform_password_change_requirement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_password_change_requirement (
    user_id uuid NOT NULL,
    primary_credential_revision uuid CONSTRAINT platform_password_change_re_primary_credential_revisio_not_null NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    claimed_at timestamp with time zone,
    issued_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_password_change_claim_check CHECK (((claimed_at IS NULL) OR (claimed_at >= created_at))),
    CONSTRAINT platform_password_change_expiry_check CHECK ((expires_at > created_at))
);


--
-- Name: platform_password_reset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_password_reset (
    user_id uuid NOT NULL,
    token_hash character varying(64),
    password_snapshot text,
    email_version bigint,
    expires_at timestamp with time zone,
    requested_at timestamp with time zone NOT NULL,
    request_day date NOT NULL,
    request_count integer NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    CONSTRAINT platform_password_reset_check CHECK (((token_hash IS NULL) = (password_snapshot IS NULL))),
    CONSTRAINT platform_password_reset_check1 CHECK (((token_hash IS NULL) = (email_version IS NULL))),
    CONSTRAINT platform_password_reset_check2 CHECK (((token_hash IS NULL) = (expires_at IS NULL))),
    CONSTRAINT platform_password_reset_failures_check CHECK (((failures >= 0) AND (failures <= 20))),
    CONSTRAINT platform_password_reset_request_count_check CHECK (((request_count >= 1) AND (request_count <= 10000))),
    CONSTRAINT platform_password_reset_token_hash_check CHECK (((token_hash IS NULL) OR ((token_hash)::text ~ '^[a-f0-9]{64}$'::text)))
);


--
-- Name: platform_permission_legacy_mapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_permission_legacy_mapping (
    old_authority character varying(120) NOT NULL,
    new_authority character varying(120) NOT NULL
);


--
-- Name: platform_permission_migration_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_permission_migration_archive (
    source_authority character varying(120) NOT NULL,
    logical_authority character varying(120) DEFAULT ''::character varying CONSTRAINT platform_permission_migration_archiv_logical_authority_not_null NOT NULL,
    normalized_authority character varying(120),
    principal_kind character varying(8) NOT NULL,
    principal_id uuid NOT NULL,
    archived_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_permission_migration_archive_principal_check CHECK (((principal_kind)::text = ANY ((ARRAY['ROLE'::character varying, 'USER'::character varying])::text[])))
);


--
-- Name: platform_permission_projection_component; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_permission_projection_component (
    logical_authority character varying(120) CONSTRAINT platform_permission_projection_compo_logical_authority_not_null NOT NULL,
    source_authority character varying(120) CONSTRAINT platform_permission_projection_compon_source_authority_not_null NOT NULL,
    source_requires_global boolean DEFAULT false CONSTRAINT platform_permission_projection__source_requires_global_not_null NOT NULL,
    target_scope_source boolean DEFAULT false CONSTRAINT platform_permission_projection_com_target_scope_source_not_null NOT NULL,
    CONSTRAINT platform_permission_projection_component_hidden_check CHECK (((source_authority)::text ~ '^platform:__v50:[a-z0-9:-]+$'::text))
);


--
-- Name: platform_plugin_artifact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_plugin_artifact (
    digest character varying(64) NOT NULL,
    module_id character varying(100) NOT NULL,
    version character varying(64) NOT NULL,
    manifest jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_plugin_artifact_digest_check CHECK (((digest)::text ~ '^[a-f0-9]{64}$'::text))
);


--
-- Name: platform_plugin_bootstrap; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_plugin_bootstrap (
    id integer NOT NULL,
    CONSTRAINT platform_plugin_bootstrap_id_check CHECK ((id = 1))
);


--
-- Name: platform_plugin_graph_approval; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_plugin_graph_approval (
    id integer NOT NULL,
    graph_revision character varying(64) NOT NULL,
    approved_at timestamp with time zone DEFAULT now() NOT NULL,
    approved_by character varying(100) NOT NULL,
    CONSTRAINT platform_plugin_graph_approval_id_check CHECK ((id = 1))
);


--
-- Name: platform_plugin_installation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_plugin_installation (
    module_id character varying(100) NOT NULL,
    digest character varying(64) NOT NULL,
    installed boolean DEFAULT false NOT NULL,
    explicit_install boolean DEFAULT false NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: platform_plugin_operation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_plugin_operation (
    id uuid NOT NULL,
    module_id character varying(100) NOT NULL,
    kind character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    step text NOT NULL,
    error_code text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    data_mode character varying(16) DEFAULT 'PRESERVE'::character varying NOT NULL,
    owner_node_id uuid,
    lease_expires_at timestamp with time zone,
    CONSTRAINT platform_plugin_operation_data_mode_check CHECK (((data_mode)::text = ANY ((ARRAY['PRESERVE'::character varying, 'DELETE'::character varying])::text[])))
);


--
-- Name: platform_plugin_runtime_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_plugin_runtime_node (
    node_id uuid NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    heartbeat_at timestamp with time zone DEFAULT now() NOT NULL,
    ready boolean DEFAULT false NOT NULL,
    fixed_graph boolean DEFAULT false NOT NULL,
    graph_revision character varying(64) DEFAULT ''::character varying NOT NULL
);


--
-- Name: platform_profile_dictionary_selection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_profile_dictionary_selection (
    user_id uuid NOT NULL,
    field_key character varying(64) NOT NULL,
    item_id uuid NOT NULL
);


--
-- Name: platform_public_auth_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_public_auth_challenge (
    id uuid NOT NULL,
    purpose character varying(24) NOT NULL,
    channel character varying(8) NOT NULL,
    session_digest character(64) NOT NULL,
    contact_digest character(64) NOT NULL,
    contact_ciphertext text NOT NULL,
    code_digest character(64),
    user_id uuid,
    contact_version bigint,
    primary_credential_revision uuid,
    state character varying(16) NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    resend_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    proof_id uuid,
    proof_expires_at timestamp with time zone,
    consumed_at timestamp with time zone,
    CONSTRAINT platform_public_auth_challenge_attempts_check CHECK (((attempts >= 0) AND (attempts <= 20))),
    CONSTRAINT platform_public_auth_challenge_channel_check CHECK (((channel)::text = ANY ((ARRAY['EMAIL'::character varying, 'SMS'::character varying])::text[]))),
    CONSTRAINT platform_public_auth_challenge_code_digest_check CHECK (((code_digest IS NULL) OR (code_digest ~ '^[a-f0-9]{64}$'::text))),
    CONSTRAINT platform_public_auth_challenge_contact_digest_check CHECK ((contact_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_public_auth_challenge_proof_check CHECK (((proof_id IS NULL) = (proof_expires_at IS NULL))),
    CONSTRAINT platform_public_auth_challenge_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['REGISTRATION'::character varying, 'LOGIN'::character varying, 'SMS_RECOVERY'::character varying])::text[]))),
    CONSTRAINT platform_public_auth_challenge_session_digest_check CHECK ((session_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_public_auth_challenge_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFIED'::character varying, 'CONSUMED'::character varying, 'EXPIRED'::character varying, 'LOCKED'::character varying, 'SUPERSEDED'::character varying])::text[])))
);


--
-- Name: platform_public_auth_rate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_public_auth_rate (
    purpose character varying(24) NOT NULL,
    channel character varying(8) NOT NULL,
    rate_digest character(64) NOT NULL,
    requested_at timestamp with time zone,
    request_day date,
    request_count integer DEFAULT 0 NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    CONSTRAINT platform_public_auth_rate_channel_check CHECK (((channel)::text = ANY ((ARRAY['EMAIL'::character varying, 'SMS'::character varying])::text[]))),
    CONSTRAINT platform_public_auth_rate_failures_check CHECK (((failures >= 0) AND (failures <= 20))),
    CONSTRAINT platform_public_auth_rate_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['REGISTRATION'::character varying, 'LOGIN'::character varying, 'SMS_RECOVERY'::character varying])::text[]))),
    CONSTRAINT platform_public_auth_rate_rate_digest_check CHECK ((rate_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_public_auth_rate_request_count_check CHECK (((request_count >= 0) AND (request_count <= 10000)))
);


--
-- Name: platform_rate_bucket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_rate_bucket (
    bucket_key character varying(80) CONSTRAINT platform_login_bucket_bucket_key_not_null NOT NULL,
    window_start timestamp with time zone CONSTRAINT platform_login_bucket_window_start_not_null NOT NULL,
    attempts integer CONSTRAINT platform_login_bucket_attempts_not_null NOT NULL,
    CONSTRAINT platform_login_bucket_attempts_check CHECK ((attempts >= 0))
);


--
-- Name: platform_reauthentication_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_reauthentication_challenge (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_hash character(64) NOT NULL,
    action character varying(48) NOT NULL,
    authentication_revision uuid CONSTRAINT platform_reauthentication_chal_authentication_revision_not_null NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    completed_at timestamp with time zone,
    ticket_id uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    CONSTRAINT platform_reauthentication_action_check CHECK (((action)::text = ANY ((ARRAY['MFA_ENROLL'::character varying, 'MFA_RENAME'::character varying, 'MFA_RECOVERY_REGENERATE'::character varying, 'MFA_DISABLE'::character varying, 'MFA_ADMIN_RESET'::character varying, 'MFA_REPLACE'::character varying, 'WECHAT_BIND'::character varying, 'WECHAT_UNBIND'::character varying])::text[]))),
    CONSTRAINT platform_reauthentication_challenge_attempts_check CHECK (((attempts >= 0) AND (attempts <= 5))),
    CONSTRAINT platform_reauthentication_challenge_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'SUPERSEDED'::character varying, 'LOCKED'::character varying])::text[]))),
    CONSTRAINT platform_reauthentication_completion_check CHECK ((((completed_at IS NULL) AND (ticket_id IS NULL)) OR ((completed_at IS NOT NULL) AND (ticket_id IS NOT NULL))))
);


--
-- Name: platform_reauthentication_code_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_reauthentication_code_challenge (
    id uuid NOT NULL,
    reauthentication_challenge_id uuid CONSTRAINT platform_reauthentication_c_reauthentication_challenge_not_null NOT NULL,
    user_id uuid NOT NULL,
    session_hash character(64) NOT NULL,
    method character varying(16) NOT NULL,
    contact_digest character(64) CONSTRAINT platform_reauthentication_code_challeng_contact_digest_not_null NOT NULL,
    contact_version bigint CONSTRAINT platform_reauthentication_code_challen_contact_version_not_null NOT NULL,
    primary_credential_revision uuid CONSTRAINT platform_reauthentication_c_primary_credential_revisio_not_null NOT NULL,
    factor_credential_revision uuid CONSTRAINT platform_reauthentication_c_factor_credential_revision_not_null NOT NULL,
    code_digest character(64),
    state character varying(16) NOT NULL,
    attempts smallint DEFAULT 0 NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_reauthentication_code_challenge_attempts_check CHECK (((attempts >= 0) AND (attempts <= 5))),
    CONSTRAINT platform_reauthentication_code_challenge_code_digest_check CHECK (((code_digest IS NULL) OR (code_digest ~ '^[a-f0-9]{64}$'::text))),
    CONSTRAINT platform_reauthentication_code_challenge_contact_digest_check CHECK ((contact_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_reauthentication_code_challenge_contact_version_check CHECK ((contact_version >= 0)),
    CONSTRAINT platform_reauthentication_code_challenge_method_check CHECK (((method)::text = ANY ((ARRAY['EMAIL_OTP'::character varying, 'SMS_OTP'::character varying])::text[]))),
    CONSTRAINT platform_reauthentication_code_challenge_session_hash_check CHECK ((session_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_reauthentication_code_challenge_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'CONSUMED'::character varying, 'SUPERSEDED'::character varying, 'LOCKED'::character varying])::text[]))),
    CONSTRAINT platform_reauthentication_code_consumption_check CHECK ((((state)::text = 'CONSUMED'::text) = (consumed_at IS NOT NULL)))
);


--
-- Name: platform_reauthentication_ticket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_reauthentication_ticket (
    id uuid NOT NULL,
    challenge_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_hash character(64) NOT NULL,
    action character varying(48) NOT NULL,
    authentication_revision uuid CONSTRAINT platform_reauthentication_tick_authentication_revision_not_null NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_reauthentication_ticket_action_check CHECK (((action)::text = ANY ((ARRAY['MFA_ENROLL'::character varying, 'MFA_RENAME'::character varying, 'MFA_RECOVERY_REGENERATE'::character varying, 'MFA_DISABLE'::character varying, 'MFA_ADMIN_RESET'::character varying, 'MFA_REPLACE'::character varying, 'WECHAT_BIND'::character varying, 'WECHAT_UNBIND'::character varying])::text[])))
);


--
-- Name: platform_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_user (
    id uuid NOT NULL,
    username character varying(100) NOT NULL,
    display_name character varying(200) NOT NULL,
    password_hash character varying(256) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    real_name character varying(200) DEFAULT ''::character varying NOT NULL,
    birth_month character varying(7) DEFAULT ''::character varying NOT NULL,
    region character varying(200) DEFAULT ''::character varying NOT NULL,
    profile_values jsonb DEFAULT '{}'::jsonb NOT NULL,
    deleted_at timestamp with time zone,
    file_quota_bytes_override bigint,
    file_quota_files_override bigint,
    preferred_locale character varying(63),
    primary_credential_revision uuid DEFAULT gen_random_uuid() NOT NULL,
    factor_credential_revision uuid DEFAULT gen_random_uuid() NOT NULL,
    CONSTRAINT platform_user_file_quota_bytes_nonnegative CHECK (((file_quota_bytes_override IS NULL) OR (file_quota_bytes_override >= 0))),
    CONSTRAINT platform_user_file_quota_files_nonnegative CHECK (((file_quota_files_override IS NULL) OR (file_quota_files_override >= 0))),
    CONSTRAINT platform_user_preferred_locale_check CHECK (((preferred_locale IS NULL) OR ((preferred_locale)::text ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'::text))),
    CONSTRAINT platform_user_profile_values_check CHECK ((jsonb_typeof(profile_values) = 'object'::text))
);


--
-- Name: platform_recovery_capability; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.platform_recovery_capability AS
 SELECT id AS user_id,
    'platform:recovery:administrator'::character varying AS capability
   FROM public.platform_user account
  WHERE (enabled AND (deleted_at IS NULL) AND (NOT (EXISTS ( SELECT required.authority
           FROM ( VALUES ('platform:users:access'::text), ('platform:users:create'::text), ('platform:users:update'::text), ('platform:users:delete'::text), ('platform:users:assign-roles'::text), ('platform:roles:access'::text), ('platform:roles:create'::text), ('platform:roles:update'::text), ('platform:roles:delete'::text), ('platform:organizations:access'::text), ('platform:organizations:create'::text), ('platform:organizations:update'::text), ('platform:organizations:delete'::text), ('platform:security:access'::text), ('platform:security:update'::text)) required(authority)
          WHERE (NOT (EXISTS ( SELECT 1
                   FROM public.platform_global_authority global_grant
                  WHERE ((global_grant.user_id = account.id) AND ((global_grant.authority)::text = required.authority)))))))));


--
-- Name: platform_registration_completion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_registration_completion (
    request_id uuid NOT NULL,
    session_digest character(64) NOT NULL,
    challenge_id uuid NOT NULL,
    proof_id uuid NOT NULL,
    request_fingerprint character(64) NOT NULL,
    account_id uuid,
    receipt jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT platform_registration_completion_request_fingerprint_check CHECK ((request_fingerprint ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_registration_completion_session_digest_check CHECK ((session_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_registration_completion_state_check CHECK ((((account_id IS NULL) AND (receipt IS NULL) AND (completed_at IS NULL)) OR ((account_id IS NOT NULL) AND (receipt IS NOT NULL) AND (completed_at IS NOT NULL))))
);


--
-- Name: platform_registration_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_registration_policy (
    id smallint NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    channels text[] DEFAULT ARRAY[]::text[] NOT NULL,
    default_role_id uuid,
    updated_by character varying(100) DEFAULT 'system'::character varying NOT NULL,
    change_reason character varying(1000) DEFAULT 'System initialized registration policy.'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_registration_policy_channels_check CHECK (((channels <@ ARRAY['EMAIL'::text, 'SMS'::text]) AND (cardinality(channels) <= 2) AND ((cardinality(channels) < 2) OR (channels[1] <> channels[2])))),
    CONSTRAINT platform_registration_policy_enabled_check CHECK (((NOT enabled) OR ((cardinality(channels) > 0) AND (default_role_id IS NOT NULL)))),
    CONSTRAINT platform_registration_policy_id_check CHECK ((id = 1)),
    CONSTRAINT platform_registration_policy_version_check CHECK ((version >= 1))
);


--
-- Name: platform_role_authentication_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_role_authentication_policy (
    role_id uuid NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    revision uuid DEFAULT gen_random_uuid() NOT NULL,
    login_mode character varying(16) DEFAULT 'INHERIT'::character varying NOT NULL,
    methods text[] DEFAULT ARRAY[]::text[] NOT NULL,
    mfa_mode character varying(16) DEFAULT 'INHERIT'::character varying NOT NULL,
    captcha_mode character varying(16) DEFAULT 'INHERIT'::character varying NOT NULL,
    updated_by character varying(100) NOT NULL,
    change_reason character varying(1000) NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_role_auth_captcha_mode_check CHECK (((captcha_mode)::text = ANY ((ARRAY['INHERIT'::character varying, 'ALWAYS'::character varying])::text[]))),
    CONSTRAINT platform_role_auth_login_mode_check CHECK (((login_mode)::text = ANY ((ARRAY['INHERIT'::character varying, 'RESTRICT'::character varying])::text[]))),
    CONSTRAINT platform_role_auth_methods_check CHECK (((methods <@ ARRAY['PASSWORD'::text, 'EMAIL_OTP'::text, 'SMS_OTP'::text, 'PASSKEY'::text, 'WECHAT'::text]) AND (((login_mode)::text <> 'RESTRICT'::text) OR ((cardinality(methods) >= 1) AND (cardinality(methods) <= 5))))),
    CONSTRAINT platform_role_auth_mfa_mode_check CHECK (((mfa_mode)::text = ANY ((ARRAY['INHERIT'::character varying, 'REQUIRED'::character varying])::text[]))),
    CONSTRAINT platform_role_authentication_policy_version_check CHECK ((version >= 1))
);


--
-- Name: platform_role_preview_bootstrap_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_role_preview_bootstrap_audit (
    event_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    target_user_id uuid NOT NULL,
    operation character varying(8) NOT NULL,
    reason character varying(500) NOT NULL,
    before_granted boolean NOT NULL,
    after_granted boolean NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_role_preview_bootstrap_audit_operation_check CHECK (((operation)::text = ANY ((ARRAY['GRANT'::character varying, 'REVOKE'::character varying])::text[]))),
    CONSTRAINT platform_role_preview_bootstrap_audit_reason_check CHECK (((length(TRIM(BOTH FROM reason)) >= 8) AND (length(TRIM(BOTH FROM reason)) <= 500)))
);


--
-- Name: platform_role_preview_context; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_role_preview_context (
    session_primary_id character(36) NOT NULL,
    actor_id uuid NOT NULL,
    revision uuid NOT NULL,
    state character varying(8) DEFAULT 'NONE'::character varying NOT NULL,
    role_id uuid,
    role_code character varying(100),
    role_name character varying(200),
    role_version bigint,
    data_scope character varying(20),
    started_at timestamp with time zone,
    expires_at timestamp with time zone,
    limited_by_actor boolean DEFAULT false NOT NULL,
    blocked_reason character varying(40),
    actor_authority_digest character(64),
    role_authority_digest character(64),
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_role_preview_context_check CHECK ((((state)::text = 'NONE'::text) = (role_id IS NULL))),
    CONSTRAINT platform_role_preview_context_check1 CHECK ((((state)::text = 'NONE'::text) = (expires_at IS NULL))),
    CONSTRAINT platform_role_preview_context_check2 CHECK ((((state)::text = 'NONE'::text) = (actor_authority_digest IS NULL))),
    CONSTRAINT platform_role_preview_context_check3 CHECK ((((state)::text = 'NONE'::text) = (role_authority_digest IS NULL))),
    CONSTRAINT platform_role_preview_context_state_check CHECK (((state)::text = ANY ((ARRAY['NONE'::character varying, 'ACTIVE'::character varying, 'BLOCKED'::character varying])::text[])))
);


--
-- Name: platform_role_preview_resource_ticket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_role_preview_resource_ticket (
    ticket_id uuid NOT NULL,
    session_primary_id character(36) CONSTRAINT platform_role_preview_resource_tick_session_primary_id_not_null NOT NULL,
    actor_id uuid NOT NULL,
    context_revision uuid NOT NULL,
    resource_type character varying(32) NOT NULL,
    resource_id uuid NOT NULL,
    page_number integer NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_role_preview_resource_ticket_page_number_check CHECK (((page_number >= 1) AND (page_number <= 500))),
    CONSTRAINT platform_role_preview_resource_ticket_resource_type_check CHECK (((resource_type)::text = 'FILE_PREVIEW'::text))
);


--
-- Name: platform_second_factor_requirement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_second_factor_requirement (
    user_id uuid NOT NULL,
    provider_id character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    requirement_version uuid DEFAULT gen_random_uuid() NOT NULL,
    self_enabled boolean DEFAULT true NOT NULL,
    recovery_pending boolean DEFAULT false NOT NULL,
    CONSTRAINT platform_second_factor_requirement_provider_id_check CHECK (((provider_id)::text ~ '^[a-z][a-z0-9-]{0,63}$'::text))
);


--
-- Name: platform_secret; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_secret (
    secret_context character varying(512) NOT NULL,
    ciphertext character varying(90000),
    version bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_secret_ciphertext_check CHECK (((ciphertext IS NULL) OR ((ciphertext)::text ~ '^v1:[A-Za-z0-9_-]{1,32}:[A-Za-z0-9_-]{38,}$'::text))),
    CONSTRAINT platform_secret_secret_context_check CHECK (((secret_context)::text ~ '^[A-Za-z0-9_:/.-]+$'::text)),
    CONSTRAINT platform_secret_version_check CHECK ((version > 0))
);


--
-- Name: platform_sensitive_role_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_sensitive_role_policy (
    role_id uuid NOT NULL,
    mode character varying(16) NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    updated_by character varying(100) NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT platform_sensitive_role_policy_mode_check CHECK (((mode)::text = ANY ((ARRAY['ON'::character varying, 'OFF'::character varying])::text[]))),
    CONSTRAINT platform_sensitive_role_policy_version_check CHECK ((version > 0))
);


--
-- Name: TABLE platform_sensitive_role_policy; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.platform_sensitive_role_policy IS 'Role control switch over shared word dictionary; missing row defaults ON';


--
-- Name: platform_sensitive_word; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_sensitive_word (
    id uuid NOT NULL,
    word character varying(128) NOT NULL,
    normalized_word character varying(128) NOT NULL,
    category character varying(64) DEFAULT 'DEFAULT'::character varying NOT NULL,
    severity character varying(16) DEFAULT 'MEDIUM'::character varying NOT NULL,
    action character varying(16) DEFAULT 'BLOCK'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_sensitive_word_action_check CHECK (((action)::text = ANY (ARRAY[('BLOCK'::character varying)::text, ('LOG_ONLY'::character varying)::text]))),
    CONSTRAINT platform_sensitive_word_category_check CHECK ((length(TRIM(BOTH FROM category)) > 0)),
    CONSTRAINT platform_sensitive_word_created_by_check CHECK ((length(TRIM(BOTH FROM created_by)) > 0)),
    CONSTRAINT platform_sensitive_word_normalized_word_check CHECK ((length(TRIM(BOTH FROM normalized_word)) > 0)),
    CONSTRAINT platform_sensitive_word_severity_check CHECK (((severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text, ('CRITICAL'::character varying)::text]))),
    CONSTRAINT platform_sensitive_word_updated_by_check CHECK ((length(TRIM(BOTH FROM updated_by)) > 0)),
    CONSTRAINT platform_sensitive_word_version_check CHECK ((version > 0)),
    CONSTRAINT platform_sensitive_word_word_check CHECK ((length(TRIM(BOTH FROM word)) > 0))
);


--
-- Name: platform_sensitive_word_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_sensitive_word_revision (
    singleton boolean NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT platform_sensitive_word_revision_singleton_check CHECK (singleton),
    CONSTRAINT platform_sensitive_word_revision_value_check CHECK ((revision >= 0))
);


--
-- Name: platform_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_setting (
    id uuid NOT NULL,
    section character varying(100) NOT NULL,
    version bigint NOT NULL,
    payload jsonb NOT NULL,
    updated_by character varying(100) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_setting_version_check CHECK ((version > 0))
);


--
-- Name: platform_setting_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_setting_revision (
    setting_id uuid NOT NULL,
    version bigint NOT NULL,
    payload jsonb NOT NULL,
    changed_by character varying(100) NOT NULL,
    change_reason character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    source_version bigint
);


--
-- Name: platform_sms_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_sms_delivery (
    id uuid NOT NULL,
    payload character varying(90000) NOT NULL,
    state character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_result character varying(40),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_sms_delivery_attempts_check CHECK (((attempts >= 0) AND (attempts <= 5))),
    CONSTRAINT platform_sms_delivery_check CHECK ((((state)::text = ANY (ARRAY[('LEASED'::character varying)::text, ('SENDING'::character varying)::text])) = ((lease_token IS NOT NULL) AND (lease_until IS NOT NULL)))),
    CONSTRAINT platform_sms_delivery_check1 CHECK (((lease_token IS NULL) = (lease_until IS NULL))),
    CONSTRAINT platform_sms_delivery_last_result_check CHECK (((last_result)::text = ANY ((ARRAY['ACCEPTED'::character varying, 'AUTHENTICATION_FAILED'::character varying, 'PROVIDER_REJECTED'::character varying, 'CONNECTION_FAILED'::character varying, 'DELIVERY_UNCERTAIN'::character varying, 'CONFIGURATION_UNAVAILABLE'::character varying, 'LEASE_EXPIRED'::character varying])::text[]))),
    CONSTRAINT platform_sms_delivery_payload_check CHECK (((payload)::text ~ '^v1:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'::text)),
    CONSTRAINT platform_sms_delivery_state_check CHECK (((state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('LEASED'::character varying)::text, ('SENDING'::character varying)::text, ('ACCEPTED'::character varying)::text, ('FAILED'::character varying)::text, ('UNCERTAIN'::character varying)::text, ('EXPIRED'::character varying)::text])))
);


--
-- Name: platform_user_organization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_user_organization (
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL
);


--
-- Name: platform_wechat_attempt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_wechat_attempt (
    id uuid NOT NULL,
    purpose character varying(16) NOT NULL,
    session_digest character(64) NOT NULL,
    state_digest character(64) NOT NULL,
    attempt_state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    settings_version bigint NOT NULL,
    app_id character varying(64) NOT NULL,
    contact_proof_id uuid,
    user_id uuid,
    base_binding_version bigint,
    replace_binding boolean DEFAULT false NOT NULL,
    authentication_revision uuid,
    primary_credential_revision uuid,
    factor_credential_revision uuid,
    open_id_digest character(64),
    nickname character varying(256),
    registration_proof_id uuid,
    login_response jsonb,
    failure_code character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    claimed_at timestamp with time zone,
    completed_at timestamp with time zone,
    CONSTRAINT platform_wechat_attempt_app_id_check CHECK (((length((app_id)::text) >= 1) AND (length((app_id)::text) <= 64))),
    CONSTRAINT platform_wechat_attempt_attempt_state_check CHECK (((attempt_state)::text = ANY ((ARRAY['PENDING'::character varying, 'CLAIMED'::character varying, 'AUTHENTICATING'::character varying, 'READY'::character varying, 'UNBOUND'::character varying, 'FAILED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'CONSUMED'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT platform_wechat_attempt_binding_check CHECK (((((purpose)::text = 'BIND'::text) AND (user_id IS NOT NULL) AND (base_binding_version IS NOT NULL) AND (authentication_revision IS NOT NULL) AND (primary_credential_revision IS NOT NULL) AND (factor_credential_revision IS NOT NULL)) OR (((purpose)::text <> 'BIND'::text) AND (user_id IS NULL) AND (base_binding_version IS NULL) AND (authentication_revision IS NULL) AND (primary_credential_revision IS NULL) AND (factor_credential_revision IS NULL)))),
    CONSTRAINT platform_wechat_attempt_failure_check CHECK (((failure_code IS NULL) OR ((attempt_state)::text = 'FAILED'::text))),
    CONSTRAINT platform_wechat_attempt_login_check CHECK (((login_response IS NULL) OR (((purpose)::text = 'LOGIN'::text) AND ((attempt_state)::text = 'READY'::text)))),
    CONSTRAINT platform_wechat_attempt_login_response_check CHECK (((login_response IS NULL) OR (jsonb_typeof(login_response) = 'object'::text))),
    CONSTRAINT platform_wechat_attempt_open_id_digest_check CHECK (((open_id_digest IS NULL) OR (open_id_digest ~ '^[a-f0-9]{64}$'::text))),
    CONSTRAINT platform_wechat_attempt_proof_check CHECK (((registration_proof_id IS NULL) OR (((purpose)::text = 'REGISTRATION'::text) AND ((attempt_state)::text = 'READY'::text)))),
    CONSTRAINT platform_wechat_attempt_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['LOGIN'::character varying, 'BIND'::character varying, 'REGISTRATION'::character varying])::text[]))),
    CONSTRAINT platform_wechat_attempt_registration_check CHECK (((((purpose)::text = 'REGISTRATION'::text) AND (contact_proof_id IS NOT NULL) AND (user_id IS NULL)) OR (((purpose)::text <> 'REGISTRATION'::text) AND (contact_proof_id IS NULL)))),
    CONSTRAINT platform_wechat_attempt_result_check CHECK ((((attempt_state)::text <> 'READY'::text) OR (open_id_digest IS NOT NULL))),
    CONSTRAINT platform_wechat_attempt_session_digest_check CHECK ((session_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_wechat_attempt_settings_version_check CHECK ((settings_version >= 0)),
    CONSTRAINT platform_wechat_attempt_state_digest_check CHECK ((state_digest ~ '^[a-f0-9]{64}$'::text))
);


--
-- Name: platform_wechat_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_wechat_binding (
    user_id uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    app_id character varying(64),
    open_id_digest character(64),
    nickname character varying(256),
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_wechat_binding_identity_check CHECK ((((app_id IS NULL) AND (open_id_digest IS NULL) AND (nickname IS NULL)) OR ((app_id IS NOT NULL) AND ((length((app_id)::text) >= 1) AND (length((app_id)::text) <= 64)) AND (open_id_digest ~ '^[a-f0-9]{64}$'::text) AND (version >= 1)))),
    CONSTRAINT platform_wechat_binding_version_check CHECK ((version >= 0))
);


--
-- Name: spring_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.spring_session (
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(100)
);


--
-- Name: spring_session_attributes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);


-- User-owned template definitions and immutable source snapshots.
CREATE TABLE public.platform_print_template (
    id uuid NOT NULL,
    name character varying(160) NOT NULL,
    category character varying(80),
    tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    draft_version integer,
    published_version integer,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_print_template_pkey PRIMARY KEY (id),
    CONSTRAINT platform_print_template_name_check CHECK ((length(TRIM(BOTH FROM name)) BETWEEN 1 AND 160)),
    CONSTRAINT platform_print_template_category_check CHECK ((category IS NULL) OR (length(TRIM(BOTH FROM category)) <= 80)),
    CONSTRAINT platform_print_template_tags_check CHECK ((jsonb_typeof(tags) = 'array'::text) AND (jsonb_array_length(tags) <= 30)),
    CONSTRAINT platform_print_template_status_check CHECK (((status)::text = ANY (ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'INACTIVE'::character varying]::text[]))),
    CONSTRAINT platform_print_template_version_check CHECK ((version > 0) AND ((draft_version IS NULL) OR (draft_version > 0)) AND ((published_version IS NULL) OR (published_version > 0)))
);

CREATE TABLE public.platform_print_template_version (
    template_id uuid NOT NULL,
    version integer NOT NULL,
    state character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    source_filename character varying(255) NOT NULL,
    source_sha256 character(64) NOT NULL,
    source_bytes bytea NOT NULL,
    inspection jsonb DEFAULT '{}'::jsonb NOT NULL,
    bindings jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT platform_print_template_version_pkey PRIMARY KEY (template_id, version),
    CONSTRAINT platform_print_template_version_template_fkey FOREIGN KEY (template_id) REFERENCES public.platform_print_template(id) ON DELETE CASCADE,
    CONSTRAINT platform_print_template_version_state_check CHECK (((state)::text = ANY (ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'SUPERSEDED'::character varying]::text[]))),
    CONSTRAINT platform_print_template_version_source_check CHECK ((length(TRIM(BOTH FROM source_filename)) BETWEEN 1 AND 255) AND (source_sha256 ~ '^[a-f0-9]{64}$'::text) AND (octet_length(source_bytes) BETWEEN 1 AND 10485760)),
    CONSTRAINT platform_print_template_version_inspection_check CHECK ((jsonb_typeof(inspection) = 'object'::text) AND (jsonb_typeof(bindings) = 'object'::text)),
    CONSTRAINT platform_print_template_version_number_check CHECK ((version > 0) AND (((state)::text = 'PUBLISHED'::text) = (published_at IS NOT NULL)))
);

CREATE TABLE public.platform_user_import_preview (
    id uuid NOT NULL,
    actor character varying(100) NOT NULL,
    file_sha256 character(64) NOT NULL,
    mode character varying(8) NOT NULL,
    preview_version bigint DEFAULT 1 NOT NULL,
    profile_schema_version bigint NOT NULL,
    rows jsonb NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_user_import_preview_pkey PRIMARY KEY (id),
    CONSTRAINT platform_user_import_preview_sha_check CHECK ((file_sha256 ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_user_import_preview_mode_check CHECK (((mode)::text = ANY (ARRAY['create'::character varying, 'update'::character varying, 'upsert'::character varying]::text[]))),
    CONSTRAINT platform_user_import_preview_rows_check CHECK ((jsonb_typeof(rows) = 'array'::text) AND (jsonb_array_length(rows) BETWEEN 1 AND 500)),
    CONSTRAINT platform_user_import_preview_version_check CHECK ((preview_version > 0) AND (profile_schema_version >= 0))
);

CREATE TABLE public.platform_user_import_task (
    id uuid NOT NULL,
    preview_id uuid NOT NULL,
    actor character varying(100) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    preview_version bigint NOT NULL,
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    result jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT platform_user_import_task_pkey PRIMARY KEY (id),
    CONSTRAINT platform_user_import_task_preview_fkey FOREIGN KEY (preview_id) REFERENCES public.platform_user_import_preview(id) ON DELETE RESTRICT,
    CONSTRAINT platform_user_import_task_key_uq UNIQUE (actor, idempotency_key),
    CONSTRAINT platform_user_import_task_idempotency_check CHECK ((length(TRIM(BOTH FROM idempotency_key)) BETWEEN 1 AND 128)),
    CONSTRAINT platform_user_import_task_status_check CHECK (((status)::text = ANY (ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying]::text[])) AND (((status)::text = 'RUNNING'::text) = (completed_at IS NULL))),
    CONSTRAINT platform_user_import_task_result_check CHECK (jsonb_typeof(result) = 'object'::text),
    CONSTRAINT platform_user_import_task_version_check CHECK (preview_version > 0)
);

CREATE UNIQUE INDEX platform_print_template_name_uq ON public.platform_print_template USING btree (lower((name)::text));
CREATE INDEX platform_print_template_search_idx ON public.platform_print_template USING btree (status, category, updated_at DESC);
CREATE UNIQUE INDEX platform_print_template_single_draft_uq ON public.platform_print_template_version USING btree (template_id) WHERE ((state)::text = 'DRAFT'::text);
CREATE INDEX platform_print_template_version_history_idx ON public.platform_print_template_version USING btree (template_id, version DESC);
CREATE INDEX platform_user_import_preview_expiry_idx ON public.platform_user_import_preview USING btree (expires_at);
CREATE INDEX platform_user_import_task_actor_idx ON public.platform_user_import_task USING btree (actor, created_at DESC);


--
-- PostgreSQL database dump complete
--



-- Stable initialization data.
--
-- PostgreSQL database dump
--


-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: platform_agreement_snapshot; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_user; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_account_agreement_acceptance; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_account_draft; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_account_email; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_file; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_account_media; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_account_phone; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_ai_conversation_share; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_ai_pending_action; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_ai_provider; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_audit; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_audit VALUES ('00000000-0000-0000-0000-000000000802', 'system', 'module.enabled', '00000000-0000-0000-0000-000000000102', '2000-01-01 00:00:00+00', NULL, NULL);
INSERT INTO public.platform_audit VALUES ('00000000-0000-0000-0000-000000000804', 'system', 'module.enabled', '00000000-0000-0000-0000-000000000104', '2000-01-01 00:00:00+00', NULL, NULL);
INSERT INTO public.platform_audit VALUES ('00000000-0000-0000-0000-000000000803', 'system', 'module.enabled', '00000000-0000-0000-0000-000000000103', '2000-01-01 00:00:00+00', NULL, NULL);
INSERT INTO public.platform_audit VALUES ('00000000-0000-0000-0000-000000000801', 'system', 'module.enabled', '00000000-0000-0000-0000-000000000101', '2000-01-01 00:00:00+00', NULL, NULL);


--
-- Data for Name: platform_authentication_policy_lock; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_authentication_policy_lock VALUES (1);


--
-- Data for Name: platform_authority; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_bootstrap_password_change_requirement; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_branding_file; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_captcha_challenge; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_captcha_proof; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_captcha_settings; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_captcha_settings VALUES (1, 2, '00000000-0000-0000-0000-000000000902', false, 'IMAGE_TEXT', '', '', '', 5, '{}', 'system', 'CAPTCHA policy initialized.', '2000-01-01 00:00:00+00');


--
-- Data for Name: platform_content_policy_hit; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_dictionary; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_dictionary VALUES ('00000000-0000-0000-0000-000000000201', 'work_order_feedback_status', '工单反馈状态', true, 'FLAT', 'remark=TERMINAL marks handled states', 0);
INSERT INTO public.platform_dictionary VALUES ('00000000-0000-0000-0000-000000000202', 'work_order_feedback_priority', '工单反馈优先级', true, 'FLAT', 'Work order feedback priorities', 0);
INSERT INTO public.platform_dictionary VALUES ('00000000-0000-0000-0000-000000000203', 'work_order_feedback_default', '工单反馈默认配置', true, 'FLAT', 'value=setting key, label=setting value', 0);
INSERT INTO public.platform_dictionary VALUES ('00000000-0000-0000-0000-000000000204', 'work_order_feedback_category', '工单反馈分类', true, 'FLAT', 'Stable category values are stored on feedback; edit labels and order, keep values unchanged.', 0);
INSERT INTO public.platform_dictionary VALUES ('00000000-0000-0000-0000-000000000205', 'sensitive_word_category', '敏感词分类', true, 'FLAT', 'Labels organize sensitive words only; matching decisions do not depend on categories.', 0);


--
-- Data for Name: platform_dictionary_item; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000304', '00000000-0000-0000-0000-000000000201', NULL, 'CLOSED', '已关闭', 40, true, 'TERMINAL', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000303', '00000000-0000-0000-0000-000000000201', NULL, 'RESOLVED', '已解决', 30, true, 'TERMINAL', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000201', NULL, 'PROCESSING', '处理中', 20, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000201', NULL, 'OPEN', '待处理', 10, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000308', '00000000-0000-0000-0000-000000000202', NULL, 'URGENT', '紧急', 40, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000307', '00000000-0000-0000-0000-000000000202', NULL, 'HIGH', '高', 30, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000306', '00000000-0000-0000-0000-000000000202', NULL, 'NORMAL', '普通', 20, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000305', '00000000-0000-0000-0000-000000000202', NULL, 'LOW', '低', 10, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000313', '00000000-0000-0000-0000-000000000203', NULL, 'IMAGE_REMARK', '工单反馈富文本图片', 50, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000312', '00000000-0000-0000-0000-000000000203', NULL, 'IMAGE_CATEGORY', '工单反馈', 40, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000311', '00000000-0000-0000-0000-000000000203', NULL, 'UPLOAD_BUCKET', 'support_feedback', 30, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000310', '00000000-0000-0000-0000-000000000203', NULL, 'DEFAULT_PRIORITY', 'NORMAL', 20, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000309', '00000000-0000-0000-0000-000000000203', NULL, 'INITIAL_STATUS', 'OPEN', 10, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000314', '00000000-0000-0000-0000-000000000204', NULL, 'QUESTION', '业务咨询', 10, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000315', '00000000-0000-0000-0000-000000000204', NULL, 'BUG', '问题反馈', 20, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000316', '00000000-0000-0000-0000-000000000204', NULL, 'FEATURE', '功能建议', 30, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000317', '00000000-0000-0000-0000-000000000204', NULL, 'ACCOUNT', '账号与权限', 40, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('00000000-0000-0000-0000-000000000318', '00000000-0000-0000-0000-000000000204', NULL, 'OTHER', '其他', 50, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('cd7e626b-f100-3e3d-7da4-82af7be8bb14', '00000000-0000-0000-0000-000000000205', NULL, 'DEFAULT', '默认分类', 10, true, '', 0);
INSERT INTO public.platform_dictionary_item VALUES ('907e8fe7-bba1-0992-4eee-d20876ba3964', '00000000-0000-0000-0000-000000000205', NULL, 'IMPORTED', '导入词', 20, true, '', 0);


--
-- Data for Name: platform_event_outbox; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_event_replay_approval; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_feedback; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_feedback_file; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_file_blob_chunk; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_global_authentication_policy; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_global_authentication_policy VALUES (1, 1, '00000000-0000-0000-0000-000000000901', '{PASSWORD,PASSKEY}', false, 'system', 'Initial authentication policy.', '2000-01-01 00:00:00+00');


--
-- Data for Name: platform_localization_entry; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_localization_locale; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_localization_locale VALUES ('zh-CN', '简体中文', 'en', 'en', true, true, 1, 'system', 'system', '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_localization_locale VALUES ('zh-TW', '繁體中文', 'en', 'en', true, true, 1, 'system', 'system', '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_localization_locale VALUES ('en', 'English', 'en', 'zh-CN', true, true, 1, 'system', 'system', '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_localization_locale VALUES ('ja', '日本語', 'en', 'en', true, true, 1, 'system', 'system', '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_localization_locale VALUES ('ko', '한국어', 'en', 'en', true, true, 1, 'system', 'system', '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_localization_locale VALUES ('fr', 'Français', 'en', 'en', true, true, 1, 'system', 'system', '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_localization_locale VALUES ('es', 'Español', 'en', 'en', true, true, 1, 'system', 'system', '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_localization_locale VALUES ('de', 'Deutsch', 'en', 'en', true, true, 1, 'system', 'system', '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00');


--
-- Data for Name: platform_localization_release; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_login_challenge; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_mail_delivery; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_mfa_offline_recovery_audit; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_mfa_recovery_request_rate; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_mfa_recovery_ticket; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_module_package; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_module_state; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_module_state VALUES ('00000000-0000-0000-0000-000000000101', 'audit-console', true, 1, '2000-01-01 00:00:00+00');
INSERT INTO public.platform_module_state VALUES ('00000000-0000-0000-0000-000000000102', 'feedback', true, 1, '2000-01-01 00:00:00+00');
INSERT INTO public.platform_module_state VALUES ('00000000-0000-0000-0000-000000000103', 'sensitive-words', true, 1, '2000-01-01 00:00:00+00');
INSERT INTO public.platform_module_state VALUES ('00000000-0000-0000-0000-000000000104', 'localization', true, 1, '2000-01-01 00:00:00+00');


--
-- Data for Name: platform_module_revision; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_module_revision VALUES ('00000000-0000-0000-0000-000000000101', 1, true, 'system', 'Enable audit console in the platform baseline', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_module_revision VALUES ('00000000-0000-0000-0000-000000000102', 1, true, 'system', 'Enable feedback utility in the platform baseline', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_module_revision VALUES ('00000000-0000-0000-0000-000000000103', 1, true, 'system', 'Enable sensitive-word policy in the platform baseline', '2000-01-01 00:00:00+00');
INSERT INTO public.platform_module_revision VALUES ('00000000-0000-0000-0000-000000000104', 1, true, 'system', 'Enable runtime localization in the platform baseline', '2000-01-01 00:00:00+00');


--
-- Data for Name: platform_navigation_menu; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001', 'home', 'home', '工作台', 'PAGE', 'home', '/dashboard/home', NULL, NULL, false, NULL, 10, true, true, false, 1, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001', 'files', 'myFiles', '我的文件', 'PAGE', 'files', '/dashboard/files', NULL, NULL, false, NULL, 20, true, true, false, 1, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000038', '00000000-0000-0000-0000-000000000054', 'sensitive-words', 'sensitiveWords', '敏感词策略', 'PAGE', 'sensitive-words', '/dashboard/sensitive-words', NULL, 'platform:sensitive-words:read', true, 'sensitive-words', 30, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', 'system-settings', 'systemSettings', '系统设置', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 20, true, true, true, 3, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000022', '00000000-0000-0000-0000-000000000053', 'users', 'userList', '用户列表', 'PAGE', 'users', '/dashboard/users', NULL, 'platform:users:access', false, NULL, 10, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000064', '00000000-0000-0000-0000-000000000054', 'print-templates', 'printTemplates', '打印模板', 'PAGE', 'print-templates', '/dashboard/print-templates', 'file-word', 'platform:print-templates:access', false, 'directory', 30, true, true, false, 1, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'overview', 'overview', '工作空间', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 10, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000053', NULL, 'user-management', 'userManagement', '用户与权限', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 30, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000054', NULL, 'content-management', 'contentManagement', '内容与资源', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 40, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000055', NULL, 'operations', 'operationsCenter', '系统运维', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 70, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000002', NULL, 'administration', 'administration', '平台配置', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 80, true, true, false, 3, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000023', '00000000-0000-0000-0000-000000000053', 'online-users', 'onlineUsers', '在线用户', 'PAGE', 'online-users', '/dashboard/online-users', NULL, 'platform:online-users:access', true, NULL, 20, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000024', '00000000-0000-0000-0000-000000000053', 'roles', 'roles', '角色管理', 'PAGE', 'roles', '/dashboard/roles', NULL, 'platform:roles:access', true, NULL, 30, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000025', '00000000-0000-0000-0000-000000000053', 'organizations', 'organizations', '组织管理', 'PAGE', 'organizations', '/dashboard/organizations', NULL, 'platform:organizations:access', false, NULL, 40, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000033', '00000000-0000-0000-0000-000000000055', 'audit', 'audit', '审计查询', 'PAGE', 'audit', '/dashboard/audit', NULL, 'platform:audit:access', true, 'audit-console', 30, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000034', '00000000-0000-0000-0000-000000000062', 'mail-deliveries', 'deliveryRecords', '投递记录', 'PAGE', 'mail-deliveries', '/dashboard/mail-deliveries', NULL, 'platform:mail-deliveries:access', true, NULL, 40, true, true, false, 3, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000035', '00000000-0000-0000-0000-000000000055', 'event-outbox', 'eventOutbox', '事件 Outbox', 'PAGE', 'event-outbox', '/dashboard/event-outbox', NULL, 'platform:event-outbox:access', true, NULL, 40, true, true, false, 3, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000055', 'monitoring', 'monitoring', '运行监控', 'PAGE', 'monitoring', '/dashboard/monitoring', NULL, 'platform:monitoring:access', true, NULL, 10, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000026', '00000000-0000-0000-0000-000000000054', 'dictionaries', 'dictionaries', '字典管理', 'PAGE', 'dictionaries', '/dashboard/dictionaries', NULL, 'platform:dictionaries:access', true, NULL, 20, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000027', '00000000-0000-0000-0000-000000000002', 'branding', 'siteSettings', '站点设置', 'PAGE', 'branding', '/dashboard/branding', NULL, 'platform:branding:access', true, NULL, 20, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000028', '00000000-0000-0000-0000-000000000062', 'smtp', 'messageChannels', '消息渠道', 'PAGE', 'smtp', '/dashboard/smtp', NULL, 'platform:smtp:access', true, NULL, 20, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000029', '00000000-0000-0000-0000-000000000053', 'profile-fields', 'profileFields', '资料字段', 'PAGE', 'profile-fields', '/dashboard/profile-fields', NULL, 'platform:profile-fields:access', true, NULL, 50, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000059', '00000000-0000-0000-0000-000000000056', 'ai-judgment', 'aiJudgment', '判断模型测试', 'PAGE', 'ai-judgment', '/dashboard/ai-judgment', NULL, 'platform:settings:read', false, 'platform', 30, false, true, true, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000060', '00000000-0000-0000-0000-000000000056', 'ai-capabilities', 'aiCapabilities', 'AI 功能调用', 'PAGE', 'ai-capabilities', '/dashboard/ai-capabilities', NULL, 'platform:settings:read', false, 'platform', 40, false, true, true, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000062', NULL, 'communication', 'communicationCenter', '消息与反馈', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 50, true, true, false, 1, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000063', NULL, 'payments', 'paymentManagement', '支付管理', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 60, true, true, false, 1, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000056', NULL, 'ai-management', 'aiManagement', 'AI 中心', 'CATALOG', NULL, NULL, NULL, NULL, false, NULL, 20, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000039', '00000000-0000-0000-0000-000000000054', 'localization', 'localizationManagement', '本地化管理', 'PAGE', 'localization', '/dashboard/localization', NULL, 'platform:localization:read', true, 'localization', 40, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000036', '00000000-0000-0000-0000-000000000062', 'feedback', 'feedback', '意见反馈', 'PAGE', 'feedback', '/dashboard/feedback', NULL, 'platform:feedback:read', false, 'feedback', 60, true, true, false, 3, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000048', '00000000-0000-0000-0000-000000000062', 'sms-deliveries', 'deliveryRecords', '投递记录', 'PAGE', 'sms-deliveries', '/dashboard/sms-deliveries', NULL, 'platform:sms-deliveries:access', true, NULL, 50, true, true, false, 3, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000055', 'api-docs', 'apiDocs', '后台 API 文档', 'PAGE', 'api-docs', '/dashboard/api-docs', 'ApiOutlined', 'platform:api-docs:access', true, 'monitoring', 20, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000047', '00000000-0000-0000-0000-000000000062', 'sms', 'messageChannels', '消息渠道', 'PAGE', 'sms', '/dashboard/sms', NULL, 'platform:sms:access', true, NULL, 30, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000002', 'agreements', 'agreements', '协议设置', 'PAGE', 'agreements', '/dashboard/agreements', NULL, 'platform:agreements:access', true, NULL, 40, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000031', '00000000-0000-0000-0000-000000000002', 'watermark', 'watermark', '水印设置', 'PAGE', 'watermark', '/dashboard/watermark', NULL, 'platform:watermark:access', true, NULL, 30, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000032', '00000000-0000-0000-0000-000000000002', 'maintenance', 'maintenance', '维护设置', 'PAGE', 'maintenance', '/dashboard/maintenance', NULL, 'platform:maintenance:access', true, NULL, 50, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000049', '00000000-0000-0000-0000-000000000053', 'security', 'authenticationSecurity', '认证与安全', 'PAGE', 'security', '/dashboard/security', NULL, 'platform:security:access', true, NULL, 60, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('f15212df-d192-491d-a0c6-1c1cbf10171d', '00000000-0000-0000-0000-000000000053', 'authentication', 'authenticationMethods', '认证方式', 'PAGE', 'authentication', '/dashboard/authentication', NULL, 'platform:authentication:access', true, NULL, 70, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('ca740762-2005-4b8d-8a4a-87af93a096ce', '00000000-0000-0000-0000-000000000053', 'wechat', 'wechatSettings', '微信登录设置', 'PAGE', 'wechat', '/dashboard/wechat', NULL, 'platform:wechat:access', true, NULL, 90, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000052', '00000000-0000-0000-0000-000000000053', 'passkey', 'passkeySettings', '通行密钥设置', 'PAGE', 'passkey', '/dashboard/passkey', NULL, 'platform:passkey:access', true, NULL, 80, true, true, false, 4, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('0e348dcc-ac99-4f40-a844-e14486e77663', '00000000-0000-0000-0000-000000000053', 'captcha', 'captchaSettingsTitle', '人机验证设置', 'PAGE', 'captcha', '/dashboard/captcha', NULL, 'platform:captcha:access', true, NULL, 100, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('230e98d2-91d3-4793-b9a8-98f4d96ca209', '00000000-0000-0000-0000-000000000053', 'registration', 'registrationSettingsTitle', '公开注册设置', 'PAGE', 'registration', '/dashboard/registration', NULL, 'platform:registration:access', true, NULL, 110, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000057', '00000000-0000-0000-0000-000000000056', 'ai-console', 'aiConsole', 'AI 对话', 'PAGE', 'ai-console', '/dashboard/ai-console', NULL, 'platform:ai-console:access', false, 'platform', 10, true, true, false, 1, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000058', '00000000-0000-0000-0000-000000000056', 'ai-models', 'aiModels', '模型接入', 'PAGE', 'ai-models', '/dashboard/ai-models', NULL, 'platform:ai-models:access', true, 'platform', 20, true, true, false, 1, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000002', 'modules', 'modules', '平台模块', 'PAGE', 'modules', '/dashboard/modules', NULL, 'platform:modules:access', true, NULL, 10, true, true, false, 3, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000037', '00000000-0000-0000-0000-000000000002', 'navigation', 'navigationManagement', '导航管理', 'PAGE', 'navigation', '/dashboard/navigation', NULL, 'platform:navigation:access', true, NULL, 60, true, true, false, 6, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000050', '00000000-0000-0000-0000-000000000054', 'files-all', 'allFiles', '全部文件', 'PAGE', 'files-all', '/dashboard/files-all', NULL, 'platform:files-all:access', true, NULL, 10, true, true, false, 2, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);
INSERT INTO public.platform_navigation_menu VALUES ('00000000-0000-0000-0000-000000000051', '00000000-0000-0000-0000-000000000062', 'notifications', 'notificationCenter', '通知中心', 'PAGE', 'notifications', '/dashboard/notifications', NULL, 'platform:notifications:access', true, NULL, 10, true, true, false, 3, '2000-01-01 00:00:00+00', '2000-01-01 00:00:00+00', NULL);


--
-- Data for Name: platform_notification; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_organization; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_reauthentication_challenge; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_passkey_challenge; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_passkey_credential; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_password_change_requirement; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_password_reset; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_permission_legacy_mapping; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:read', 'platform:users:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:read', 'platform:users:export');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:read', 'platform:online-users:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:write', 'platform:users:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:write', 'platform:users:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:write', 'platform:users:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:read', 'platform:ai-console:read-users');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:reset-password', 'platform:__v50:users-reset-password');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:write', 'platform:online-users:terminate-session');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:users:terminate-session', 'platform:online-users:terminate-session');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:roles:read', 'platform:roles:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:roles:read', 'platform:users:read-roles');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:roles:read', 'platform:registration:read-roles');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:roles:write', 'platform:roles:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:roles:write', 'platform:roles:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:roles:write', 'platform:roles:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:roles:assign', 'platform:__v50:users-assign-roles');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:organizations:read', 'platform:organizations:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:organizations:write', 'platform:organizations:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:organizations:write', 'platform:organizations:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:organizations:write', 'platform:organizations:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:organizations:assign', 'platform:__v50:users-assign-organizations');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:audit:read', 'platform:audit:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:audit:read', 'platform:mail-deliveries:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:audit:read', 'platform:sms-deliveries:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:audit:read', 'platform:event-outbox:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:audit:read', 'platform:monitoring:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:audit:read', 'platform:api-docs:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:dictionaries:read', 'platform:dictionaries:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:dictionaries:read', 'platform:dictionaries:export');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:dictionaries:read', 'platform:profile-fields:read-dictionaries');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:dictionaries:write', 'platform:dictionaries:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:dictionaries:write', 'platform:dictionaries:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:dictionaries:write', 'platform:dictionaries:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:dictionaries:write', 'platform:dictionaries:import');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:branding:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:smtp:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:sms:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:profile-fields:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:agreements:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:watermark:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:maintenance:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:security:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:authentication:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:passkey:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:wechat:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:captcha:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:registration:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:file-retention:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:ai-console:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:ai-models:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:read', 'platform:registration:read-roles');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:branding:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:smtp:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:smtp:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:sms:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:sms:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:profile-fields:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:agreements:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:watermark:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:maintenance:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:security:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:authentication:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:passkey:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:wechat:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:captcha:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:registration:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:file-retention:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:ai-models:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:ai-models:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:ai-models:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:write', 'platform:ai-models:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:branding:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:smtp:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:smtp:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:sms:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:sms:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:profile-fields:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:agreements:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:watermark:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:maintenance:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:security:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:authentication:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:passkey:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:wechat:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:captcha:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:registration:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:file-retention:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:ai-models:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:ai-models:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:ai-models:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:create', 'platform:ai-models:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:branding:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:smtp:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:smtp:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:sms:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:sms:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:profile-fields:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:agreements:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:watermark:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:maintenance:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:security:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:authentication:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:passkey:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:wechat:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:captcha:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:registration:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:file-retention:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:ai-models:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:ai-models:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:ai-models:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:update', 'platform:ai-models:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:branding:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:smtp:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:smtp:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:sms:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:sms:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:profile-fields:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:agreements:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:watermark:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:maintenance:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:security:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:authentication:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:passkey:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:wechat:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:captcha:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:registration:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:file-retention:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:ai-models:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:ai-models:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:ai-models:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:settings:delete', 'platform:ai-models:test');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:modules:read', 'platform:modules:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:modules:write', 'platform:modules:install');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:modules:write', 'platform:modules:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:modules:write', 'platform:modules:uninstall');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:events:write', 'platform:event-outbox:replay');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:events:replay', 'platform:event-outbox:replay');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:navigation:read', 'platform:navigation:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:navigation:write', 'platform:navigation:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:navigation:write', 'platform:navigation:update');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:navigation:write', 'platform:navigation:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:read', 'platform:files-all:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:read', 'platform:files-all:preview');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:read', 'platform:files-all:download');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:write', 'platform:files-all:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:write', 'platform:files-all:restore');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:write', 'platform:files-all:purge');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:delete', 'platform:files-all:delete');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:delete', 'platform:files-all:purge');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:files:restore', 'platform:files-all:restore');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:notifications:read', 'platform:notifications:access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:notifications:write', 'platform:notifications:create');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:mfa:reset', 'platform:__v50:mfa-reset-global');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:mfa:read', 'platform:mfa:management-access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:mfa:read', 'platform:mfa:management-read');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:mfa:manage', 'platform:mfa:management-access');
INSERT INTO public.platform_permission_legacy_mapping VALUES ('platform:mfa:manage', 'platform:mfa:management-read');


--
-- Data for Name: platform_permission_migration_archive; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_permission_projection_component; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_permission_projection_component VALUES ('platform:users:assign-roles', 'platform:__v50:users-assign-roles', true, true);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:users:assign-organizations', 'platform:__v50:users-assign-organizations', true, true);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:users:reset-password', 'platform:__v50:users-reset-password', true, true);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:ai-console:create-user', 'platform:__v50:ai-console-create-user-global', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:ai-console:update-user', 'platform:__v50:ai-console-update-user-global', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:ai-console:delete-user', 'platform:__v50:ai-console-delete-user-global', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:roles:read-authentication-policy', 'platform:__v50:role-policy-read-roles', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:roles:read-authentication-policy', 'platform:__v50:role-policy-read-settings', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:roles:update-authentication-policy', 'platform:__v50:role-policy-update-roles', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:roles:update-authentication-policy', 'platform:__v50:role-policy-update-settings', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:registration:assign-default-role', 'platform:__v50:registration-assign-settings', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:registration:assign-default-role', 'platform:__v50:registration-assign-roles', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:mfa:management-reset', 'platform:__v50:mfa-reset-global', true, false);
INSERT INTO public.platform_permission_projection_component VALUES ('platform:mfa:management-reset', 'platform:__v50:mfa-user-update-scope', false, true);


--
-- Data for Name: platform_permission_retired_authority; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_permission_retired_authority VALUES ('platform:navigation:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:roles:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:mfa:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:organizations:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:events:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:dictionaries:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:files:restore');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:events:replay');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:modules:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:roles:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:files:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:settings:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:notifications:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:organizations:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:mfa:manage');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:navigation:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:users:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:organizations:assign');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:settings:update');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:settings:create');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:users:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:roles:assign');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:settings:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:audit:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:users:terminate-session');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:mfa:reset');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:settings:delete');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:dictionaries:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:files:read');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:notifications:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:modules:write');
INSERT INTO public.platform_permission_retired_authority VALUES ('platform:files:delete');


--
-- Data for Name: platform_plugin_artifact; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_plugin_bootstrap; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_plugin_graph_approval; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_plugin_installation; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_plugin_operation; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_plugin_runtime_node; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_profile_dictionary_selection; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_public_auth_challenge; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_public_auth_rate; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_rate_bucket; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_reauthentication_code_challenge; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_reauthentication_ticket; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_registration_completion; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_role; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_role (id, code, name, enabled, version, data_scope, file_quota_bytes_limit, file_quota_files_limit) VALUES
('00000000-0000-0000-0000-000000000052', 'admin', '管理员', true, 0, 'ALL', NULL, NULL),
('00000000-0000-0000-0000-000000000053', 'user', '普通用户', true, 0, 'SELF', NULL, NULL);



--
-- Data for Name: platform_registration_policy; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_registration_policy VALUES (1, 1, false, '{}', '00000000-0000-0000-0000-000000000053', 'system', 'System initialized registration policy.', '2000-01-01 00:00:00+00');


--
-- Data for Name: platform_role_authentication_policy; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_role_authority; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_role_authority (role_id, authority) VALUES
('00000000-0000-0000-0000-000000000052', 'platform:agreements:access'),
('00000000-0000-0000-0000-000000000052', 'platform:agreements:update'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-console:access'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-console:create-user'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-console:delete-user'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-console:read-users'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-console:update-user'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-models:access'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-models:create'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-models:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-models:test'),
('00000000-0000-0000-0000-000000000052', 'platform:ai-models:update'),
('00000000-0000-0000-0000-000000000052', 'platform:alipay-pay:orders-access'),
('00000000-0000-0000-0000-000000000052', 'platform:alipay-pay:orders-create'),
('00000000-0000-0000-0000-000000000052', 'platform:alipay-pay:orders-manage'),
('00000000-0000-0000-0000-000000000052', 'platform:alipay-pay:orders-read'),
('00000000-0000-0000-0000-000000000052', 'platform:alipay-pay:orders-refund'),
('00000000-0000-0000-0000-000000000052', 'platform:alipay-pay:settings-access'),
('00000000-0000-0000-0000-000000000052', 'platform:alipay-pay:settings-read'),
('00000000-0000-0000-0000-000000000052', 'platform:alipay-pay:settings-update'),
('00000000-0000-0000-0000-000000000052', 'platform:api-docs:access'),
('00000000-0000-0000-0000-000000000052', 'platform:audit:access'),
('00000000-0000-0000-0000-000000000052', 'platform:authentication:access'),
('00000000-0000-0000-0000-000000000052', 'platform:authentication:update'),
('00000000-0000-0000-0000-000000000052', 'platform:branding:access'),
('00000000-0000-0000-0000-000000000052', 'platform:branding:update'),
('00000000-0000-0000-0000-000000000052', 'platform:captcha:access'),
('00000000-0000-0000-0000-000000000052', 'platform:captcha:update'),
('00000000-0000-0000-0000-000000000052', 'platform:dictionaries:access'),
('00000000-0000-0000-0000-000000000052', 'platform:dictionaries:create'),
('00000000-0000-0000-0000-000000000052', 'platform:dictionaries:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:dictionaries:export'),
('00000000-0000-0000-0000-000000000052', 'platform:dictionaries:import'),
('00000000-0000-0000-0000-000000000052', 'platform:dictionaries:update'),
('00000000-0000-0000-0000-000000000052', 'platform:event-outbox:access'),
('00000000-0000-0000-0000-000000000052', 'platform:event-outbox:replay'),
('00000000-0000-0000-0000-000000000052', 'platform:feedback:access'),
('00000000-0000-0000-0000-000000000052', 'platform:feedback:create'),
('00000000-0000-0000-0000-000000000052', 'platform:feedback:read'),
('00000000-0000-0000-0000-000000000052', 'platform:feedback:reply'),
('00000000-0000-0000-0000-000000000052', 'platform:feedback:update'),
('00000000-0000-0000-0000-000000000052', 'platform:file-retention:access'),
('00000000-0000-0000-0000-000000000052', 'platform:file-retention:update'),
('00000000-0000-0000-0000-000000000052', 'platform:files-all:access'),
('00000000-0000-0000-0000-000000000052', 'platform:files-all:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:files-all:download'),
('00000000-0000-0000-0000-000000000052', 'platform:files-all:preview'),
('00000000-0000-0000-0000-000000000052', 'platform:files-all:purge'),
('00000000-0000-0000-0000-000000000052', 'platform:files-all:restore'),
('00000000-0000-0000-0000-000000000052', 'platform:files-all:update-quota'),
('00000000-0000-0000-0000-000000000052', 'platform:localization:access'),
('00000000-0000-0000-0000-000000000052', 'platform:localization:create'),
('00000000-0000-0000-0000-000000000052', 'platform:localization:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:localization:import'),
('00000000-0000-0000-0000-000000000052', 'platform:localization:publish'),
('00000000-0000-0000-0000-000000000052', 'platform:localization:read'),
('00000000-0000-0000-0000-000000000052', 'platform:localization:update'),
('00000000-0000-0000-0000-000000000052', 'platform:mail-deliveries:access'),
('00000000-0000-0000-0000-000000000052', 'platform:maintenance:access'),
('00000000-0000-0000-0000-000000000052', 'platform:maintenance:update'),
('00000000-0000-0000-0000-000000000052', 'platform:mfa:management-access'),
('00000000-0000-0000-0000-000000000052', 'platform:mfa:management-read'),
('00000000-0000-0000-0000-000000000052', 'platform:mfa:management-reset'),
('00000000-0000-0000-0000-000000000052', 'platform:modules:access'),
('00000000-0000-0000-0000-000000000052', 'platform:modules:install'),
('00000000-0000-0000-0000-000000000052', 'platform:modules:uninstall'),
('00000000-0000-0000-0000-000000000052', 'platform:modules:update'),
('00000000-0000-0000-0000-000000000052', 'platform:monitoring:access'),
('00000000-0000-0000-0000-000000000052', 'platform:navigation:access'),
('00000000-0000-0000-0000-000000000052', 'platform:navigation:create'),
('00000000-0000-0000-0000-000000000052', 'platform:navigation:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:navigation:update'),
('00000000-0000-0000-0000-000000000052', 'platform:notifications:access'),
('00000000-0000-0000-0000-000000000052', 'platform:notifications:create'),
('00000000-0000-0000-0000-000000000052', 'platform:online-users:access'),
('00000000-0000-0000-0000-000000000052', 'platform:online-users:terminate-session'),
('00000000-0000-0000-0000-000000000052', 'platform:organizations:access'),
('00000000-0000-0000-0000-000000000052', 'platform:organizations:create'),
('00000000-0000-0000-0000-000000000052', 'platform:organizations:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:organizations:update'),
('00000000-0000-0000-0000-000000000052', 'platform:passkey:access'),
('00000000-0000-0000-0000-000000000052', 'platform:passkey:update'),
('00000000-0000-0000-0000-000000000052', 'platform:payment-core:orders-access'),
('00000000-0000-0000-0000-000000000052', 'platform:payment-core:orders-create'),
('00000000-0000-0000-0000-000000000052', 'platform:payment-core:orders-manage'),
('00000000-0000-0000-0000-000000000052', 'platform:payment-core:orders-read'),
('00000000-0000-0000-0000-000000000052', 'platform:payment-core:orders-refund'),
('00000000-0000-0000-0000-000000000052', 'platform:print-templates:access'),
('00000000-0000-0000-0000-000000000052', 'platform:print-templates:create'),
('00000000-0000-0000-0000-000000000052', 'platform:print-templates:publish'),
('00000000-0000-0000-0000-000000000052', 'platform:print-templates:update'),
('00000000-0000-0000-0000-000000000052', 'platform:print-templates:use'),
('00000000-0000-0000-0000-000000000052', 'platform:profile-fields:access'),
('00000000-0000-0000-0000-000000000052', 'platform:profile-fields:read-dictionaries'),
('00000000-0000-0000-0000-000000000052', 'platform:profile-fields:update'),
('00000000-0000-0000-0000-000000000052', 'platform:registration:access'),
('00000000-0000-0000-0000-000000000052', 'platform:registration:assign-default-role'),
('00000000-0000-0000-0000-000000000052', 'platform:registration:read-roles'),
('00000000-0000-0000-0000-000000000052', 'platform:registration:update'),
('00000000-0000-0000-0000-000000000052', 'platform:roles:access'),
('00000000-0000-0000-0000-000000000052', 'platform:roles:create'),
('00000000-0000-0000-0000-000000000052', 'platform:roles:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:roles:preview'),
('00000000-0000-0000-0000-000000000052', 'platform:roles:read-authentication-policy'),
('00000000-0000-0000-0000-000000000052', 'platform:roles:update'),
('00000000-0000-0000-0000-000000000052', 'platform:roles:update-authentication-policy'),
('00000000-0000-0000-0000-000000000052', 'platform:security:access'),
('00000000-0000-0000-0000-000000000052', 'platform:security:update'),
('00000000-0000-0000-0000-000000000052', 'platform:sensitive-words:access'),
('00000000-0000-0000-0000-000000000052', 'platform:sensitive-words:create'),
('00000000-0000-0000-0000-000000000052', 'platform:sensitive-words:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:sensitive-words:import'),
('00000000-0000-0000-0000-000000000052', 'platform:sensitive-words:policy-update'),
('00000000-0000-0000-0000-000000000052', 'platform:sensitive-words:read'),
('00000000-0000-0000-0000-000000000052', 'platform:sensitive-words:update'),
('00000000-0000-0000-0000-000000000052', 'platform:sms-deliveries:access'),
('00000000-0000-0000-0000-000000000052', 'platform:sms:access'),
('00000000-0000-0000-0000-000000000052', 'platform:sms:test'),
('00000000-0000-0000-0000-000000000052', 'platform:sms:update'),
('00000000-0000-0000-0000-000000000052', 'platform:smtp:access'),
('00000000-0000-0000-0000-000000000052', 'platform:smtp:test'),
('00000000-0000-0000-0000-000000000052', 'platform:smtp:update'),
('00000000-0000-0000-0000-000000000052', 'platform:users:access'),
('00000000-0000-0000-0000-000000000052', 'platform:users:assign-organizations'),
('00000000-0000-0000-0000-000000000052', 'platform:users:assign-roles'),
('00000000-0000-0000-0000-000000000052', 'platform:users:create'),
('00000000-0000-0000-0000-000000000052', 'platform:users:delete'),
('00000000-0000-0000-0000-000000000052', 'platform:users:export'),
('00000000-0000-0000-0000-000000000052', 'platform:users:import'),
('00000000-0000-0000-0000-000000000052', 'platform:users:print'),
('00000000-0000-0000-0000-000000000052', 'platform:users:read-roles'),
('00000000-0000-0000-0000-000000000052', 'platform:users:reset-password'),
('00000000-0000-0000-0000-000000000052', 'platform:users:update'),
('00000000-0000-0000-0000-000000000052', 'platform:watermark:access'),
('00000000-0000-0000-0000-000000000052', 'platform:watermark:update'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat-pay:orders-access'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat-pay:orders-create'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat-pay:orders-manage'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat-pay:orders-read'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat-pay:orders-refund'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat-pay:settings-access'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat-pay:settings-read'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat-pay:settings-update'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat:access'),
('00000000-0000-0000-0000-000000000052', 'platform:wechat:update'),
('00000000-0000-0000-0000-000000000053', 'platform:feedback:access'),
('00000000-0000-0000-0000-000000000053', 'platform:feedback:create'),
('00000000-0000-0000-0000-000000000053', 'platform:feedback:read');



--
-- Data for Name: platform_role_preview_bootstrap_audit; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: spring_session; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_role_preview_context; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_role_preview_resource_ticket; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_second_factor_requirement; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_secret; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_sensitive_role_policy; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_sensitive_word; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_sensitive_word_revision; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_sensitive_word_revision VALUES (true, 0);


--
-- Data for Name: platform_setting; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_setting_revision; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_sms_delivery; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_user_organization; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_user_role; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_wechat_attempt; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platform_wechat_binding; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: spring_session_attributes; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- PostgreSQL database dump complete
--



-- Post-data constraints, indexes, triggers, and related objects.
--
-- PostgreSQL database dump
--


-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: platform_account_agreement_acceptance platform_account_agreement_acceptance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_agreement_acceptance
    ADD CONSTRAINT platform_account_agreement_acceptance_pkey PRIMARY KEY (id);


--
-- Name: platform_account_agreement_acceptance platform_account_agreement_acceptance_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_agreement_acceptance
    ADD CONSTRAINT platform_account_agreement_acceptance_request_id_key UNIQUE (request_id);


--
-- Name: platform_account_agreement_acceptance platform_account_agreement_acceptance_user_snapshot_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_agreement_acceptance
    ADD CONSTRAINT platform_account_agreement_acceptance_user_snapshot_key UNIQUE (user_id, snapshot_id);


--
-- Name: platform_account_draft platform_account_draft_owner_id_draft_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_draft
    ADD CONSTRAINT platform_account_draft_owner_id_draft_key_key UNIQUE (owner_id, draft_key);


--
-- Name: platform_account_draft platform_account_draft_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_draft
    ADD CONSTRAINT platform_account_draft_pkey PRIMARY KEY (id);


--
-- Name: platform_account_email platform_account_email_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_email
    ADD CONSTRAINT platform_account_email_pkey PRIMARY KEY (user_id);


--
-- Name: platform_account_media platform_account_media_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_media
    ADD CONSTRAINT platform_account_media_pkey PRIMARY KEY (user_id);


--
-- Name: platform_account_phone platform_account_phone_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_phone
    ADD CONSTRAINT platform_account_phone_pkey PRIMARY KEY (user_id);


--
-- Name: platform_agreement_snapshot platform_agreement_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_agreement_snapshot
    ADD CONSTRAINT platform_agreement_snapshot_pkey PRIMARY KEY (id);


--
-- Name: platform_agreement_snapshot platform_agreement_snapshot_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_agreement_snapshot
    ADD CONSTRAINT platform_agreement_snapshot_version_key UNIQUE (version);


--
-- Name: platform_ai_conversation_share platform_ai_conversation_share_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_ai_conversation_share
    ADD CONSTRAINT platform_ai_conversation_share_pkey PRIMARY KEY (id);


--
-- Name: platform_ai_conversation_share platform_ai_conversation_share_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_ai_conversation_share
    ADD CONSTRAINT platform_ai_conversation_share_token_hash_key UNIQUE (token_hash);


--
-- Name: platform_ai_pending_action platform_ai_pending_action_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_ai_pending_action
    ADD CONSTRAINT platform_ai_pending_action_pkey PRIMARY KEY (id);


--
-- Name: platform_ai_provider platform_ai_provider_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_ai_provider
    ADD CONSTRAINT platform_ai_provider_pkey PRIMARY KEY (id);


--
-- Name: platform_audit platform_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_audit
    ADD CONSTRAINT platform_audit_pkey PRIMARY KEY (id);


--
-- Name: platform_authentication_policy_lock platform_authentication_policy_lock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_authentication_policy_lock
    ADD CONSTRAINT platform_authentication_policy_lock_pkey PRIMARY KEY (id);


--
-- Name: platform_authority platform_authority_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_authority
    ADD CONSTRAINT platform_authority_pkey PRIMARY KEY (user_id, authority);


--
-- Name: platform_bootstrap_password_change_requirement platform_bootstrap_password_change_requirement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_bootstrap_password_change_requirement
    ADD CONSTRAINT platform_bootstrap_password_change_requirement_pkey PRIMARY KEY (user_id);


--
-- Name: platform_branding_file platform_branding_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_branding_file
    ADD CONSTRAINT platform_branding_file_pkey PRIMARY KEY (file_id);


--
-- Name: platform_captcha_challenge platform_captcha_challenge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_captcha_challenge
    ADD CONSTRAINT platform_captcha_challenge_pkey PRIMARY KEY (id);


--
-- Name: platform_captcha_proof platform_captcha_proof_challenge_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_captcha_proof
    ADD CONSTRAINT platform_captcha_proof_challenge_id_key UNIQUE (challenge_id);


--
-- Name: platform_captcha_proof platform_captcha_proof_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_captcha_proof
    ADD CONSTRAINT platform_captcha_proof_pkey PRIMARY KEY (id);


--
-- Name: platform_captcha_settings platform_captcha_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_captcha_settings
    ADD CONSTRAINT platform_captcha_settings_pkey PRIMARY KEY (id);


--
-- Name: platform_content_policy_hit platform_content_policy_hit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_content_policy_hit
    ADD CONSTRAINT platform_content_policy_hit_pkey PRIMARY KEY (id);


--
-- Name: platform_dictionary platform_dictionary_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary
    ADD CONSTRAINT platform_dictionary_code_key UNIQUE (code);


--
-- Name: platform_dictionary_item platform_dictionary_item_dictionary_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_dictionary_id_id_key UNIQUE (dictionary_id, id);


--
-- Name: platform_dictionary_item platform_dictionary_item_dictionary_id_value_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_dictionary_id_value_key UNIQUE (dictionary_id, value);


--
-- Name: platform_dictionary_item platform_dictionary_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_pkey PRIMARY KEY (id);


--
-- Name: platform_dictionary platform_dictionary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary
    ADD CONSTRAINT platform_dictionary_pkey PRIMARY KEY (id);


--
-- Name: platform_event_operation platform_event_operation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_operation
    ADD CONSTRAINT platform_event_operation_pkey PRIMARY KEY (operation_id);


--
-- Name: platform_event_outbox platform_event_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_outbox
    ADD CONSTRAINT platform_event_outbox_pkey PRIMARY KEY (id);


--
-- Name: platform_event_trace platform_event_trace_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_trace
    ADD CONSTRAINT platform_event_trace_pkey PRIMARY KEY (trace_sequence);


--
-- Name: platform_event_outbox platform_event_outbox_source_type_event_type_event_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_outbox
    ADD CONSTRAINT platform_event_outbox_source_type_event_type_event_key_key UNIQUE (source_type, event_type, event_key);


--
-- Name: platform_event_replay_approval platform_event_replay_approval_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_replay_approval
    ADD CONSTRAINT platform_event_replay_approval_pkey PRIMARY KEY (id);


--
-- Name: platform_feedback_file platform_feedback_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback_file
    ADD CONSTRAINT platform_feedback_file_pkey PRIMARY KEY (feedback_id, file_id);


--
-- Name: platform_feedback platform_feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback
    ADD CONSTRAINT platform_feedback_pkey PRIMARY KEY (id);


--
-- Name: platform_file platform_file_avatar_target; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_file
    ADD CONSTRAINT platform_file_avatar_target UNIQUE (id, owner_id, state, media_type);


--
-- Name: platform_file_blob_chunk platform_file_blob_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_file_blob_chunk
    ADD CONSTRAINT platform_file_blob_chunk_pkey PRIMARY KEY (file_id, chunk_index);


--
-- Name: platform_file platform_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_file
    ADD CONSTRAINT platform_file_pkey PRIMARY KEY (id);


--
-- Name: platform_global_authentication_policy platform_global_authentication_policy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_global_authentication_policy
    ADD CONSTRAINT platform_global_authentication_policy_pkey PRIMARY KEY (id);


--
-- Name: platform_localization_entry platform_localization_entry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_localization_entry
    ADD CONSTRAINT platform_localization_entry_pkey PRIMARY KEY (id);


--
-- Name: platform_localization_locale platform_localization_locale_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_localization_locale
    ADD CONSTRAINT platform_localization_locale_pk PRIMARY KEY (locale_code);


--
-- Name: platform_localization_release platform_localization_release_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_localization_release
    ADD CONSTRAINT platform_localization_release_pkey PRIMARY KEY (locale_code);


--
-- Name: platform_rate_bucket platform_login_bucket_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_rate_bucket
    ADD CONSTRAINT platform_login_bucket_pkey PRIMARY KEY (bucket_key);


--
-- Name: platform_login_challenge platform_login_challenge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_login_challenge
    ADD CONSTRAINT platform_login_challenge_pkey PRIMARY KEY (id);


--
-- Name: platform_mail_delivery platform_mail_delivery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_mail_delivery
    ADD CONSTRAINT platform_mail_delivery_pkey PRIMARY KEY (id);


--
-- Name: platform_mfa_offline_recovery_audit platform_mfa_offline_recovery_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_mfa_offline_recovery_audit
    ADD CONSTRAINT platform_mfa_offline_recovery_audit_pkey PRIMARY KEY (event_id);


--
-- Name: platform_mfa_recovery_request_rate platform_mfa_recovery_request_rate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_mfa_recovery_request_rate
    ADD CONSTRAINT platform_mfa_recovery_request_rate_pkey PRIMARY KEY (user_id);


--
-- Name: platform_mfa_recovery_ticket platform_mfa_recovery_ticket_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_mfa_recovery_ticket
    ADD CONSTRAINT platform_mfa_recovery_ticket_pkey PRIMARY KEY (id);


--
-- Name: platform_module_package platform_module_package_identity_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_package
    ADD CONSTRAINT platform_module_package_identity_uq UNIQUE (module_id, package_version, artifact_digest);


--
-- Name: platform_module_package platform_module_package_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_package
    ADD CONSTRAINT platform_module_package_pkey PRIMARY KEY (id);


--
-- Name: platform_module_revision platform_module_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_revision
    ADD CONSTRAINT platform_module_revision_pkey PRIMARY KEY (module_state_id, version);


--
-- Name: platform_module_state platform_module_state_module_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_state
    ADD CONSTRAINT platform_module_state_module_id_key UNIQUE (module_id);


--
-- Name: platform_module_state platform_module_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_state
    ADD CONSTRAINT platform_module_state_pkey PRIMARY KEY (id);


--
-- Name: platform_navigation_menu platform_navigation_menu_menu_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_navigation_menu
    ADD CONSTRAINT platform_navigation_menu_menu_code_key UNIQUE (menu_code);


--
-- Name: platform_navigation_menu platform_navigation_menu_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_navigation_menu
    ADD CONSTRAINT platform_navigation_menu_pkey PRIMARY KEY (id);


--
-- Name: platform_notification platform_notification_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_notification
    ADD CONSTRAINT platform_notification_pkey PRIMARY KEY (id);


--
-- Name: platform_organization platform_organization_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_organization
    ADD CONSTRAINT platform_organization_code_key UNIQUE (code);


--
-- Name: platform_organization platform_organization_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_organization
    ADD CONSTRAINT platform_organization_pkey PRIMARY KEY (id);


--
-- Name: platform_passkey_challenge platform_passkey_challenge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_challenge
    ADD CONSTRAINT platform_passkey_challenge_pkey PRIMARY KEY (id);


--
-- Name: platform_passkey_credential platform_passkey_credential_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_credential
    ADD CONSTRAINT platform_passkey_credential_id_key UNIQUE (credential_id);


--
-- Name: platform_passkey_credential platform_passkey_credential_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_credential
    ADD CONSTRAINT platform_passkey_credential_pkey PRIMARY KEY (id);


--
-- Name: platform_password_change_requirement platform_password_change_requirement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_password_change_requirement
    ADD CONSTRAINT platform_password_change_requirement_pkey PRIMARY KEY (user_id);


--
-- Name: platform_password_reset platform_password_reset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_password_reset
    ADD CONSTRAINT platform_password_reset_pkey PRIMARY KEY (user_id);


--
-- Name: platform_permission_legacy_mapping platform_permission_legacy_mapping_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_permission_legacy_mapping
    ADD CONSTRAINT platform_permission_legacy_mapping_pkey PRIMARY KEY (old_authority, new_authority);


--
-- Name: platform_permission_migration_archive platform_permission_migration_archive_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_permission_migration_archive
    ADD CONSTRAINT platform_permission_migration_archive_pkey PRIMARY KEY (source_authority, logical_authority, principal_kind, principal_id);


--
-- Name: platform_permission_projection_component platform_permission_projection_component_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_permission_projection_component
    ADD CONSTRAINT platform_permission_projection_component_pkey PRIMARY KEY (logical_authority, source_authority);


--
-- Name: platform_permission_projection_component platform_permission_projection_component_source_authority_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_permission_projection_component
    ADD CONSTRAINT platform_permission_projection_component_source_authority_key UNIQUE (source_authority);


--
-- Name: platform_permission_retired_authority platform_permission_retired_authority_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_permission_retired_authority
    ADD CONSTRAINT platform_permission_retired_authority_pkey PRIMARY KEY (authority);


--
-- Name: platform_plugin_artifact platform_plugin_artifact_module_id_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_plugin_artifact
    ADD CONSTRAINT platform_plugin_artifact_module_id_version_key UNIQUE (module_id, version);


--
-- Name: platform_plugin_artifact platform_plugin_artifact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_plugin_artifact
    ADD CONSTRAINT platform_plugin_artifact_pkey PRIMARY KEY (digest);


--
-- Name: platform_plugin_bootstrap platform_plugin_bootstrap_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_plugin_bootstrap
    ADD CONSTRAINT platform_plugin_bootstrap_pkey PRIMARY KEY (id);


--
-- Name: platform_plugin_graph_approval platform_plugin_graph_approval_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_plugin_graph_approval
    ADD CONSTRAINT platform_plugin_graph_approval_pkey PRIMARY KEY (id);


--
-- Name: platform_plugin_installation platform_plugin_installation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_plugin_installation
    ADD CONSTRAINT platform_plugin_installation_pkey PRIMARY KEY (module_id);


--
-- Name: platform_plugin_operation platform_plugin_operation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_plugin_operation
    ADD CONSTRAINT platform_plugin_operation_pkey PRIMARY KEY (id);


--
-- Name: platform_plugin_runtime_node platform_plugin_runtime_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_plugin_runtime_node
    ADD CONSTRAINT platform_plugin_runtime_node_pkey PRIMARY KEY (node_id);


--
-- Name: platform_profile_dictionary_selection platform_profile_dictionary_selection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_profile_dictionary_selection
    ADD CONSTRAINT platform_profile_dictionary_selection_pkey PRIMARY KEY (user_id, field_key);


--
-- Name: platform_public_auth_challenge platform_public_auth_challenge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_public_auth_challenge
    ADD CONSTRAINT platform_public_auth_challenge_pkey PRIMARY KEY (id);


--
-- Name: platform_public_auth_challenge platform_public_auth_challenge_proof_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_public_auth_challenge
    ADD CONSTRAINT platform_public_auth_challenge_proof_id_key UNIQUE (proof_id);


--
-- Name: platform_public_auth_rate platform_public_auth_rate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_public_auth_rate
    ADD CONSTRAINT platform_public_auth_rate_pkey PRIMARY KEY (purpose, channel, rate_digest);


--
-- Name: platform_reauthentication_challenge platform_reauthentication_challenge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_challenge
    ADD CONSTRAINT platform_reauthentication_challenge_pkey PRIMARY KEY (id);


--
-- Name: platform_reauthentication_code_challenge platform_reauthentication_code_challenge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_code_challenge
    ADD CONSTRAINT platform_reauthentication_code_challenge_pkey PRIMARY KEY (id);


--
-- Name: platform_reauthentication_ticket platform_reauthentication_ticket_challenge_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_ticket
    ADD CONSTRAINT platform_reauthentication_ticket_challenge_id_key UNIQUE (challenge_id);


--
-- Name: platform_reauthentication_ticket platform_reauthentication_ticket_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_ticket
    ADD CONSTRAINT platform_reauthentication_ticket_pkey PRIMARY KEY (id);


--
-- Name: platform_registration_completion platform_registration_completion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_registration_completion
    ADD CONSTRAINT platform_registration_completion_pkey PRIMARY KEY (request_id);


--
-- Name: platform_registration_policy platform_registration_policy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_registration_policy
    ADD CONSTRAINT platform_registration_policy_pkey PRIMARY KEY (id);


--
-- Name: platform_role_authentication_policy platform_role_authentication_policy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_authentication_policy
    ADD CONSTRAINT platform_role_authentication_policy_pkey PRIMARY KEY (role_id);


--
-- Name: platform_role_authority platform_role_authority_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_authority
    ADD CONSTRAINT platform_role_authority_pkey PRIMARY KEY (role_id, authority);


--
-- Name: platform_role platform_role_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role
    ADD CONSTRAINT platform_role_code_key UNIQUE (code);


--
-- Name: platform_role platform_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role
    ADD CONSTRAINT platform_role_pkey PRIMARY KEY (id);


--
-- Name: platform_role_preview_bootstrap_audit platform_role_preview_bootstrap_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_bootstrap_audit
    ADD CONSTRAINT platform_role_preview_bootstrap_audit_pkey PRIMARY KEY (event_id);


--
-- Name: platform_role_preview_context platform_role_preview_context_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_context
    ADD CONSTRAINT platform_role_preview_context_pkey PRIMARY KEY (session_primary_id);


--
-- Name: platform_role_preview_resource_ticket platform_role_preview_resource_ticket_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_resource_ticket
    ADD CONSTRAINT platform_role_preview_resource_ticket_pkey PRIMARY KEY (ticket_id);


--
-- Name: platform_second_factor_requirement platform_second_factor_requirement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_second_factor_requirement
    ADD CONSTRAINT platform_second_factor_requirement_pkey PRIMARY KEY (user_id);


--
-- Name: platform_secret platform_secret_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_secret
    ADD CONSTRAINT platform_secret_pkey PRIMARY KEY (secret_context);


--
-- Name: platform_sensitive_role_policy platform_sensitive_role_policy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_sensitive_role_policy
    ADD CONSTRAINT platform_sensitive_role_policy_pkey PRIMARY KEY (role_id);


--
-- Name: platform_sensitive_word platform_sensitive_word_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_sensitive_word
    ADD CONSTRAINT platform_sensitive_word_pkey PRIMARY KEY (id);


--
-- Name: platform_sensitive_word_revision platform_sensitive_word_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_sensitive_word_revision
    ADD CONSTRAINT platform_sensitive_word_revision_pkey PRIMARY KEY (singleton);


--
-- Name: platform_setting platform_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting
    ADD CONSTRAINT platform_setting_pkey PRIMARY KEY (id);


--
-- Name: platform_setting_revision platform_setting_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting_revision
    ADD CONSTRAINT platform_setting_revision_pkey PRIMARY KEY (setting_id, version);


--
-- Name: platform_setting platform_setting_section_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting
    ADD CONSTRAINT platform_setting_section_key UNIQUE (section);


--
-- Name: platform_sms_delivery platform_sms_delivery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_sms_delivery
    ADD CONSTRAINT platform_sms_delivery_pkey PRIMARY KEY (id);


--
-- Name: platform_user_organization platform_user_organization_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_organization
    ADD CONSTRAINT platform_user_organization_pkey PRIMARY KEY (user_id, organization_id);


--
-- Name: platform_user platform_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user
    ADD CONSTRAINT platform_user_pkey PRIMARY KEY (id);


--
-- Name: platform_user_role platform_user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_role
    ADD CONSTRAINT platform_user_role_pkey PRIMARY KEY (user_id, role_id);


--
-- Name: platform_user platform_user_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user
    ADD CONSTRAINT platform_user_username_key UNIQUE (username);


--
-- Name: platform_wechat_attempt platform_wechat_attempt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_wechat_attempt
    ADD CONSTRAINT platform_wechat_attempt_pkey PRIMARY KEY (id);


--
-- Name: platform_wechat_attempt platform_wechat_attempt_registration_proof_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_wechat_attempt
    ADD CONSTRAINT platform_wechat_attempt_registration_proof_id_key UNIQUE (registration_proof_id);


--
-- Name: platform_wechat_attempt platform_wechat_attempt_state_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_wechat_attempt
    ADD CONSTRAINT platform_wechat_attempt_state_digest_key UNIQUE (state_digest);


--
-- Name: platform_wechat_binding platform_wechat_binding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_wechat_binding
    ADD CONSTRAINT platform_wechat_binding_pkey PRIMARY KEY (user_id);


--
-- Name: spring_session_attributes spring_session_attributes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pkey PRIMARY KEY (session_primary_id, attribute_name);


--
-- Name: spring_session spring_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session
    ADD CONSTRAINT spring_session_pkey PRIMARY KEY (primary_id);


--
-- Name: platform_account_email_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_account_email_unique ON public.platform_account_email USING btree (lower((email)::text)) WHERE (email IS NOT NULL);


--
-- Name: platform_account_media_avatar; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_account_media_avatar ON public.platform_account_media USING btree (avatar_file_id) WHERE (avatar_file_id IS NOT NULL);


--
-- Name: platform_account_phone_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_account_phone_pending ON public.platform_account_phone USING btree (requested_at) WHERE (pending_phone IS NOT NULL);


--
-- Name: platform_account_phone_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_account_phone_unique ON public.platform_account_phone USING btree (phone) WHERE (phone IS NOT NULL);


--
-- Name: platform_ai_pending_action_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_ai_pending_action_expiry_idx ON public.platform_ai_pending_action USING btree (expires_at);


--
-- Name: platform_ai_provider_enabled_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_ai_provider_enabled_idx ON public.platform_ai_provider USING btree (enabled, provider_kind, name);


--
-- Name: platform_audit_account_sessions; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_audit_account_sessions ON public.platform_audit USING btree (actor, created_at DESC, id DESC) WHERE ((action)::text = 'session.revoke'::text);


--
-- Name: platform_audit_account_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_audit_account_target ON public.platform_audit USING btree (target_id, created_at DESC, id DESC) WHERE ((action)::text = ANY (ARRAY[('account.loginSucceeded'::character varying)::text, ('account.loginFailed'::character varying)::text, ('account.passwordChanged'::character varying)::text, ('account.passwordRecoveryRequested'::character varying)::text, ('account.passwordRecovered'::character varying)::text, ('account.passwordRecoveryFailed'::character varying)::text, ('account.emailVerificationRequested'::character varying)::text, ('account.emailVerified'::character varying)::text, ('account.emailVerificationFailed'::character varying)::text]));


--
-- Name: platform_audit_actor_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_audit_actor_idx ON public.platform_audit USING btree (actor, created_at DESC, id);


--
-- Name: platform_audit_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_audit_created_idx ON public.platform_audit USING btree (created_at DESC);


--
-- Name: platform_branding_file_pending_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_branding_file_pending_idx ON public.platform_branding_file USING btree (created_at, file_id) WHERE (published_at IS NULL);


--
-- Name: platform_captcha_challenge_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_captcha_challenge_expiry_idx ON public.platform_captcha_challenge USING btree (expires_at) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFYING'::character varying, 'VERIFIED'::character varying])::text[]));


--
-- Name: platform_captcha_challenge_session_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_captcha_challenge_session_idx ON public.platform_captcha_challenge USING btree (session_digest, action, created_at DESC);


--
-- Name: platform_captcha_one_active_challenge_per_action; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_captcha_one_active_challenge_per_action ON public.platform_captcha_challenge USING btree (session_digest, action) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFYING'::character varying, 'VERIFIED'::character varying])::text[]));


--
-- Name: platform_captcha_proof_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_captcha_proof_expiry_idx ON public.platform_captcha_proof USING btree (expires_at) WHERE (consumed_at IS NULL);


--
-- Name: platform_content_policy_hit_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_content_policy_hit_recent ON public.platform_content_policy_hit USING btree (created_at DESC, id);


--
-- Name: platform_dictionary_item_parent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_dictionary_item_parent_idx ON public.platform_dictionary_item USING btree (dictionary_id, parent_id, sort_order, id);


--
-- Name: platform_event_outbox_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_outbox_created ON public.platform_event_outbox USING btree (created_at DESC, id DESC);


--
-- Name: platform_event_outbox_operation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_outbox_operation ON public.platform_event_outbox USING btree (operation_id, created_at, id) WHERE (operation_id IS NOT NULL);


--
-- Name: platform_event_outbox_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_outbox_due ON public.platform_event_outbox USING btree (status, available_at, created_at, id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: platform_event_outbox_lease; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_outbox_lease ON public.platform_event_outbox USING btree (lease_until, updated_at, id) WHERE ((status)::text = 'PROCESSING'::text);


--
-- Name: platform_event_replay_approval_event; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_replay_approval_event ON public.platform_event_replay_approval USING btree (event_id, created_at DESC, id DESC);


--
-- Name: platform_event_operation_audit; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_event_operation_audit ON public.platform_event_operation USING btree (audit_operation_id) WHERE (audit_operation_id IS NOT NULL);


--
-- Name: platform_event_trace_audit; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_trace_audit ON public.platform_event_trace USING btree (audit_operation_id, trace_sequence DESC) WHERE (audit_operation_id IS NOT NULL);


--
-- Name: platform_event_trace_event; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_trace_event ON public.platform_event_trace USING btree (event_id, trace_sequence);


--
-- Name: platform_feedback_category_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_feedback_category_recent ON public.platform_feedback USING btree (category, created_at DESC, id DESC);


--
-- Name: platform_feedback_file_file; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_feedback_file_file ON public.platform_feedback_file USING btree (file_id);


--
-- Name: platform_feedback_owner_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_feedback_owner_recent ON public.platform_feedback USING btree (owner_id, created_at DESC, id DESC);


--
-- Name: platform_feedback_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_feedback_recent ON public.platform_feedback USING btree (created_at DESC, id DESC);


--
-- Name: platform_file_blob_chunk_retirement_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_blob_chunk_retirement_idx ON public.platform_file_blob_chunk USING btree (retire_after, file_id, chunk_index) WHERE (retire_after IS NOT NULL);


--
-- Name: platform_file_cleanup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_cleanup ON public.platform_file USING btree (state, updated_at, id);


--
-- Name: platform_file_orphan_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_orphan_retention ON public.platform_file USING btree (orphaned_at, id) WHERE ((owner_id IS NULL) AND ((state)::text = 'READY'::text));


--
-- Name: platform_file_owner_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_owner_created ON public.platform_file USING btree (owner_id, created_at DESC, id);


--
-- Name: platform_file_owner_trash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_owner_trash ON public.platform_file USING btree (owner_id, trashed_at DESC, id) WHERE ((state)::text = 'TRASHED'::text);


--
-- Name: platform_file_trash_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_trash_retention ON public.platform_file USING btree (trashed_at, id) WHERE ((state)::text = 'TRASHED'::text);


--
-- Name: platform_file_upload_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_upload_expiry ON public.platform_file USING btree (upload_expires_at, id) WHERE ((state)::text = 'UPLOADING'::text);


--
-- Name: platform_localization_entry_key_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_localization_entry_key_uq ON public.platform_localization_entry USING btree (message_key) WHERE (deleted = false);


--
-- Name: platform_localization_entry_list_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_localization_entry_list_idx ON public.platform_localization_entry USING btree (namespace, enabled, deleted, updated_at DESC, id);


--
-- Name: platform_localization_locale_list_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_localization_locale_list_idx ON public.platform_localization_locale USING btree (enabled, built_in, display_name, locale_code);


--
-- Name: platform_login_challenge_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_login_challenge_expiry ON public.platform_login_challenge USING btree (expires_at);


--
-- Name: platform_mail_leases; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_mail_leases ON public.platform_mail_delivery USING btree (lease_until) WHERE ((state)::text = ANY (ARRAY[('LEASED'::character varying)::text, ('SENDING'::character varying)::text]));


--
-- Name: platform_mail_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_mail_pending ON public.platform_mail_delivery USING btree (available_at, created_at, id) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_mail_pending_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_mail_pending_expiry ON public.platform_mail_delivery USING btree (expires_at) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_mfa_offline_recovery_audit_target_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_mfa_offline_recovery_audit_target_idx ON public.platform_mfa_offline_recovery_audit USING btree (target_user_id, created_at DESC);


--
-- Name: platform_mfa_recovery_ticket_active_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_mfa_recovery_ticket_active_user_idx ON public.platform_mfa_recovery_ticket USING btree (user_id) WHERE ((consumed_at IS NULL) AND (revoked_at IS NULL));


--
-- Name: platform_mfa_recovery_ticket_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_mfa_recovery_ticket_expiry_idx ON public.platform_mfa_recovery_ticket USING btree (expires_at) WHERE ((consumed_at IS NULL) AND (revoked_at IS NULL));


--
-- Name: platform_module_package_active_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_module_package_active_uq ON public.platform_module_package USING btree (module_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: platform_module_package_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_module_package_lookup ON public.platform_module_package USING btree (module_id, status, created_at DESC, id DESC);


--
-- Name: platform_navigation_page_path_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_navigation_page_path_uq ON public.platform_navigation_menu USING btree (path) WHERE ((deleted = false) AND ((menu_type)::text = 'PAGE'::text) AND (path IS NOT NULL));


--
-- Name: platform_navigation_page_section_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_navigation_page_section_uq ON public.platform_navigation_menu USING btree (section_key) WHERE ((deleted = false) AND ((menu_type)::text = 'PAGE'::text) AND (section_key IS NOT NULL));


--
-- Name: platform_navigation_parent_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_navigation_parent_order_idx ON public.platform_navigation_menu USING btree (parent_id, sort_order, id);


--
-- Name: platform_navigation_visible_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_navigation_visible_idx ON public.platform_navigation_menu USING btree (enabled, deleted, sort_order, id);


--
-- Name: platform_notification_dedupe; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_notification_dedupe ON public.platform_notification USING btree (recipient_id, dedupe_key);


--
-- Name: platform_notification_inbox; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_notification_inbox ON public.platform_notification USING btree (recipient_id, created_at DESC, id DESC);


--
-- Name: platform_notification_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_notification_unread ON public.platform_notification USING btree (recipient_id, created_at DESC, id DESC) WHERE (read_at IS NULL);


--
-- Name: platform_organization_parent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_organization_parent_idx ON public.platform_organization USING btree (parent_id);


--
-- Name: platform_organization_sort_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_organization_sort_idx ON public.platform_organization USING btree (parent_id, sort_no, code, id);


--
-- Name: platform_passkey_challenge_cleanup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_passkey_challenge_cleanup ON public.platform_passkey_challenge USING btree (expires_at, id);


--
-- Name: platform_passkey_challenge_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_passkey_challenge_expiry ON public.platform_passkey_challenge USING btree (expires_at, id) WHERE (consumed_at IS NULL);


--
-- Name: platform_passkey_challenge_session_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_passkey_challenge_session_kind ON public.platform_passkey_challenge USING btree (session_id, kind, expires_at, id);


--
-- Name: platform_passkey_credential_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_passkey_credential_user ON public.platform_passkey_credential USING btree (user_id, created_at DESC, id DESC);


--
-- Name: platform_plugin_operation_lease_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_plugin_operation_lease_idx ON public.platform_plugin_operation USING btree (lease_expires_at) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[]));


--
-- Name: platform_plugin_runtime_node_heartbeat_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_plugin_runtime_node_heartbeat_idx ON public.platform_plugin_runtime_node USING btree (heartbeat_at);


--
-- Name: platform_profile_dictionary_selection_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_profile_dictionary_selection_item ON public.platform_profile_dictionary_selection USING btree (item_id);


--
-- Name: platform_public_auth_challenge_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_public_auth_challenge_expiry_idx ON public.platform_public_auth_challenge USING btree (expires_at) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFIED'::character varying])::text[]));


--
-- Name: platform_public_auth_challenge_session_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_public_auth_challenge_session_idx ON public.platform_public_auth_challenge USING btree (session_digest, purpose, created_at DESC);


--
-- Name: platform_public_auth_rate_cleanup_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_public_auth_rate_cleanup_idx ON public.platform_public_auth_rate USING btree (request_day);


--
-- Name: platform_rate_bucket_window_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_rate_bucket_window_idx ON public.platform_rate_bucket USING btree (window_start);


--
-- Name: platform_reauthentication_challenge_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_reauthentication_challenge_expiry ON public.platform_reauthentication_challenge USING btree (expires_at);


--
-- Name: platform_reauthentication_challenge_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_reauthentication_challenge_session ON public.platform_reauthentication_challenge USING btree (user_id, session_hash, created_at DESC);


--
-- Name: platform_reauthentication_cleanup_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_reauthentication_cleanup_idx ON public.platform_reauthentication_challenge USING btree (expires_at, id);


--
-- Name: platform_reauthentication_code_active_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_reauthentication_code_active_idx ON public.platform_reauthentication_code_challenge USING btree (reauthentication_challenge_id) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_reauthentication_code_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_reauthentication_code_expiry_idx ON public.platform_reauthentication_code_challenge USING btree (expires_at) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_reauthentication_code_user_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_reauthentication_code_user_created_idx ON public.platform_reauthentication_code_challenge USING btree (user_id, created_at DESC);


--
-- Name: platform_reauthentication_one_pending_per_session_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_reauthentication_one_pending_per_session_idx ON public.platform_reauthentication_challenge USING btree (user_id, session_hash) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_reauthentication_ticket_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_reauthentication_ticket_expiry ON public.platform_reauthentication_ticket USING btree (expires_at);


--
-- Name: platform_registration_completion_cleanup_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_registration_completion_cleanup_idx ON public.platform_registration_completion USING btree (created_at);


--
-- Name: platform_role_auth_policy_revision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_role_auth_policy_revision ON public.platform_role_authentication_policy USING btree (revision);


--
-- Name: platform_role_preview_bootstrap_audit_target_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_role_preview_bootstrap_audit_target_idx ON public.platform_role_preview_bootstrap_audit USING btree (target_user_id, created_at DESC);


--
-- Name: platform_role_preview_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_role_preview_expiry_idx ON public.platform_role_preview_context USING btree (expires_at, session_primary_id) WHERE ((state)::text = 'ACTIVE'::text);


--
-- Name: platform_role_preview_ticket_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_role_preview_ticket_expiry_idx ON public.platform_role_preview_resource_ticket USING btree (expires_at, ticket_id);


--
-- Name: platform_second_factor_requirement_sources; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_second_factor_requirement_sources ON public.platform_second_factor_requirement USING btree (user_id) WHERE (self_enabled OR recovery_pending);


--
-- Name: platform_sensitive_word_category_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sensitive_word_category_recent ON public.platform_sensitive_word USING btree (category, updated_at DESC, id) WHERE (deleted = false);


--
-- Name: platform_sensitive_word_enabled_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sensitive_word_enabled_idx ON public.platform_sensitive_word USING btree (enabled, deleted, updated_at DESC, id);


--
-- Name: platform_sensitive_word_normalized_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_sensitive_word_normalized_uq ON public.platform_sensitive_word USING btree (normalized_word) WHERE (deleted = false);


--
-- Name: platform_sms_leases; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sms_leases ON public.platform_sms_delivery USING btree (lease_until, updated_at, id) WHERE ((state)::text = ANY (ARRAY[('LEASED'::character varying)::text, ('SENDING'::character varying)::text]));


--
-- Name: platform_sms_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sms_pending ON public.platform_sms_delivery USING btree (available_at, created_at, id) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_sms_pending_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sms_pending_expiry ON public.platform_sms_delivery USING btree (expires_at) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_user_deleted_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_user_deleted_at_idx ON public.platform_user USING btree (deleted_at);


--
-- Name: platform_user_organization_org_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_user_organization_org_idx ON public.platform_user_organization USING btree (organization_id);


--
-- Name: platform_user_role_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_user_role_role_idx ON public.platform_user_role USING btree (role_id);


--
-- Name: platform_wechat_attempt_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_wechat_attempt_expiry_idx ON public.platform_wechat_attempt USING btree (expires_at) WHERE ((attempt_state)::text = ANY ((ARRAY['PENDING'::character varying, 'CLAIMED'::character varying, 'READY'::character varying])::text[]));


--
-- Name: platform_wechat_attempt_session_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_wechat_attempt_session_idx ON public.platform_wechat_attempt USING btree (session_digest, created_at DESC, id DESC);


--
-- Name: platform_wechat_binding_identity_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_wechat_binding_identity_key ON public.platform_wechat_binding USING btree (app_id, open_id_digest) WHERE (open_id_digest IS NOT NULL);


--
-- Name: spring_session_ix1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX spring_session_ix1 ON public.spring_session USING btree (session_id);


--
-- Name: spring_session_ix2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_session_ix2 ON public.spring_session USING btree (expiry_time);


--
-- Name: spring_session_ix3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_session_ix3 ON public.spring_session USING btree (principal_name);


--
-- Name: platform_file platform_file_track_owner; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER platform_file_track_owner BEFORE INSERT OR UPDATE OF owner_id ON public.platform_file FOR EACH ROW EXECUTE FUNCTION public.platform_file_track_owner();


--
-- Name: platform_sensitive_word platform_sensitive_word_revision_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER platform_sensitive_word_revision_trigger AFTER INSERT OR DELETE OR UPDATE ON public.platform_sensitive_word FOR EACH ROW EXECUTE FUNCTION public.platform_bump_sensitive_word_revision();


--
-- Name: platform_sensitive_word platform_sensitive_word_revision_truncate_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER platform_sensitive_word_revision_truncate_trigger AFTER TRUNCATE ON public.platform_sensitive_word FOR EACH STATEMENT EXECUTE FUNCTION public.platform_bump_sensitive_word_revision();


--
-- Name: platform_account_agreement_acceptance platform_account_agreement_acceptance_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_agreement_acceptance
    ADD CONSTRAINT platform_account_agreement_acceptance_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES public.platform_agreement_snapshot(id) ON DELETE RESTRICT;


--
-- Name: platform_account_agreement_acceptance platform_account_agreement_acceptance_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_agreement_acceptance
    ADD CONSTRAINT platform_account_agreement_acceptance_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_account_draft platform_account_draft_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_draft
    ADD CONSTRAINT platform_account_draft_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_account_email platform_account_email_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_email
    ADD CONSTRAINT platform_account_email_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_account_media platform_account_media_avatar_file_id_user_id_file_state_m_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_media
    ADD CONSTRAINT platform_account_media_avatar_file_id_user_id_file_state_m_fkey FOREIGN KEY (avatar_file_id, user_id, file_state, media_type) REFERENCES public.platform_file(id, owner_id, state, media_type);


--
-- Name: platform_account_media platform_account_media_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_media
    ADD CONSTRAINT platform_account_media_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_account_phone platform_account_phone_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_phone
    ADD CONSTRAINT platform_account_phone_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_authority platform_authority_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_authority
    ADD CONSTRAINT platform_authority_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_bootstrap_password_change_requirement platform_bootstrap_password_change_requirement_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_bootstrap_password_change_requirement
    ADD CONSTRAINT platform_bootstrap_password_change_requirement_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_branding_file platform_branding_file_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_branding_file
    ADD CONSTRAINT platform_branding_file_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.platform_file(id) ON DELETE CASCADE;


--
-- Name: platform_captcha_proof platform_captcha_proof_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_captcha_proof
    ADD CONSTRAINT platform_captcha_proof_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.platform_captcha_challenge(id) ON DELETE CASCADE;


--
-- Name: platform_dictionary_item platform_dictionary_item_dictionary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_dictionary_id_fkey FOREIGN KEY (dictionary_id) REFERENCES public.platform_dictionary(id) ON DELETE RESTRICT;


--
-- Name: platform_dictionary_item platform_dictionary_item_dictionary_id_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_dictionary_id_parent_id_fkey FOREIGN KEY (dictionary_id, parent_id) REFERENCES public.platform_dictionary_item(dictionary_id, id) ON DELETE RESTRICT;


--
-- Name: platform_event_replay_approval platform_event_replay_approval_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_replay_approval
    ADD CONSTRAINT platform_event_replay_approval_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.platform_event_outbox(id) ON DELETE RESTRICT;


--
-- Name: platform_event_outbox platform_event_outbox_operation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_outbox
    ADD CONSTRAINT platform_event_outbox_operation_id_fkey FOREIGN KEY (operation_id) REFERENCES public.platform_event_operation(operation_id) ON DELETE SET NULL;


--
-- Name: platform_event_trace platform_event_trace_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_trace
    ADD CONSTRAINT platform_event_trace_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.platform_event_outbox(id) ON DELETE CASCADE;


--
-- Name: platform_feedback_file platform_feedback_file_feedback_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback_file
    ADD CONSTRAINT platform_feedback_file_feedback_id_fkey FOREIGN KEY (feedback_id) REFERENCES public.platform_feedback(id) ON DELETE CASCADE;


--
-- Name: platform_feedback_file platform_feedback_file_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback_file
    ADD CONSTRAINT platform_feedback_file_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.platform_file(id) ON DELETE RESTRICT;


--
-- Name: platform_feedback platform_feedback_handled_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback
    ADD CONSTRAINT platform_feedback_handled_by_fkey FOREIGN KEY (handled_by) REFERENCES public.platform_user(id) ON DELETE SET NULL;


--
-- Name: platform_feedback platform_feedback_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback
    ADD CONSTRAINT platform_feedback_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.platform_user(id) ON DELETE SET NULL;


--
-- Name: platform_file platform_file_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_file
    ADD CONSTRAINT platform_file_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.platform_user(id) ON DELETE SET NULL;


--
-- Name: platform_localization_locale platform_localization_locale_fallback_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_localization_locale
    ADD CONSTRAINT platform_localization_locale_fallback_fk FOREIGN KEY (fallback_locale) REFERENCES public.platform_localization_locale(locale_code) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: platform_localization_locale platform_localization_locale_source_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_localization_locale
    ADD CONSTRAINT platform_localization_locale_source_fk FOREIGN KEY (source_locale) REFERENCES public.platform_localization_locale(locale_code) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: platform_login_challenge platform_login_challenge_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_login_challenge
    ADD CONSTRAINT platform_login_challenge_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_mfa_offline_recovery_audit platform_mfa_offline_recovery_audit_target_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_mfa_offline_recovery_audit
    ADD CONSTRAINT platform_mfa_offline_recovery_audit_target_user_id_fkey FOREIGN KEY (target_user_id) REFERENCES public.platform_user(id) ON DELETE RESTRICT;


--
-- Name: platform_mfa_recovery_request_rate platform_mfa_recovery_request_rate_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_mfa_recovery_request_rate
    ADD CONSTRAINT platform_mfa_recovery_request_rate_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_mfa_recovery_ticket platform_mfa_recovery_ticket_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_mfa_recovery_ticket
    ADD CONSTRAINT platform_mfa_recovery_ticket_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_module_revision platform_module_revision_module_state_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_revision
    ADD CONSTRAINT platform_module_revision_module_state_id_fkey FOREIGN KEY (module_state_id) REFERENCES public.platform_module_state(id) ON DELETE RESTRICT;


--
-- Name: platform_navigation_menu platform_navigation_menu_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_navigation_menu
    ADD CONSTRAINT platform_navigation_menu_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.platform_navigation_menu(id) ON DELETE RESTRICT;


--
-- Name: platform_notification platform_notification_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_notification
    ADD CONSTRAINT platform_notification_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.platform_event_outbox(id) ON DELETE SET NULL;


--
-- Name: platform_notification platform_notification_recipient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_notification
    ADD CONSTRAINT platform_notification_recipient_id_fkey FOREIGN KEY (recipient_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_organization platform_organization_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_organization
    ADD CONSTRAINT platform_organization_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.platform_organization(id) ON DELETE RESTRICT;


--
-- Name: platform_passkey_challenge platform_passkey_challenge_reauthentication_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_challenge
    ADD CONSTRAINT platform_passkey_challenge_reauthentication_challenge_id_fkey FOREIGN KEY (reauthentication_challenge_id) REFERENCES public.platform_reauthentication_challenge(id) ON DELETE CASCADE;


--
-- Name: platform_passkey_challenge platform_passkey_challenge_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_challenge
    ADD CONSTRAINT platform_passkey_challenge_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_passkey_credential platform_passkey_credential_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_credential
    ADD CONSTRAINT platform_passkey_credential_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_password_change_requirement platform_password_change_requirement_issued_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_password_change_requirement
    ADD CONSTRAINT platform_password_change_requirement_issued_by_fkey FOREIGN KEY (issued_by) REFERENCES public.platform_user(id);


--
-- Name: platform_password_change_requirement platform_password_change_requirement_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_password_change_requirement
    ADD CONSTRAINT platform_password_change_requirement_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_password_reset platform_password_reset_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_password_reset
    ADD CONSTRAINT platform_password_reset_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_plugin_installation platform_plugin_installation_digest_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_plugin_installation
    ADD CONSTRAINT platform_plugin_installation_digest_fkey FOREIGN KEY (digest) REFERENCES public.platform_plugin_artifact(digest);


--
-- Name: platform_profile_dictionary_selection platform_profile_dictionary_selection_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_profile_dictionary_selection
    ADD CONSTRAINT platform_profile_dictionary_selection_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.platform_dictionary_item(id) ON DELETE RESTRICT;


--
-- Name: platform_profile_dictionary_selection platform_profile_dictionary_selection_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_profile_dictionary_selection
    ADD CONSTRAINT platform_profile_dictionary_selection_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_public_auth_challenge platform_public_auth_challenge_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_public_auth_challenge
    ADD CONSTRAINT platform_public_auth_challenge_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_reauthentication_challenge platform_reauthentication_challenge_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_challenge
    ADD CONSTRAINT platform_reauthentication_challenge_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_reauthentication_code_challenge platform_reauthentication_cod_reauthentication_challenge_i_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_code_challenge
    ADD CONSTRAINT platform_reauthentication_cod_reauthentication_challenge_i_fkey FOREIGN KEY (reauthentication_challenge_id) REFERENCES public.platform_reauthentication_challenge(id) ON DELETE CASCADE;


--
-- Name: platform_reauthentication_code_challenge platform_reauthentication_code_challenge_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_code_challenge
    ADD CONSTRAINT platform_reauthentication_code_challenge_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_reauthentication_ticket platform_reauthentication_ticket_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_ticket
    ADD CONSTRAINT platform_reauthentication_ticket_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.platform_reauthentication_challenge(id) ON DELETE CASCADE;


--
-- Name: platform_reauthentication_ticket platform_reauthentication_ticket_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_reauthentication_ticket
    ADD CONSTRAINT platform_reauthentication_ticket_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_registration_completion platform_registration_completion_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_registration_completion
    ADD CONSTRAINT platform_registration_completion_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_registration_completion platform_registration_completion_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_registration_completion
    ADD CONSTRAINT platform_registration_completion_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.platform_public_auth_challenge(id) ON DELETE CASCADE;


--
-- Name: platform_registration_completion platform_registration_completion_proof_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_registration_completion
    ADD CONSTRAINT platform_registration_completion_proof_fkey FOREIGN KEY (proof_id) REFERENCES public.platform_public_auth_challenge(proof_id) ON DELETE CASCADE;


--
-- Name: platform_registration_policy platform_registration_policy_default_role_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_registration_policy
    ADD CONSTRAINT platform_registration_policy_default_role_fkey FOREIGN KEY (default_role_id) REFERENCES public.platform_role(id) ON DELETE RESTRICT;


--
-- Name: platform_role_authentication_policy platform_role_authentication_policy_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_authentication_policy
    ADD CONSTRAINT platform_role_authentication_policy_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.platform_role(id) ON DELETE CASCADE;


--
-- Name: platform_role_authority platform_role_authority_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_authority
    ADD CONSTRAINT platform_role_authority_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.platform_role(id) ON DELETE CASCADE;


--
-- Name: platform_role_preview_bootstrap_audit platform_role_preview_bootstrap_audit_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_bootstrap_audit
    ADD CONSTRAINT platform_role_preview_bootstrap_audit_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.platform_user(id);


--
-- Name: platform_role_preview_bootstrap_audit platform_role_preview_bootstrap_audit_target_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_bootstrap_audit
    ADD CONSTRAINT platform_role_preview_bootstrap_audit_target_user_id_fkey FOREIGN KEY (target_user_id) REFERENCES public.platform_user(id);


--
-- Name: platform_role_preview_context platform_role_preview_context_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_context
    ADD CONSTRAINT platform_role_preview_context_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_role_preview_context platform_role_preview_context_session_primary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_context
    ADD CONSTRAINT platform_role_preview_context_session_primary_id_fkey FOREIGN KEY (session_primary_id) REFERENCES public.spring_session(primary_id) ON DELETE CASCADE;


--
-- Name: platform_role_preview_resource_ticket platform_role_preview_resource_ticket_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_resource_ticket
    ADD CONSTRAINT platform_role_preview_resource_ticket_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_role_preview_resource_ticket platform_role_preview_resource_ticket_session_primary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_preview_resource_ticket
    ADD CONSTRAINT platform_role_preview_resource_ticket_session_primary_id_fkey FOREIGN KEY (session_primary_id) REFERENCES public.spring_session(primary_id) ON DELETE CASCADE;


--
-- Name: platform_second_factor_requirement platform_second_factor_requirement_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_second_factor_requirement
    ADD CONSTRAINT platform_second_factor_requirement_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_sensitive_role_policy platform_sensitive_role_policy_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_sensitive_role_policy
    ADD CONSTRAINT platform_sensitive_role_policy_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.platform_role(id) ON DELETE CASCADE;


--
-- Name: platform_setting_revision platform_setting_revision_setting_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting_revision
    ADD CONSTRAINT platform_setting_revision_setting_id_fkey FOREIGN KEY (setting_id) REFERENCES public.platform_setting(id) ON DELETE RESTRICT;


--
-- Name: platform_user_organization platform_user_organization_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_organization
    ADD CONSTRAINT platform_user_organization_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.platform_organization(id) ON DELETE RESTRICT;


--
-- Name: platform_user_organization platform_user_organization_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_organization
    ADD CONSTRAINT platform_user_organization_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_user_role platform_user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_role
    ADD CONSTRAINT platform_user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.platform_role(id) ON DELETE RESTRICT;


--
-- Name: platform_user_role platform_user_role_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_role
    ADD CONSTRAINT platform_user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_wechat_attempt platform_wechat_attempt_contact_proof_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_wechat_attempt
    ADD CONSTRAINT platform_wechat_attempt_contact_proof_id_fkey FOREIGN KEY (contact_proof_id) REFERENCES public.platform_public_auth_challenge(proof_id) ON DELETE CASCADE;


--
-- Name: platform_wechat_attempt platform_wechat_attempt_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_wechat_attempt
    ADD CONSTRAINT platform_wechat_attempt_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_wechat_binding platform_wechat_binding_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_wechat_binding
    ADD CONSTRAINT platform_wechat_binding_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_setting_revision setting_revision_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting_revision
    ADD CONSTRAINT setting_revision_source FOREIGN KEY (setting_id, source_version) REFERENCES public.platform_setting_revision(setting_id, version);


--
-- Name: spring_session_attributes spring_session_attributes_session_primary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_session_primary_id_fkey FOREIGN KEY (session_primary_id) REFERENCES public.spring_session(primary_id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--
