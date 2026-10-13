# Temporary password reset

Administrators can issue a temporary password from a user row when the user cannot complete a
self-service recovery. The operation requires `platform:users:reset-password`, an all-scope grant,
and visibility of the target account under the same permission. It accepts the user's optimistic
version, refuses self-reset and the last recovery administrator, and records only a secret-free
audit event. The one-time password is generated with the active security password policy, returned
once with `Cache-Control: no-store`, and expires after 30 minutes.

Issuing a reset replaces the existing password hash, advances the primary credential revision,
increments the user version, revokes persisted sessions and pending login challenges, and stores a
30-minute password-change requirement tied to that credential revision. The temporary password is
stored only as the normal password hash. A valid primary proof through password, passkey, email or
SMS code, or WeChat opens a session-bound `PASSWORD_CHANGE_REQUIRED` login stage before CAPTCHA,
MFA, or authenticated-session creation. The pending session has no security context and cannot call
authenticated application routes. The login state endpoint restores the stage after refresh; cancel
or expiry does not restore the consumed temporary password.

`POST /api/auth/login/password-change` requires the pending session's challenge id and CSRF token.
It rate-limits by that pending session, checks the current password policy, requires an exact
confirmation, and rejects the temporary password as the replacement. The challenge and requirement
are consumed atomically with replacing the hash and advancing the credential revision. Existing
sessions are revoked, the pending session is invalidated, and the user must log in again with the new
password. Any required MFA is enforced on that next login.

A second administrative reset invalidates the first temporary password and every challenge bound to
its credential revision. Verified email or SMS password recovery, and a successful authenticated
password change, also clear the requirement after setting a policy-valid password; those flows
advance the credential revision and revoke prior sessions through their existing mechanisms.
