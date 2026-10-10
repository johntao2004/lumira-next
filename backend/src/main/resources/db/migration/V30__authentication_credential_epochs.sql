ALTER TABLE platform_user
    ADD COLUMN primary_credential_revision uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN factor_credential_revision uuid NOT NULL DEFAULT gen_random_uuid();
