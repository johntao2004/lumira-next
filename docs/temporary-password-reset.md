# Temporary password reset

Administrators can issue a temporary password from a user row when the user cannot complete a
self-service recovery. The operation requires `platform:users:reset-password` and visibility of
the target under both the Users page and reset permissions. The operator must hold the target's
effective permissions globally. The public-registration feedback grants are exempt when the
target holds them only at SELF scope. The operation accepts the user's optimistic
version, refuses self-reset and the last recovery administrator, and records only a secret-free
audit event. The one-time password is generated with the active security password policy, returned
once with `Cache-Control: no-store`, and expires after 30 minutes.

Issuing a reset replaces the existing password hash, advances the primary credential revision,
increments the user version, revokes persisted sessions and pending login challenges, and stores a
30-minute password-change requirement tied to that credential revision. The temporary password is
stored only as the normal password hash. A valid primary proof through password, passkey, email or
SMS code, or WeChat opens a session-bound `PASSWORD_CHANGE_REQUIRED` login stage before CAPTCHA,
MFA, or authenticated-session creation. The pending response includes
`passwordChangeReason: TEMPORARY_RESET` so the login page can explain the one-time reset flow. The
pending session has no security context and cannot call authenticated application routes. The login
state endpoint restores the stage after refresh; cancel or expiry does not restore the consumed
temporary password.

`POST /api/auth/login/password-change` requires the pending session's challenge id and CSRF token.
It rate-limits by that pending session, checks the current password policy, requires an exact
confirmation, and rejects the temporary password as the replacement. The challenge and requirement
are consumed atomically with replacing the hash and advancing the credential revision. The endpoint
returns the same standard `LoginResponse` as login and continues the current flow: if no further
step is required, the response is `COMPLETE` and authenticated; otherwise it returns the normal
unauthenticated CAPTCHA, MFA, enrollment, or recovery state. Existing authenticated sessions are
revoked, and successful completion rotates the current session ID and CSRF token before saving the
authenticated context. The user does not need to enter the new password again. Any required MFA or
other policy step is enforced in this continued flow.

A second administrative reset invalidates the first temporary password and every challenge bound to
its credential revision. Verified email or SMS password recovery, and a successful authenticated
password change, also clear the requirement after setting a policy-valid password; those flows
advance the credential revision and revoke prior sessions through their existing mechanisms.

The first administrator created from the local bootstrap configuration has a separate persistent
first-login password requirement. Its `PASSWORD_CHANGE_REQUIRED` response uses
`passwordChangeReason: BOOTSTRAP`, allowing the page to explain that the initial administrator must
choose a new password before sign-in can finish. Refresh restores the reason with the pending
challenge. Cancel or challenge expiry ends only that login attempt; the requirement remains, and the
administrator can authenticate again with the initial credential to reopen the change step. After a
successful change, the existing authenticated sessions are revoked and the current login continues
with the standard `LoginResponse`; successful completion rotates the session ID and CSRF token, then
saves the authenticated context. CAPTCHA, MFA, enrollment, or recovery requirements continue through
their existing unauthenticated states. The administrator does not need to enter the new password
again. Older responses without `passwordChangeReason` continue to use the temporary-reset wording.

A password an administrator enters directly when creating a user does not set a
first-login password-change requirement. The account can complete its first login
with that password, subject to the configured authentication policy. System-generated
temporary resets and the bootstrap administrator still require a password change.
