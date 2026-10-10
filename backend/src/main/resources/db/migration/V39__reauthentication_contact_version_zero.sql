-- Contact version zero is a valid legacy/default version for verified contacts.
-- Reauthentication challenges bind to the observed version without requiring it
-- to be positive.
ALTER TABLE platform_reauthentication_code_challenge
    DROP CONSTRAINT platform_reauthentication_code_challenge_contact_version_check,
    ADD CONSTRAINT platform_reauthentication_code_challenge_contact_version_check
        CHECK (contact_version >= 0);
