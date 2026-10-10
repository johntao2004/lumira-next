ALTER TABLE platform_captcha_challenge
    ADD COLUMN verification_digest char(64),
    ADD CONSTRAINT platform_captcha_verification_digest_check
        CHECK (verification_digest IS NULL OR verification_digest ~ '^[a-f0-9]{64}$');

UPDATE platform_captcha_proof
   SET consumed_at = COALESCE(consumed_at, CURRENT_TIMESTAMP)
 WHERE consumed_at IS NULL;

UPDATE platform_captcha_challenge
   SET state = 'EXPIRED', claim_id = NULL, claimed_at = NULL
 WHERE state IN ('PENDING', 'VERIFYING', 'VERIFIED');

UPDATE platform_captcha_settings
   SET version = version + 1,
       revision = gen_random_uuid(),
       updated_at = CURRENT_TIMESTAMP
 WHERE id = 1;
