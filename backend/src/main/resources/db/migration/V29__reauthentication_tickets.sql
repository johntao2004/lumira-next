-- Challenges prove a password and, when currently required, an independent MFA factor.
-- Completed challenges mint action-bound tickets; tickets are one-use and session-bound.
CREATE TABLE platform_reauthentication_challenge (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    session_hash char(64) NOT NULL,
    action varchar(48) NOT NULL,
    authentication_revision uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    completed_at timestamptz,
    ticket_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_reauthentication_action_check CHECK (
        action IN ('MFA_ENROLL','MFA_RENAME','MFA_RECOVERY_REGENERATE','MFA_DISABLE','MFA_ADMIN_RESET','WECHAT_BIND','WECHAT_UNBIND')
    ),
    CONSTRAINT platform_reauthentication_completion_check CHECK (
        (completed_at IS NULL AND ticket_id IS NULL)
        OR (completed_at IS NOT NULL AND ticket_id IS NOT NULL)
    )
);
CREATE INDEX platform_reauthentication_challenge_expiry
    ON platform_reauthentication_challenge(expires_at);
CREATE INDEX platform_reauthentication_challenge_session
    ON platform_reauthentication_challenge(user_id, session_hash, created_at DESC);

CREATE TABLE platform_reauthentication_ticket (
    id uuid PRIMARY KEY,
    challenge_id uuid NOT NULL UNIQUE
        REFERENCES platform_reauthentication_challenge(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    session_hash char(64) NOT NULL,
    action varchar(48) NOT NULL,
    authentication_revision uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_reauthentication_ticket_action_check CHECK (
        action IN ('MFA_ENROLL','MFA_RENAME','MFA_RECOVERY_REGENERATE','MFA_DISABLE','MFA_ADMIN_RESET','WECHAT_BIND','WECHAT_UNBIND')
    )
);
CREATE INDEX platform_reauthentication_ticket_expiry
    ON platform_reauthentication_ticket(expires_at);
