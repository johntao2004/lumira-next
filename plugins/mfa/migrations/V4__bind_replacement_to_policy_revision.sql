-- A staged replacement is valid only under the authentication policy it started with.
-- Existing pending slots are invalidated safely; the active authenticator is untouched.
ALTER TABLE plugin_mfa_replacement
    ADD COLUMN authentication_revision uuid;

UPDATE plugin_mfa_replacement
   SET authentication_revision=gen_random_uuid();

ALTER TABLE plugin_mfa_replacement
    ALTER COLUMN authentication_revision SET NOT NULL;
