INSERT INTO platform_authority(user_id, authority)
SELECT u.id, p.authority
  FROM platform_user u
 CROSS JOIN (VALUES ('platform:mfa:read'), ('platform:mfa:reset')) p(authority)
 WHERE u.username = 'admin'
   AND u.deleted_at IS NULL
   AND NOT EXISTS (
       SELECT 1
         FROM (VALUES
              ('platform:roles:create'),
              ('platform:roles:update'),
              ('platform:roles:delete'),
              ('platform:roles:assign')) needed(authority)
        WHERE NOT EXISTS (
            SELECT 1 FROM platform_authority a
             WHERE a.user_id = u.id AND a.authority = needed.authority
        )
   )
ON CONFLICT DO NOTHING;
