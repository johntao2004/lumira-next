-- Global organization readers manage the complete hierarchy, including
-- disabled records. Scoped readers still see only their enabled organizations.
CREATE OR REPLACE FUNCTION public.platform_visible_organizations(actor_name text, permission_name text) RETURNS TABLE(organization_id uuid)
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
