package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable action/session/policy-bound CAPTCHA challenges and one-time proofs. */
@Service
public class CaptchaProofService implements CaptchaProofPort, AuthenticationCapabilityContributor {
    public record ChallengeRequest(
            Action action, String attemptId, Boolean accessible, String locale) {
        public ChallengeRequest(Action action, String attemptId, Boolean accessible) {
            this(action, attemptId, accessible, null);
        }
    }

    public record PointerSample(double x, double y, long elapsedMs) {}

    public record ChallengeResponse(
            UUID challengeId,
            Action action,
            String attemptId,
            Instant expiresAt,
            String provider,
            String type,
            boolean accessibleAlternativeAvailable,
            String imageDataUrl,
            String backgroundDataUrl,
            String pieceDataUrl,
            String audioDataUrl,
            int width,
            int height,
            int pieceWidth,
            int pieceHeight,
            int pieceY) {}

    public record VerifyRequest(
            UUID challengeId,
            String answer,
            Double sliderX,
            Integer durationMs,
            List<PointerSample> trail) {}

    public record VerifyResponse(
            UUID captchaProofId, Action action, String attemptId, Instant expiresAt) {}

    private record ChallengeClaim(
            UUID challengeId,
            UUID claimId,
            String type,
            String responseDigest,
            String policyRevision,
            long settingsVersion,
            Action action,
            String attemptId) {}

    private record ClaimedRow(
            UUID id,
            Action action,
            String attemptId,
            String policyRevision,
            long settingsVersion,
            String type,
            String responseDigest,
            int attempts) {}

    private record VerificationOutcome(VerifyResponse response, String errorCode) {}

    private static final Set<Action> PUBLIC_ACTIONS =
            Set.of(
                    Action.PASSWORD_LOGIN,
                    Action.PASSKEY_LOGIN,
                    Action.WECHAT_LOGIN,
                    Action.LOGIN_CODE_SEND,
                    Action.LOGIN_CODE_VERIFY,
                    Action.REGISTRATION_SEND,
                    Action.REGISTRATION_VERIFY,
                    Action.REGISTRATION_COMPLETE,
                    Action.PASSWORD_RECOVERY_REQUEST,
                    Action.PASSWORD_RECOVERY_VERIFY,
                    Action.PASSWORD_RECOVERY_CONFIRM);
    private static final int CHALLENGE_TTL_SECONDS = 120;
    private static final int PROOF_TTL_SECONDS = 300;
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_TRAIL_POINTS = 64;
    private static final int MAX_SLIDER_DURATION_MS = CHALLENGE_TTL_SECONDS * 1000;

    private final JdbcClient jdbc;
    private final CaptchaSettingsService settings;
    private final LocalCaptchaAssets assets;
    private final ClientAddressResolver addresses;
    private final PublicAuthCrypto publicAuthCrypto;
    private final FixedWindowBuckets buckets;
    private final ObjectProvider<RoleAuthenticationPolicyPort> rolePolicies;
    private final TransactionTemplate shortTransaction;

    public CaptchaProofService(
            JdbcClient jdbc,
            CaptchaSettingsService settings,
            LocalCaptchaAssets assets,
            ClientAddressResolver addresses,
            PublicAuthCrypto publicAuthCrypto,
            FixedWindowBuckets buckets,
            ObjectProvider<RoleAuthenticationPolicyPort> rolePolicies,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.assets = assets;
        this.addresses = addresses;
        this.publicAuthCrypto = publicAuthCrypto;
        this.buckets = buckets;
        this.rolePolicies = rolePolicies;
        this.shortTransaction = new TransactionTemplate(transactionManager);
        this.shortTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.shortTransaction.setTimeout(8);
    }

    @Override
    public String id() {
        return "captcha";
    }

    @Override
    public Contribution authenticationCapabilities() {
        var snapshot = settings.current();
        var modes = snapshot.data().parsedActions();
        var requiredActions =
                modes.entrySet().stream()
                        .filter(entry -> entry.getValue() != CaptchaSettingsService.Mode.OFF)
                        .map(entry -> entry.getKey().name())
                        .sorted()
                        .toList();
        var alwaysActions =
                modes.entrySet().stream()
                        .filter(entry -> entry.getValue() == CaptchaSettingsService.Mode.ALWAYS)
                        .map(entry -> entry.getKey().name())
                        .sorted()
                        .toList();
        boolean required =
                modes.values().stream()
                        .anyMatch(mode -> mode == CaptchaSettingsService.Mode.ALWAYS);
        String type = requiredActions.isEmpty() ? null : snapshot.data().type();
        boolean ready = settings.readyFor(snapshot.data());
        return new Contribution(
                Set.of(),
                false,
                false,
                ready,
                false,
                new Registration(false, false, List.of(), null),
                new Captcha(required, type, requiredActions, alwaysActions));
    }

    @Override
    public AccountMethods accountMethods(UUID userId) {
        var snapshot = settings.current();
        return new AccountMethods(Set.of(), "captcha:" + snapshot.revision());
    }

    @Override
    @Transactional(readOnly = true)
    public Requirement requirement(Action action, UUID accountId, UUID defaultRoleId) {
        return requirement(action, accountId, defaultRoleId, RequestFacts.none());
    }

    @Override
    @Transactional(readOnly = true)
    public Requirement requirement(
            Action action, UUID accountId, UUID defaultRoleId, RequestFacts requestFacts) {
        if (action == null) throw invalid("captchaActionInvalid");
        var snapshot = settings.current();
        var data = snapshot.data();
        CaptchaSettingsService.Mode mode =
                data.parsedActions().getOrDefault(action, CaptchaSettingsService.Mode.OFF);
        boolean risk =
                mode == CaptchaSettingsService.Mode.RISK && riskTriggered(requestFacts, accountId);
        boolean required = mode == CaptchaSettingsService.Mode.ALWAYS || risk;
        var revisions = new ArrayList<String>();
        revisions.add(snapshot.revision().toString());
        revisions.add(action.name());
        revisions.add(mode.name());
        revisions.add(Boolean.toString(risk));
        RoleAuthenticationPolicyPort roles = rolePolicies.getIfAvailable();
        if (action == Action.POST_PRIMARY_AUTH && accountId != null && roles != null) {
            for (var role : roles.activeForUser(accountId)) {
                revisions.add(role.roleId() + ":" + role.roleVersion() + ":" + role.revision());
                required |= role.captchaMode() == RoleAuthenticationPolicyPort.CaptchaMode.ALWAYS;
            }
        } else if (action == Action.REGISTRATION_COMPLETE
                && defaultRoleId != null
                && roles != null) {
            var role = roles.forRole(defaultRoleId);
            if (role != null) {
                revisions.add(role.roleId() + ":" + role.roleVersion() + ":" + role.revision());
                required |= role.captchaMode() == RoleAuthenticationPolicyPort.CaptchaMode.ALWAYS;
            }
        }
        return new Requirement(
                required, digest(String.join("|", revisions)), required ? data.type() : null);
    }

    @Override
    public boolean readyForRequiredPolicy() {
        var snapshot = settings.current();
        return settings.readyFor(snapshot.data());
    }

    @Override
    @Transactional(readOnly = true)
    public void preflight(
            String proofId,
            Action action,
            String policyRevision,
            RequestFacts requestFacts,
            String expectedAttemptId) {
        if (action == null
                || policyRevision == null
                || policyRevision.isBlank()
                || requestFacts == null) throw invalid("captchaProofExpired");
        if (action == Action.POST_PRIMARY_AUTH
                && (expectedAttemptId == null || expectedAttemptId.isBlank()))
            throw invalid("captchaProofExpired");
        UUID id;
        try {
            id = UUID.fromString(proofId);
        } catch (RuntimeException error) {
            throw invalid("captchaProofExpired");
        }
        String session = requestFacts.sessionDigest();
        if (session == null) throw invalid("captchaProofExpired");
        boolean valid =
                jdbc.sql(
                                """
                        SELECT EXISTS(
                            SELECT 1 FROM platform_captcha_proof p
                              JOIN platform_captcha_challenge c ON c.id=p.challenge_id
                             WHERE p.id=:id AND p.session_digest=:session AND p.action=:action
                               AND p.policy_revision=:revision
                               AND p.attempt_id IS NOT DISTINCT FROM :attempt
                               AND p.consumed_at IS NULL AND p.expires_at>CURRENT_TIMESTAMP
                               AND c.state='VERIFIED' AND c.expires_at>CURRENT_TIMESTAMP
                        )
                        """)
                        .param("id", id)
                        .param("session", session)
                        .param("action", action.name())
                        .param("revision", policyRevision)
                        .param("attempt", expectedAttemptId)
                        .query(Boolean.class)
                        .single();
        if (!valid) throw invalid("captchaProofExpired");
    }

    @Transactional
    @Override
    public void consume(
            String proofId,
            Action action,
            String policyRevision,
            RequestFacts requestFacts,
            String expectedAttemptId) {
        if (action == null || policyRevision == null || requestFacts == null)
            throw invalid("captchaProofExpired");
        if (action == Action.POST_PRIMARY_AUTH
                && (expectedAttemptId == null || expectedAttemptId.isBlank()))
            throw invalid("captchaProofExpired");
        UUID id;
        try {
            id = UUID.fromString(proofId);
        } catch (RuntimeException error) {
            throw invalid("captchaProofExpired");
        }
        String session = requestFacts.sessionDigest();
        if (session == null) throw invalid("captchaProofExpired");
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:flow,0))")
                .param("flow", session + ":" + action.name())
                .query(
                        resultSet -> {
                            resultSet.next();
                            return 0;
                        });
        int consumed =
                jdbc.sql(
                                """
                        UPDATE platform_captcha_proof p
                           SET consumed_at=CURRENT_TIMESTAMP
                          FROM platform_captcha_challenge c
                         WHERE p.id=:id AND p.challenge_id=c.id AND c.state='VERIFIED'
                           AND p.session_digest=:session AND p.action=:action
                           AND p.policy_revision=:revision
                           AND p.attempt_id IS NOT DISTINCT FROM :attempt
                           AND p.consumed_at IS NULL AND p.expires_at>CURRENT_TIMESTAMP
                        """)
                        .param("id", id)
                        .param("session", session)
                        .param("action", action.name())
                        .param("revision", policyRevision)
                        .param("attempt", expectedAttemptId)
                        .update();
        if (consumed != 1) throw invalid("captchaProofExpired");
    }

    @Transactional
    public ChallengeResponse start(ChallengeRequest input, HttpServletRequest request) {
        if (input == null || input.action() == null || request == null)
            throw invalid("captchaActionInvalid");
        Action action = input.action();
        UUID accountId = null;
        UUID defaultRoleId = registrationDefaultRole(action);
        String attemptId = normalizeAttempt(input.attemptId());
        if (action == Action.POST_PRIMARY_AUTH) {
            if (attemptId == null || !validPrimaryAttempt(attemptId, request))
                throw invalid("secondFactorExpired");
            accountId = primaryAttemptAccount(attemptId, request);
            if (accountId == null) throw invalid("secondFactorExpired");
        } else if (!PUBLIC_ACTIONS.contains(action) || attemptId != null) {
            throw invalid("captchaActionInvalid");
        }
        RequestFacts requestFacts =
                CaptchaRequestFacts.from(request, addresses, publicAuthCrypto, true);
        String session = requestFacts.sessionDigest();
        if (!buckets.consume(requestFacts.sourceRateBucketKeys().get("captcha-source"), 60)
                || !buckets.consume(
                        requestFacts.sessionRateBucketKeys().get("captcha-session"), 12))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "captchaRateLimited");
        Requirement requirement = requirement(action, accountId, defaultRoleId, requestFacts);
        var snapshot = settings.current();
        boolean accessible = Boolean.TRUE.equals(input.accessible());
        if (accessible && !settings.readiness().accessibleAlternativeAvailable())
            throw unavailable("captchaAccessibleAlternativeUnavailable");
        if (!settings.readyFor(snapshot.data())) throw unavailable("captchaUnavailable");
        String type = accessible ? "AUDIO" : snapshot.data().type();

        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(CHALLENGE_TTL_SECONDS);
        LocalCaptchaAssets.Assets rendered = assets.create(type, input.locale());
        String responseDigest = responseDigest(id, type, rendered);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:flow,0))")
                .param("flow", session + ":" + action.name())
                .query(
                        resultSet -> {
                            resultSet.next();
                            return 0;
                        });
        jdbc.sql(
                        """
                        UPDATE platform_captcha_challenge
                           SET state='EXPIRED', claim_id=NULL, claimed_at=NULL
                         WHERE session_digest=:session AND action=:action
                           AND state IN ('PENDING','VERIFYING','VERIFIED')
                """)
                .param("session", session)
                .param("action", action.name())
                .update();
        jdbc.sql(
                        """
                        UPDATE platform_captcha_proof p SET consumed_at=CURRENT_TIMESTAMP
                          FROM platform_captcha_challenge c
                         WHERE p.challenge_id=c.id AND c.session_digest=:session AND c.action=:action
                           AND c.state='EXPIRED' AND p.consumed_at IS NULL
                        """)
                .param("session", session)
                .param("action", action.name())
                .update();
        jdbc.sql(
                        """
                        INSERT INTO platform_captcha_challenge
                            (id,session_digest,action,attempt_id,policy_revision,settings_version,
                             provider_app_id,accessible_alternative,challenge_type,verification_digest,expires_at)
                        VALUES (:id,:session,:action,:attempt,:revision,:version,'LOCAL',:accessible,:type,:digest,:expires)
                        """)
                .param("id", id)
                .param("session", session)
                .param("action", action.name())
                .param("attempt", attemptId)
                .param("revision", requirement.policyRevision())
                .param("version", snapshot.version())
                .param("accessible", accessible)
                .param("type", type)
                .param("digest", responseDigest)
                .param("expires", java.sql.Timestamp.from(expires))
                .update();
        return new ChallengeResponse(
                id,
                action,
                attemptId,
                expires,
                "LOCAL",
                type,
                settings.readiness().accessibleAlternativeAvailable(),
                rendered.imageDataUrl(),
                rendered.backgroundDataUrl(),
                rendered.pieceDataUrl(),
                rendered.audioDataUrl(),
                rendered.width(),
                rendered.height(),
                rendered.pieceWidth(),
                rendered.pieceHeight(),
                rendered.pieceY());
    }

    public VerifyResponse verify(VerifyRequest input, HttpServletRequest request) {
        validateVerifyInput(input);
        RequestFacts requestFacts =
                CaptchaRequestFacts.from(request, addresses, publicAuthCrypto, false);
        if (!buckets.consume(requestFacts.sourceRateBucketKeys().get("captcha-source"), 120))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "captchaRateLimited");
        String session = requestFacts.sessionDigest();
        if (session == null) throw invalid("captchaChallengeExpired");
        UUID claimId = UUID.randomUUID();
        ChallengeClaim claim =
                shortTransaction.execute(status -> claim(input, request, session, claimId));
        if (claim == null) throw invalid("captchaChallengeExpired");
        boolean correct = verifyLocal(claim, input);
        VerificationOutcome outcome =
                shortTransaction.execute(status -> finishVerification(claim, correct, request));
        if (outcome == null) throw invalid("captchaChallengeExpired");
        if (outcome.errorCode() != null) {
            if (outcome.errorCode().equals("captchaUnavailable"))
                throw unavailable(outcome.errorCode());
            throw invalid(outcome.errorCode());
        }
        return outcome.response();
    }

    private ChallengeClaim claim(
            VerifyRequest input, HttpServletRequest request, String session, UUID claimId) {
        ClaimedRow row =
                jdbc.sql(
                                """
                        SELECT id,action,attempt_id,policy_revision,settings_version,challenge_type,
                               verification_digest,attempts
                          FROM platform_captcha_challenge
                         WHERE id=:id AND session_digest=:session AND expires_at>CURRENT_TIMESTAMP
                           AND provider_app_id='LOCAL' AND verification_digest IS NOT NULL
                           AND attempts<:max AND (state='PENDING' OR state='VERIFYING' AND claimed_at<CURRENT_TIMESTAMP-INTERVAL '15 seconds')
                         FOR UPDATE
                        """)
                        .param("id", input.challengeId())
                        .param("session", session)
                        .param("max", MAX_ATTEMPTS)
                        .query(
                                (rs, n) ->
                                        new ClaimedRow(
                                                rs.getObject("id", UUID.class),
                                                Action.valueOf(rs.getString("action")),
                                                rs.getString("attempt_id"),
                                                rs.getString("policy_revision"),
                                                rs.getLong("settings_version"),
                                                rs.getString("challenge_type"),
                                                rs.getString("verification_digest"),
                                                rs.getInt("attempts")))
                        .optional()
                        .orElse(null);
        if (row == null) return null;
        UUID accountId =
                row.action() == Action.POST_PRIMARY_AUTH
                        ? primaryAttemptAccount(row.attemptId(), request)
                        : null;
        if (row.action() == Action.POST_PRIMARY_AUTH && accountId == null) {
            jdbc.sql(
                            "UPDATE platform_captcha_challenge SET state='EXPIRED',claim_id=NULL,claimed_at=NULL WHERE id=:id")
                    .param("id", row.id())
                    .update();
            return null;
        }
        UUID defaultRoleId = registrationDefaultRole(row.action());
        Requirement current =
                requirement(
                        row.action(),
                        accountId,
                        defaultRoleId,
                        CaptchaRequestFacts.from(request, addresses, publicAuthCrypto, false));
        var snapshot = settings.current();
        if (!row.policyRevision().equals(current.policyRevision())
                || row.settingsVersion() != snapshot.version()
                || !settings.readyFor(snapshot.data())) {
            jdbc.sql(
                            "UPDATE platform_captcha_challenge SET state='EXPIRED',claim_id=NULL,claimed_at=NULL WHERE id=:id")
                    .param("id", row.id())
                    .update();
            return null;
        }
        jdbc.sql(
                        "UPDATE platform_captcha_challenge SET state='VERIFYING',claim_id=:claim,claimed_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("claim", claimId)
                .param("id", row.id())
                .update();
        return new ChallengeClaim(
                row.id(),
                claimId,
                row.type(),
                row.responseDigest(),
                row.policyRevision(),
                row.settingsVersion(),
                row.action(),
                row.attemptId());
    }

    private VerificationOutcome finishVerification(
            ChallengeClaim claim, boolean correct, HttpServletRequest request) {
        var locked =
                jdbc.sql(
                                """
                        SELECT state,attempts,expires_at FROM platform_captcha_challenge
                         WHERE id=:id AND claim_id=:claim AND state='VERIFYING' FOR UPDATE
                        """)
                        .param("id", claim.challengeId())
                        .param("claim", claim.claimId())
                        .query(
                                (rs, n) ->
                                        new Object[] {
                                            rs.getString("state"),
                                            rs.getInt("attempts"),
                                            rs.getTimestamp("expires_at").toInstant()
                                        })
                        .optional()
                        .orElse(null);
        if (locked == null) return null;
        if (((Instant) locked[2]).isBefore(Instant.now())) {
            jdbc.sql(
                            "UPDATE platform_captcha_challenge SET state='EXPIRED',claim_id=NULL,claimed_at=NULL WHERE id=:id")
                    .param("id", claim.challengeId())
                    .update();
            return new VerificationOutcome(null, "captchaChallengeExpired");
        }
        UUID accountId =
                claim.action() == Action.POST_PRIMARY_AUTH
                        ? primaryAttemptAccount(claim.attemptId(), request)
                        : null;
        if (claim.action() == Action.POST_PRIMARY_AUTH && accountId == null) {
            jdbc.sql(
                            "UPDATE platform_captcha_challenge SET state='EXPIRED',claim_id=NULL,claimed_at=NULL WHERE id=:id")
                    .param("id", claim.challengeId())
                    .update();
            return new VerificationOutcome(null, "secondFactorExpired");
        }
        Requirement current =
                requirement(
                        claim.action(),
                        accountId,
                        registrationDefaultRole(claim.action()),
                        CaptchaRequestFacts.from(request, addresses, publicAuthCrypto, false));
        var snapshot = settings.current();
        if (!claim.policyRevision().equals(current.policyRevision())
                || claim.settingsVersion() != snapshot.version()) {
            jdbc.sql(
                            "UPDATE platform_captcha_challenge SET state='EXPIRED',claim_id=NULL,claimed_at=NULL WHERE id=:id")
                    .param("id", claim.challengeId())
                    .update();
            return new VerificationOutcome(null, "captchaPolicyChanged");
        }
        if (!correct) {
            int attempts = ((Integer) locked[1]) + 1;
            jdbc.sql(
                            "UPDATE platform_captcha_challenge SET attempts=:attempts,state=:state,claim_id=NULL,claimed_at=NULL WHERE id=:id")
                    .param("attempts", Math.min(MAX_ATTEMPTS, attempts))
                    .param("state", attempts >= MAX_ATTEMPTS ? "LOCKED" : "PENDING")
                    .param("id", claim.challengeId())
                    .update();
            return new VerificationOutcome(
                    null, attempts >= MAX_ATTEMPTS ? "captchaChallengeLocked" : "captchaInvalid");
        }
        UUID proof = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(PROOF_TTL_SECONDS);
        String session =
                CaptchaRequestFacts.from(request, addresses, publicAuthCrypto, false)
                        .sessionDigest();
        int changed =
                jdbc.sql(
                                """
                        UPDATE platform_captcha_challenge SET state='VERIFIED',claim_id=NULL,claimed_at=NULL
                         WHERE id=:id AND state='VERIFYING' AND claim_id=:claim
                        """)
                        .param("id", claim.challengeId())
                        .param("claim", claim.claimId())
                        .update();
        if (changed != 1) return null;
        jdbc.sql(
                        """
                        INSERT INTO platform_captcha_proof
                            (id,challenge_id,session_digest,action,attempt_id,policy_revision,expires_at)
                        VALUES (:id,:challenge,:session,:action,:attempt,:revision,:expires)
                        """)
                .param("id", proof)
                .param("challenge", claim.challengeId())
                .param("session", session)
                .param("action", claim.action().name())
                .param("attempt", claim.attemptId())
                .param("revision", claim.policyRevision())
                .param("expires", java.sql.Timestamp.from(expires))
                .update();
        return new VerificationOutcome(
                new VerifyResponse(proof, claim.action(), claim.attemptId(), expires), null);
    }

    private String responseDigest(
            UUID challengeId, String type, LocalCaptchaAssets.Assets rendered) {
        String canonical;
        if ("SLIDER".equals(type)) {
            canonical = "slider:" + rendered.sliderTargetX();
        } else {
            canonical = "answer:" + type + ":" + rendered.answer();
        }
        return publicAuthCrypto.fingerprint(
                "captcha:" + challengeId, canonical.getBytes(StandardCharsets.US_ASCII));
    }

    private boolean verifyLocal(ChallengeClaim claim, VerifyRequest input) {
        if ("IMAGE_TEXT".equals(claim.type()) || "AUDIO".equals(claim.type())) {
            if (input.answer() == null
                    || !input.answer().matches("\\d{6}")
                    || input.sliderX() != null
                    || input.trail() != null
                    || input.durationMs() != null) return false;
            String actual =
                    publicAuthCrypto.fingerprint(
                            "captcha:" + claim.challengeId(),
                            ("answer:" + claim.type() + ":" + input.answer())
                                    .getBytes(StandardCharsets.US_ASCII));
            return MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII),
                    claim.responseDigest().getBytes(StandardCharsets.US_ASCII));
        }
        if (!"SLIDER".equals(claim.type())
                || input.answer() != null
                || !validSliderMovement(input.sliderX(), input.durationMs(), input.trail()))
            return false;
        int submittedX = (int) Math.round(input.sliderX());
        boolean matches = false;
        for (int candidate = submittedX - LocalCaptchaAssets.SLIDER_TOLERANCE;
                candidate <= submittedX + LocalCaptchaAssets.SLIDER_TOLERANCE;
                candidate++) {
            String actual =
                    publicAuthCrypto.fingerprint(
                            "captcha:" + claim.challengeId(),
                            ("slider:" + candidate).getBytes(StandardCharsets.US_ASCII));
            matches |=
                    MessageDigest.isEqual(
                            actual.getBytes(StandardCharsets.US_ASCII),
                            claim.responseDigest().getBytes(StandardCharsets.US_ASCII));
        }
        return matches;
    }

    private static boolean validSliderMovement(
            Double sliderX, Integer durationMs, List<PointerSample> trail) {
        if (sliderX == null
                || !Double.isFinite(sliderX)
                || sliderX <= 0
                || sliderX > LocalCaptchaAssets.SLIDER_WIDTH
                || durationMs == null
                || durationMs < 1
                || durationMs > MAX_SLIDER_DURATION_MS
                || trail == null
                || trail.size() < 2
                || trail.size() > MAX_TRAIL_POINTS) return false;
        PointerSample first = trail.getFirst();
        PointerSample last = trail.getLast();
        if (!validPointer(first)
                || !validPointer(last)
                || Math.abs(first.x()) > 2
                || first.elapsedMs() != 0
                || Math.abs(last.x() - sliderX) > 2
                || last.elapsedMs() > durationMs) return false;
        double minimumX = first.x();
        double maximumX = first.x();
        long previousElapsed = -1;
        for (PointerSample point : trail) {
            if (!validPointer(point)
                    || point.elapsedMs() < previousElapsed
                    || point.elapsedMs() > durationMs) return false;
            previousElapsed = point.elapsedMs();
            minimumX = Math.min(minimumX, point.x());
            maximumX = Math.max(maximumX, point.x());
        }
        return maximumX - minimumX >= 1;
    }

    private static boolean validPointer(PointerSample point) {
        return point != null
                && Double.isFinite(point.x())
                && Double.isFinite(point.y())
                && point.x() >= 0
                && point.x() <= LocalCaptchaAssets.SLIDER_WIDTH
                && point.y() >= 0
                && point.y() <= LocalCaptchaAssets.SLIDER_HEIGHT
                && point.elapsedMs() >= 0
                && point.elapsedMs() <= MAX_SLIDER_DURATION_MS;
    }

    private UUID registrationDefaultRole(Action action) {
        if (action != Action.REGISTRATION_COMPLETE) return null;
        return jdbc.sql("SELECT default_role_id FROM platform_registration_policy WHERE id=1")
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private boolean riskTriggered(RequestFacts requestFacts, UUID accountId) {
        if (requestFacts != null
                && (bucketAtLeast(requestFacts.sourceRateBucketKeys(), "source", 8)
                        || bucketAtLeast(requestFacts.sourceRateBucketKeys(), "login-source", 8)
                        || bucketAtLeast(requestFacts.sourceRateBucketKeys(), "reg-source", 8)
                        || bucketAtLeast(requestFacts.sourceRateBucketKeys(), "regcomp-source", 3)
                        || bucketAtLeast(requestFacts.sourceRateBucketKeys(), "smsrec-source", 3)
                        || bucketAtLeast(requestFacts.sourceRateBucketKeys(), "mfa-source", 3)
                        || bucketAtLeast(requestFacts.sourceRateBucketKeys(), "wechat-source", 3)
                        || bucketAtLeast(
                                requestFacts.sourceRateBucketKeys(), "recovery-source", 3)))
            return true;
        if (requestFacts != null
                && requestFacts.sessionDigest() != null
                && (bucketAtLeast(requestFacts.sessionRateBucketKeys(), "captcha-session", 4)
                        || bucketAtLeast(requestFacts.sessionRateBucketKeys(), "login-session", 4)
                        || bucketAtLeast(requestFacts.sessionRateBucketKeys(), "reg-session", 4)
                        || bucketAtLeast(requestFacts.sessionRateBucketKeys(), "regcomp-session", 3)
                        || bucketAtLeast(requestFacts.sessionRateBucketKeys(), "smsrec-session", 3)
                        || bucketAtLeast(requestFacts.sessionRateBucketKeys(), "wechat-session", 4)
                        || bucketAtLeast(requestFacts.sessionRateBucketKeys(), "mfa-session", 3)))
            return true;
        return accountId != null
                && bucketAtLeast(FixedWindowBuckets.key("mfa-account", accountId.toString()), 3);
    }

    private boolean bucketAtLeast(Map<String, String> bucketKeys, String namespace, int threshold) {
        String key = bucketKeys == null ? null : bucketKeys.get(namespace);
        return key != null && bucketAtLeast(key, threshold);
    }

    private boolean bucketAtLeast(String key, int threshold) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_rate_bucket WHERE bucket_key=:key AND window_start=date_trunc('minute',CURRENT_TIMESTAMP) AND attempts>=:threshold)")
                .param("key", key)
                .param("threshold", threshold)
                .query(Boolean.class)
                .single();
    }

    private boolean validPrimaryAttempt(String attemptId, HttpServletRequest request) {
        return primaryAttemptAccount(attemptId, request) != null;
    }

    private UUID primaryAttemptAccount(String attemptId, HttpServletRequest request) {
        if (attemptId == null || request.getSession(false) == null) return null;
        UUID id;
        try {
            id = UUID.fromString(attemptId);
        } catch (RuntimeException error) {
            return null;
        }
        return jdbc.sql(
                        "SELECT user_id FROM platform_login_challenge WHERE id=:id AND session_hash=:session AND state='CAPTCHA_REQUIRED' AND expires_at>CURRENT_TIMESTAMP AND attempts<5")
                .param("id", id)
                .param(
                        "session",
                        FixedWindowBuckets.key("mfa-session", request.getSession(false).getId())
                                .substring(12))
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private static String normalizeAttempt(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) return null;
        if (attemptId.length() > 128 || attemptId.codePoints().anyMatch(Character::isISOControl))
            throw invalid("captchaActionInvalid");
        return attemptId;
    }

    private static void validateVerifyInput(VerifyRequest input) {
        if (input == null
                || input.challengeId() == null
                || input.answer() != null && input.answer().length() > 32
                || input.trail() != null && input.trail().size() > MAX_TRAIL_POINTS)
            throw invalid("captchaInvalid");
    }

    @Scheduled(
            fixedDelayString = "${lumira.captcha.cleanup-interval-ms:60000}",
            initialDelayString = "${lumira.captcha.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void cleanup() {
        jdbc.sql(
                        """
                        WITH expired AS (
                            SELECT id FROM platform_captcha_challenge
                             WHERE expires_at<=CURRENT_TIMESTAMP OR state IN ('LOCKED','EXPIRED')
                                OR state='VERIFYING' AND claimed_at<CURRENT_TIMESTAMP-INTERVAL '1 minute'
                             ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT 250
                        ) DELETE FROM platform_captcha_challenge c USING expired e WHERE c.id=e.id
                        """)
                .update();
        jdbc.sql(
                        """
                        WITH expired AS (
                            SELECT id FROM platform_captcha_proof
                             WHERE expires_at<=CURRENT_TIMESTAMP OR consumed_at IS NOT NULL
                             ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT 250
                        ) DELETE FROM platform_captcha_proof p USING expired e WHERE p.id=e.id
                        """)
                .update();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ResponseStatusException invalid(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException unavailable(String code) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, code);
    }
}
