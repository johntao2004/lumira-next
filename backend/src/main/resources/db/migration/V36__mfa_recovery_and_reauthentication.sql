-- P08 adds a separate reauthentication factor path and a restricted account recovery ticket.
ALTER TABLE platform_reauthentication_challenge
    DROP CONSTRAINT platform_reauthentication_action_check,
    ADD CONSTRAINT platform_reauthentication_action_check CHECK (
        action IN (
            'MFA_ENROLL', 'MFA_RENAME', 'MFA_RECOVERY_REGENERATE', 'MFA_DISABLE',
            'MFA_ADMIN_RESET', 'MFA_REPLACE', 'WECHAT_BIND', 'WECHAT_UNBIND'
        )
    );

ALTER TABLE platform_reauthentication_ticket
    DROP CONSTRAINT platform_reauthentication_ticket_action_check,
    ADD CONSTRAINT platform_reauthentication_ticket_action_check CHECK (
        action IN (
            'MFA_ENROLL', 'MFA_RENAME', 'MFA_RECOVERY_REGENERATE', 'MFA_DISABLE',
            'MFA_ADMIN_RESET', 'MFA_REPLACE', 'WECHAT_BIND', 'WECHAT_UNBIND'
        )
    );

CREATE TABLE platform_reauthentication_code_challenge (
    id uuid PRIMARY KEY,
    reauthentication_challenge_id uuid NOT NULL
        REFERENCES platform_reauthentication_challenge(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    session_hash char(64) NOT NULL CHECK (session_hash ~ '^[a-f0-9]{64}$'),
    method varchar(16) NOT NULL CHECK (method IN ('EMAIL_OTP', 'SMS_OTP')),
    contact_digest char(64) NOT NULL CHECK (contact_digest ~ '^[a-f0-9]{64}$'),
    contact_version bigint NOT NULL CHECK (contact_version > 0),
    primary_credential_revision uuid NOT NULL,
    factor_credential_revision uuid NOT NULL,
    code_digest char(64) CHECK (code_digest IS NULL OR code_digest ~ '^[a-f0-9]{64}$'),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'CONSUMED', 'SUPERSEDED', 'LOCKED')),
    attempts smallint NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_reauthentication_code_consumption_check
        CHECK ((state = 'CONSUMED') = (consumed_at IS NOT NULL))
);

CREATE UNIQUE INDEX platform_reauthentication_code_active_idx
    ON platform_reauthentication_code_challenge(reauthentication_challenge_id)
    WHERE state = 'PENDING';
CREATE INDEX platform_reauthentication_code_expiry_idx
    ON platform_reauthentication_code_challenge(expires_at)
    WHERE state = 'PENDING';
CREATE INDEX platform_reauthentication_code_user_created_idx
    ON platform_reauthentication_code_challenge(user_id, created_at DESC);

ALTER TABLE platform_reauthentication_challenge
    ADD COLUMN state varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'COMPLETED', 'SUPERSEDED', 'LOCKED'));
UPDATE platform_reauthentication_challenge
   SET state = CASE WHEN completed_at IS NOT NULL THEN 'COMPLETED' ELSE 'SUPERSEDED' END
 WHERE completed_at IS NOT NULL OR expires_at <= CURRENT_TIMESTAMP;
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY user_id, session_hash ORDER BY created_at DESC, id DESC) AS position
      FROM platform_reauthentication_challenge
     WHERE state = 'PENDING'
)
UPDATE platform_reauthentication_challenge challenge
   SET state = 'SUPERSEDED'
  FROM ranked
 WHERE challenge.id = ranked.id AND ranked.position > 1;
CREATE UNIQUE INDEX platform_reauthentication_one_pending_per_session_idx
    ON platform_reauthentication_challenge(user_id, session_hash)
    WHERE state = 'PENDING';
CREATE INDEX platform_reauthentication_cleanup_idx
    ON platform_reauthentication_challenge(expires_at, id);

ALTER TABLE platform_passkey_challenge
    DROP CONSTRAINT platform_passkey_challenge_kind_check,
    ADD COLUMN reauthentication_challenge_id uuid
        REFERENCES platform_reauthentication_challenge(id) ON DELETE CASCADE,
    ADD CONSTRAINT platform_passkey_challenge_kind_check
        CHECK (kind IN ('REGISTRATION', 'AUTHENTICATION', 'REAUTHENTICATION')),
    ADD CONSTRAINT platform_passkey_reauthentication_binding_check
        CHECK ((kind = 'REAUTHENTICATION') = (reauthentication_challenge_id IS NOT NULL));

CREATE TABLE platform_mfa_recovery_ticket (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    token_digest char(64) NOT NULL CHECK (token_digest ~ '^[a-f0-9]{64}$'),
    factor_credential_revision uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_mfa_recovery_ticket_state_check
        CHECK (NOT (consumed_at IS NOT NULL AND revoked_at IS NOT NULL))
);

CREATE UNIQUE INDEX platform_mfa_recovery_ticket_active_user_idx
    ON platform_mfa_recovery_ticket(user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE INDEX platform_mfa_recovery_ticket_expiry_idx
    ON platform_mfa_recovery_ticket(expires_at)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE platform_mfa_recovery_request_rate (
    user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,
    requested_at timestamptz,
    request_day date,
    request_count integer NOT NULL DEFAULT 0 CHECK (request_count BETWEEN 0 AND 1000),
    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 20),
    failure_day date
);

CREATE TABLE platform_mfa_offline_recovery_audit (
    event_id uuid PRIMARY KEY,
    target_user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE RESTRICT,
    target_username varchar(100) NOT NULL,
    operator varchar(100) NOT NULL CHECK (length(trim(operator)) BETWEEN 1 AND 100),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) BETWEEN 8 AND 500),
    delivery_method varchar(16) NOT NULL CHECK (delivery_method IN ('MAIL', 'FILE')),
    ticket_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX platform_mfa_offline_recovery_audit_target_idx
    ON platform_mfa_offline_recovery_audit (target_user_id, created_at DESC);
