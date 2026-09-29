package com.lumira.platform.identity;

import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import com.lumira.platform.identity.api.PasswordContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.dataformat.cbor.CBORFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Manual WebAuthn verifier for the scaffold. It keeps credentials and
 * one-time challenges in PostgreSQL, so every instance shares the same
 * replay protection and signature counter state.
 */
@Service
public class PasskeyService {
    private static final String REGISTRATION = "REGISTRATION";
    private static final String AUTHENTICATION = "AUTHENTICATION";
    private static final int FLAG_UP = 0x01;
    private static final int FLAG_UV = 0x04;
    private static final int FLAG_BE = 0x08;
    private static final int FLAG_BS = 0x10;
    private static final int FLAG_AT = 0x40;
    private static final int MAX_LABEL = 128;

    private final PasskeyChallengeStore challenges;
    private final PasskeyCredentialStore credentials;
    private final PasskeyPolicyPort settings;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ObjectMapper cbor;
    private final PasswordEncoder passwordEncoder;
    private final UserDetailsService userDetailsService;
    private final MaintenancePolicyPort maintenance;
    private final LoginAudit loginAudit;
    private final ClientAddressResolver addresses;

    public PasskeyService(
            PasskeyChallengeStore challenges,
            PasskeyCredentialStore credentials,
            PasskeyPolicyPort settings,
            JdbcClient jdbc,
            ObjectMapper json,
            PasswordEncoder passwordEncoder,
            UserDetailsService userDetailsService,
            MaintenancePolicyPort maintenance,
            LoginAudit loginAudit,
            ClientAddressResolver addresses) {
        this.challenges = challenges;
        this.credentials = credentials;
        this.settings = settings;
        this.jdbc = jdbc;
        this.json = json;
        this.cbor = new ObjectMapper(new CBORFactory());
        this.passwordEncoder = passwordEncoder;
        this.userDetailsService = userDetailsService;
        this.maintenance = maintenance;
        this.loginAudit = loginAudit;
        this.addresses = addresses;
    }

    public boolean authenticationAvailable() {
        PasskeyPolicyPort.Policy policy = settings.currentPolicy();
        return Boolean.TRUE.equals(policy.enabled()) && Boolean.TRUE.equals(policy.passwordlessEnabled());
    }

    @Transactional
    public PasskeyOptions authenticationOptions(HttpServletRequest request) {
        PasskeyPolicyPort.Policy policy = requireEnabled(true);
        byte[] challenge = randomBytes(32);
        PasskeyChallengeStore.Challenge saved = challenges.create(
                AUTHENTICATION, challenge, null, null, sessionId(request), policy.challengeTtlSeconds());
        Map<String, Object> publicKey = new LinkedHashMap<>();
        publicKey.put("challenge", base64Url(challenge));
        publicKey.put("rpId", policy.rpId());
        publicKey.put("timeout", policy.challengeTtlSeconds() * 1000);
        publicKey.put("userVerification", "required");
        return new PasskeyOptions(saved.id().toString(), publicKey);
    }

    @Transactional
    public PasskeyOptions registrationOptions(Authentication actor, PasskeyRequests.Operation operation,
                                              HttpServletRequest request) {
        UserIdentity user = current(actor);
        verifyCurrentPassword(user.username(), operation == null ? null : operation.currentPassword());
        PasskeyPolicyPort.Policy policy = requireEnabled(false);
        byte[] challenge = randomBytes(32);
        byte[] userHandle = randomBytes(32);
        PasskeyChallengeStore.Challenge saved = challenges.create(
                REGISTRATION, challenge, user.id(), userHandle, sessionId(request), policy.challengeTtlSeconds());

        List<Map<String, Object>> excluded = credentials.list(user.id()).stream().map(this::descriptor).toList();
        Map<String, Object> publicKey = new LinkedHashMap<>();
        publicKey.put("challenge", base64Url(challenge));
        publicKey.put("rp", Map.of("id", policy.rpId(), "name", policy.rpName()));
        publicKey.put("user", Map.of("id", base64Url(userHandle), "name", user.username(), "displayName", user.displayName()));
        publicKey.put("pubKeyCredParams", List.of(
                Map.of("type", "public-key", "alg", -7),
                Map.of("type", "public-key", "alg", -257)));
        publicKey.put("timeout", policy.challengeTtlSeconds() * 1000);
        publicKey.put("excludeCredentials", excluded);
        publicKey.put("authenticatorSelection", Map.of(
                "residentKey", "required", "requireResidentKey", true, "userVerification", "required"));
        publicKey.put("attestation", "none");
        return new PasskeyOptions(saved.id().toString(), publicKey);
    }

    @Transactional
    public PasskeyCredentialView completeRegistration(Authentication actor, PasskeyRequests.Registration request,
                                                      HttpServletRequest httpRequest) {
        UserIdentity user = current(actor);
        PasskeyPolicyPort.Policy policy = requireEnabled(false);
        PasskeyChallengeStore.Challenge challenge = consume(request == null ? null : request.challengeId(), REGISTRATION, httpRequest);
        if (challenge.userId() == null || !challenge.userId().equals(user.id())) unauthorized("passkeyChallengeOwner");
        if (request == null || request.response() == null) invalid("invalidPasskeyRegistration");

        byte[] clientDataBytes = decode(request.response().clientDataJSON(), "clientDataJSON");
        parseClientData(clientDataBytes, "webauthn.create", challenge.challenge(), policy);
        AttestationData attestation = parseAttestation(request.response().attestationObject());
        byte[] rawId = decode(request.rawId(), "rawId");
        if (!MessageDigest.isEqual(rawId, attestation.credentialId())) invalid("passkeyCredentialMismatch");
        if (!MessageDigest.isEqual(attestation.rpIdHash(), sha256(policy.rpId().getBytes(StandardCharsets.UTF_8)))) {
            invalid("passkeyRpIdMismatch");
        }
        ensureUserVerified(attestation.flags());
        if (credentials.existsByCredentialId(attestation.credentialId())) conflict("passkeyAlreadyBound");
        String label = label(request.label(), httpRequest);
        String transports = transports(request.transports());
        PasskeyCredentialStore.Credential saved = credentials.insert(
                user.id(), challenge.userHandle(), attestation.credentialId(), attestation.publicKeyCose(),
                attestation.signCount(), transports, (attestation.flags() & FLAG_BE) != 0,
                (attestation.flags() & FLAG_BS) != 0, label);
        return view(saved);
    }

    @Transactional
    public Map<String, Object> completeAuthentication(PasskeyRequests.Authentication request,
                                                       HttpServletRequest httpRequest,
                                                       HttpServletResponse httpResponse) {
        PasskeyPolicyPort.Policy policy = requireEnabled(true);
        PasskeyChallengeStore.Challenge challenge = consume(request == null ? null : request.challengeId(), AUTHENTICATION, httpRequest);
        if (request == null || request.response() == null) invalid("invalidPasskeyAuthentication");
        byte[] clientDataBytes = decode(request.response().clientDataJSON(), "clientDataJSON");
        parseClientData(clientDataBytes, "webauthn.get", challenge.challenge(), policy);
        byte[] authenticatorData = decode(request.response().authenticatorData(), "authenticatorData");
        if (authenticatorData.length < 37) invalid("invalidPasskeyAuthenticatorData");
        if (!MessageDigest.isEqual(authenticatorDataRpIdHash(authenticatorData), sha256(policy.rpId().getBytes(StandardCharsets.UTF_8)))) {
            invalid("passkeyRpIdMismatch");
        }
        int flags = authenticatorData[32] & 0xff;
        ensureUserVerified(flags);
        byte[] credentialId = decode(request.rawId(), "rawId");
        PasskeyCredentialStore.Credential credential = credentials.findByCredentialIdForUpdate(credentialId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "passkeyNotFound"));
        if (request.response().userHandle() != null && !request.response().userHandle().isBlank()
                && !MessageDigest.isEqual(credential.userHandle(), decode(request.response().userHandle(), "userHandle"))) {
            unauthorized("passkeyUserHandleMismatch");
        }
        verifySignature(credential.publicKeyCose(), authenticatorData, clientDataBytes,
                decode(request.response().signature(), "signature"));
        long suppliedCounter = readSignCount(authenticatorData);
        if (credential.signCount() > 0 && suppliedCounter > 0 && suppliedCounter <= credential.signCount()) {
            unauthorized("passkeyCounterMismatch");
        }
        if (!credentials.updateUsage(credential.id(), credential.userId(), suppliedCounter, (flags & FLAG_BS) != 0)) {
            unauthorized("passkeyNotFound");
        }
        Account account = jdbc.sql("SELECT username,enabled FROM platform_user WHERE id=:id")
                .param("id", credential.userId())
                .query((rs, row) -> new Account(rs.getString("username"), rs.getBoolean("enabled")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "passkeyUserUnavailable"));
        if (!account.enabled()) unauthorized("passkeyUserDisabled");
        if (!maintenance.canLogin(account.username())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "maintenanceActive");
        }
        try {
            httpRequest.changeSessionId();
            UserDetails details = userDetailsService.loadUserByUsername(account.username());
            var authentication = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, httpRequest, httpResponse);
            loginAudit.record(account.username(), true, addresses.resolve(httpRequest), httpRequest.getHeader("User-Agent"));
            return Map.of("authenticated", true, "username", account.username());
        } catch (RuntimeException failure) {
            SecurityContextHolder.clearContext();
            var session = httpRequest.getSession(false);
            if (session != null) session.invalidate();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
        }
    }

    @Transactional(readOnly = true)
    public List<PasskeyCredentialView> list(Authentication actor) {
        return credentials.list(current(actor).id()).stream().map(this::view).toList();
    }

    @Transactional
    public PasskeyCredentialView rename(Authentication actor, UUID id, String label, String currentPassword) {
        UserIdentity user = current(actor);
        verifyCurrentPassword(user.username(), currentPassword);
        String normalized = normalizeLabel(label);
        return credentials.rename(id, user.id(), normalized).map(this::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "passkeyNotFound"));
    }

    @Transactional
    public void delete(Authentication actor, UUID id, PasskeyRequests.Operation operation) {
        UserIdentity user = current(actor);
        verifyCurrentPassword(user.username(), operation == null ? null : operation.currentPassword());
        if (!credentials.delete(id, user.id())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "passkeyNotFound");
    }

    private PasskeyPolicyPort.Policy requireEnabled(boolean passwordless) {
        PasskeyPolicyPort.Policy policy = settings.currentPolicy();
        if (!Boolean.TRUE.equals(policy.enabled())
                || (passwordless ? !Boolean.TRUE.equals(policy.passwordlessEnabled())
                                  : !Boolean.TRUE.equals(policy.selfBindingEnabled()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "passkeyFeatureDisabled");
        }
        return policy;
    }

    private UserIdentity current(Authentication actor) {
        if (actor == null || !actor.isAuthenticated() || actor.getName() == null || actor.getName().isBlank()) {
            unauthorized("authenticationRequired");
        }
        return jdbc.sql("SELECT id,username,display_name FROM platform_user WHERE username=:name AND enabled=true")
                .param("name", actor.getName())
                .query((rs, row) -> new UserIdentity(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticationRequired"));
    }

    private void verifyCurrentPassword(String username, String currentPassword) {
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currentPasswordRequired");
        }
        String hash = jdbc.sql("SELECT password_hash FROM platform_user WHERE username=:name AND enabled=true")
                .param("name", username).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticationRequired"));
        if (!PasswordContract.compatibleWithStoredHash(currentPassword, hash)
                || !passwordEncoder.matches(currentPassword, hash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "wrongCurrentPassword");
        }
    }

    private PasskeyChallengeStore.Challenge consume(String value, String kind, HttpServletRequest request) {
        UUID id;
        try { id = UUID.fromString(value == null ? "" : value.trim()); }
        catch (IllegalArgumentException invalid) { invalid("invalidPasskeyChallenge"); return null; }
        return challenges.consume(id, kind, sessionId(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "passkeyChallengeExpired"));
    }

    private void parseClientData(byte[] value, String expectedType, byte[] challenge, PasskeyPolicyPort.Policy policy) {
        try {
            JsonNode node = json.readTree(value);
            String type = node.path("type").asText();
            String receivedChallenge = node.path("challenge").asText();
            String origin = node.path("origin").asText();
            if (!expectedType.equals(type) || !base64Url(challenge).equals(receivedChallenge)) invalid("passkeyChallengeMismatch");
            if (!policy.allowedOrigins().contains(origin)) invalid("passkeyOriginNotAllowed");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPasskeyClientData");
        }
    }

    private AttestationData parseAttestation(String encoded) {
        try {
            JsonNode node = cbor.readTree(decode(encoded, "attestationObject"));
            byte[] authData = node.path("authData").binaryValue();
            if (authData == null || authData.length < 55 || (authData[32] & FLAG_AT) == 0) invalid("invalidPasskeyAttestation");
            int flags = authData[32] & 0xff;
            long counter = readSignCount(authData);
            int credentialLength = ((authData[53] & 0xff) << 8) | (authData[54] & 0xff);
            int credentialOffset = 55;
            int coseOffset = credentialOffset + credentialLength;
            if (credentialLength < 16 || coseOffset >= authData.length) invalid("invalidPasskeyCredential");
            return new AttestationData(authenticatorDataRpIdHash(authData), flags, counter,
                    Arrays.copyOfRange(authData, credentialOffset, coseOffset),
                    Arrays.copyOfRange(authData, coseOffset, authData.length));
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPasskeyAttestation");
        }
    }

    private void verifySignature(byte[] coseKey, byte[] authenticatorData, byte[] clientData,
                                 byte[] signatureBytes) {
        try {
            PublicKey key = publicKeyFromCose(coseKey);
            byte[] signed = ByteBuffer.allocate(authenticatorData.length + 32)
                    .put(authenticatorData).put(sha256(clientData)).array();
            Signature signature = Signature.getInstance(key instanceof java.security.interfaces.ECPublicKey
                    ? "SHA256withECDSA" : "SHA256withRSA");
            signature.initVerify(key);
            signature.update(signed);
            if (!signature.verify(signatureBytes)) unauthorized("passkeySignatureInvalid");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "passkeySignatureInvalid");
        }
    }

    private PublicKey publicKeyFromCose(byte[] value) throws Exception {
        JsonNode node = cbor.readTree(value);
        int kty = node.path("1").asInt(-1);
        if (kty == 2) {
            if (node.path("-1").asInt(-1) != 1) invalid("unsupportedPasskeyCurve");
            byte[] x = node.path("-2").binaryValue();
            byte[] y = node.path("-3").binaryValue();
            if (x == null || y == null || x.length != 32 || y.length != 32) invalid("invalidPasskeyPublicKey");
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                    new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), ecSpec));
        }
        if (kty == 3) {
            byte[] modulus = node.path("-1").binaryValue();
            byte[] exponent = node.path("-2").binaryValue();
            if (modulus == null || exponent == null || modulus.length < 256 || modulus.length > 512 || exponent.length > 8) {
                invalid("invalidPasskeyPublicKey");
            }
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, modulus), new BigInteger(1, exponent)));
        }
        invalid("unsupportedPasskeyAlgorithm");
        return null;
    }

    private void ensureUserVerified(int flags) {
        if ((flags & FLAG_UP) == 0 || (flags & FLAG_UV) == 0) unauthorized("passkeyUserVerificationRequired");
    }

    private Map<String, Object> descriptor(PasskeyCredentialStore.Credential credential) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "public-key");
        result.put("id", base64Url(credential.credentialId()));
        List<String> values = transportsList(credential.transports());
        if (!values.isEmpty()) result.put("transports", values);
        return result;
    }

    private PasskeyCredentialView view(PasskeyCredentialStore.Credential value) {
        return new PasskeyCredentialView(value.id(), value.label(), value.createdAt(), value.lastUsedAt());
    }

    private String label(String value, HttpServletRequest request) {
        if (value == null || value.isBlank()) {
            String agent = request.getHeader("User-Agent");
            value = agent == null || agent.isBlank() ? "Passkey" : "Passkey - " + agent.strip();
        }
        return normalizeLabel(value);
    }

    private String normalizeLabel(String value) {
        if (value == null) invalid("passkeyLabelRequired");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_LABEL
                || normalized.codePoints().anyMatch(Character::isISOControl)) invalid("invalidPasskeyLabel");
        return normalized;
    }

    private String transports(List<String> values) {
        if (values == null) return "";
        List<String> allowed = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
            if (!List.of("usb", "nfc", "ble", "internal", "hybrid", "smart-card").contains(normalized)) invalid("invalidPasskeyTransport");
            if (!allowed.contains(normalized)) allowed.add(normalized);
        }
        return String.join(",", allowed);
    }

    private List<String> transportsList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    private String sessionId(HttpServletRequest request) {
        return request.getSession(true).getId();
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        new java.security.SecureRandom().nextBytes(value);
        return value;
    }

    private byte[] decode(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 8192) invalid("invalidPasskey" + field);
        try { return Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPasskey" + field); }
    }

    private String base64Url(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }

    private byte[] authenticatorDataRpIdHash(byte[] value) { return Arrays.copyOfRange(value, 0, 32); }

    private long readSignCount(byte[] value) {
        return ((long) (value[33] & 0xff) << 24) | ((long) (value[34] & 0xff) << 16)
                | ((long) (value[35] & 0xff) << 8) | (value[36] & 0xffL);
    }

    private byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void invalid(String reason) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private void unauthorized(String reason) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason); }
    private void conflict(String reason) { throw new ResponseStatusException(HttpStatus.CONFLICT, reason); }

    private record UserIdentity(UUID id, String username, String displayName) {}
    private record Account(String username, boolean enabled) {}
    private record AttestationData(byte[] rpIdHash, int flags, long signCount,
                                   byte[] credentialId, byte[] publicKeyCose) {}
}
