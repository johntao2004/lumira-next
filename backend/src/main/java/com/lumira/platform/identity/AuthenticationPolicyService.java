package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the real account policy from shared host and module ports on every authentication
 * boundary.
 */
@Service
public class AuthenticationPolicyService implements EffectiveAuthenticationPolicyPort {
    public record AuthenticatorStatus(
            boolean available, Boolean configured, Integer recoveryCodesRemaining) {}

    public record AccountView(
            List<PrimaryMethod> enabledMethods,
            List<PrimaryMethod> usableMethods,
            boolean mfaRequired,
            List<RequirementSource> requirementSources,
            AuthenticatorStatus authenticator,
            String authenticationRevision) {}

    public record PublicMethods(
            List<PrimaryMethod> methods,
            Registration registration,
            Capabilities capabilities,
            Captcha captcha) {}

    public record Registration(
            boolean supported, boolean available, List<String> channels, String path) {}

    public record Capabilities(
            boolean loginCode, boolean wechat, boolean captcha, boolean rolePreview) {}

    public record Captcha(
            boolean required, String type, List<String> actions, List<String> alwaysActions) {}

    private final JdbcClient jdbc;
    private final GlobalAuthenticationPolicyPort global;
    private final RoleAuthenticationPolicyPort roles;
    private final PasskeyPolicyPort passkeys;
    private final PluginExtensionsPort plugins;
    private final ObjectProvider<AuthenticationCapabilityContributor> capabilityContributors;

    public AuthenticationPolicyService(
            JdbcClient jdbc,
            GlobalAuthenticationPolicyPort global,
            RoleAuthenticationPolicyPort roles,
            PasskeyPolicyPort passkeys,
            PluginExtensionsPort plugins,
            ObjectProvider<AuthenticationCapabilityContributor> capabilityContributors) {
        this.jdbc = jdbc;
        this.global = global;
        this.roles = roles;
        this.passkeys = passkeys;
        this.plugins = plugins;
        this.capabilityContributors = capabilityContributors;
    }

    @Override
    @Transactional(readOnly = true)
    public EffectivePolicy resolve(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId");
        record CredentialEpoch(
                UUID primary, UUID factor, boolean passwordPresent, boolean passkeyPresent) {}
        CredentialEpoch credentialEpoch =
                jdbc.sql(
                                """
                        SELECT u.primary_credential_revision,u.factor_credential_revision,
                               (u.password_hash IS NOT NULL) AS password_present,
                               EXISTS(SELECT 1 FROM platform_passkey_credential p WHERE p.user_id=u.id) AS passkey_present
                          FROM platform_user u WHERE u.id=:id AND u.enabled AND u.deleted_at IS NULL
                        """)
                        .param("id", userId)
                        .query(
                                (rs, row) ->
                                        new CredentialEpoch(
                                                rs.getObject(
                                                        "primary_credential_revision", UUID.class),
                                                rs.getObject(
                                                        "factor_credential_revision", UUID.class),
                                                rs.getBoolean("password_present"),
                                                rs.getBoolean("passkey_present")))
                        .optional()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "authenticationRequired"));
        var globalPolicy = global.current();
        List<RoleAuthenticationPolicyPort.RolePolicy> assignedRoles = roles.activeForUser(userId);
        EnumSet<PrimaryMethod> allowed =
                globalPolicy.enabledMethods().isEmpty()
                        ? EnumSet.noneOf(PrimaryMethod.class)
                        : EnumSet.copyOf(globalPolicy.enabledMethods());
        boolean roleRequiresMfa = false;
        boolean captchaRequired = false;
        var sources = new ArrayList<RequirementSource>();
        for (RoleAuthenticationPolicyPort.RolePolicy role : assignedRoles) {
            if (role.loginMode() == RoleAuthenticationPolicyPort.LoginMode.RESTRICT)
                allowed.retainAll(role.methods());
            if (role.mfaMode() == RoleAuthenticationPolicyPort.MfaMode.REQUIRED) {
                roleRequiresMfa = true;
                sources.add(new RequirementSource("ROLE", role.roleId()));
            }
            captchaRequired |=
                    role.captchaMode() == RoleAuthenticationPolicyPort.CaptchaMode.ALWAYS;
        }
        if (globalPolicy.mfaRequired()) sources.add(new RequirementSource("GLOBAL", null));

        record AccountRequirement(UUID version, boolean selfEnabled, boolean recoveryPending) {}
        AccountRequirement personal =
                jdbc.sql(
                                """
                                SELECT requirement_version,self_enabled,recovery_pending
                                  FROM platform_second_factor_requirement WHERE user_id=:id
                                """)
                        .param("id", userId)
                        .query(
                                (rs, row) ->
                                        new AccountRequirement(
                                                rs.getObject("requirement_version", UUID.class),
                                                rs.getBoolean("self_enabled"),
                                                rs.getBoolean("recovery_pending")))
                        .optional()
                        .orElse(null);
        if (personal != null && personal.selfEnabled())
            sources.add(new RequirementSource("SELF_ENABLED", null));
        if (personal != null && personal.recoveryPending())
            sources.add(new RequirementSource("RECOVERY_PENDING", null));

        EnumSet<PrimaryMethod> usable = EnumSet.noneOf(PrimaryMethod.class);
        if (allowed.contains(PrimaryMethod.PASSWORD) && credentialEpoch.passwordPresent())
            usable.add(PrimaryMethod.PASSWORD);
        PasskeyPolicyPort.Policy passkeyPolicy = passkeys.currentPolicy();
        if (allowed.contains(PrimaryMethod.PASSKEY)
                && Boolean.TRUE.equals(passkeyPolicy.enabled())
                && Boolean.TRUE.equals(passkeyPolicy.passwordlessEnabled())
                && credentialEpoch.passkeyPresent()) usable.add(PrimaryMethod.PASSKEY);
        var contributedRevisions = new TreeMap<String, String>();
        for (var contributor : capabilityContributors.orderedStream().toList()) {
            var account = contributor.accountMethods(userId);
            for (PrimaryMethod method : account.usableMethods())
                if (allowed.contains(method)) usable.add(method);
            contributedRevisions.put(contributor.id(), account.authenticationRevision());
        }

        boolean mfaRequired =
                globalPolicy.mfaRequired()
                        || roleRequiresMfa
                        || personal != null
                                && (personal.selfEnabled() || personal.recoveryPending());
        UUID revision =
                revision(
                        userId,
                        globalPolicy.revision(),
                        assignedRoles,
                        personal,
                        credentialEpoch,
                        allowed,
                        usable,
                        contributedRevisions);
        return new EffectivePolicy(
                revision,
                allowed,
                usable,
                mfaRequired,
                sources,
                captchaRequired,
                credentialEpoch.primary(),
                credentialEpoch.factor());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean requiredByPolicyExceptSelf(UUID userId) {
        boolean globalRequired = global.current().mfaRequired();
        if (globalRequired) return true;
        for (var role : roles.activeForUser(userId))
            if (role.mfaMode() == RoleAuthenticationPolicyPort.MfaMode.REQUIRED) return true;
        var status =
                jdbc.sql(
                                "SELECT recovery_pending FROM platform_second_factor_requirement WHERE user_id=:id")
                        .param("id", userId)
                        .query(Boolean.class)
                        .optional();
        return status.orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean secondFactorAvailable() {
        long availableProviders =
                plugins
                        .extensions(
                                SecondFactorProvider.class,
                                p ->
                                        p.id().equals("mfa")
                                                && p.available()
                                                && p.supportsRestrictedEnrollmentAndRecovery())
                        .stream()
                        .filter(Boolean.TRUE::equals)
                        .count();
        return availableProviders == 1;
    }

    @Transactional(readOnly = true)
    public AccountView accountView(UUID userId) {
        EffectivePolicy effective = resolve(userId);
        var auth = authenticatorStatus(userId);
        return new AccountView(
                effective.allowedMethods().stream().sorted().toList(),
                effective.usableMethods().stream().sorted().toList(),
                effective.mfaRequired(),
                effective.requirementSources(),
                auth,
                effective.revision().toString());
    }

    @Transactional(readOnly = true)
    public AuthenticatorStatus authenticatorStatus(UUID userId) {
        List<AuthenticatorStatus> availableProviders =
                plugins
                        .extensions(
                                SecondFactorProvider.class,
                                provider -> {
                                    if (!provider.id().equals("mfa") || !provider.available())
                                        return new AuthenticatorStatus(false, null, null);
                                    var status = provider.status(userId);
                                    return status == null
                                            ? new AuthenticatorStatus(true, null, null)
                                            : new AuthenticatorStatus(
                                                    true,
                                                    status.configured(),
                                                    status.recoveryCodesRemaining());
                                })
                        .stream()
                        .filter(AuthenticatorStatus::available)
                        .toList();
        return availableProviders.size() == 1
                ? availableProviders.getFirst()
                : new AuthenticatorStatus(false, null, null);
    }

    @Transactional(readOnly = true)
    public PublicMethods publicMethods() {
        var globalPolicy = global.current();
        var methods = new ArrayList<PrimaryMethod>();
        if (globalPolicy.enabledMethods().contains(PrimaryMethod.PASSWORD))
            methods.add(PrimaryMethod.PASSWORD);
        PasskeyPolicyPort.Policy passkeyPolicy = passkeys.currentPolicy();
        if (globalPolicy.enabledMethods().contains(PrimaryMethod.PASSKEY)
                && Boolean.TRUE.equals(passkeyPolicy.enabled())
                && Boolean.TRUE.equals(passkeyPolicy.passwordlessEnabled()))
            methods.add(PrimaryMethod.PASSKEY);
        boolean loginCode = false;
        boolean wechat = false;
        boolean captchaSupported = false;
        boolean rolePreview = false;
        boolean captchaRequired = false;
        String captchaType = null;
        var captchaActions = new LinkedHashSet<String>();
        var captchaAlwaysActions = new LinkedHashSet<String>();
        boolean registrationSupported = false;
        boolean registrationAvailable = false;
        var registrationChannels = new LinkedHashSet<String>();
        String registrationPath = null;
        for (var contributor : capabilityContributors.orderedStream().toList()) {
            var addition = contributor.authenticationCapabilities();
            for (PrimaryMethod method : addition.methods())
                if (globalPolicy.enabledMethods().contains(method) && !methods.contains(method))
                    methods.add(method);
            loginCode |= addition.loginCode();
            wechat |= addition.wechat();
            captchaSupported |= addition.captcha();
            rolePreview |= addition.rolePreview();
            captchaRequired |= addition.captchaPolicy().required();
            if (captchaType == null) captchaType = addition.captchaPolicy().type();
            captchaActions.addAll(addition.captchaPolicy().actions());
            captchaAlwaysActions.addAll(addition.captchaPolicy().alwaysActions());
            registrationSupported |= addition.registration().supported();
            registrationAvailable |= addition.registration().available();
            registrationChannels.addAll(addition.registration().channels());
            if (registrationPath == null) registrationPath = addition.registration().path();
        }
        loginCode &=
                (methods.contains(PrimaryMethod.EMAIL_OTP)
                        || methods.contains(PrimaryMethod.SMS_OTP));
        wechat &= methods.contains(PrimaryMethod.WECHAT);
        return new PublicMethods(
                List.copyOf(methods),
                new Registration(
                        registrationSupported,
                        registrationAvailable,
                        List.copyOf(registrationChannels),
                        registrationPath),
                new Capabilities(loginCode, wechat, captchaSupported, rolePreview),
                new Captcha(
                        captchaRequired,
                        captchaType,
                        List.copyOf(captchaActions),
                        List.copyOf(captchaAlwaysActions)));
    }

    private UUID revision(
            UUID userId,
            UUID globalRevision,
            List<RoleAuthenticationPolicyPort.RolePolicy> assignedRoles,
            Object personalRequirement,
            Object credentialEpoch,
            Set<PrimaryMethod> allowed,
            Set<PrimaryMethod> usable,
            Map<String, String> contributedRevisions) {
        StringBuilder value =
                new StringBuilder(userId.toString())
                        .append('|')
                        .append(globalRevision)
                        .append('|')
                        .append(credentialEpoch);
        for (var role : assignedRoles) {
            value.append('|')
                    .append(role.roleId())
                    .append(':')
                    .append(role.roleVersion())
                    .append(':')
                    .append(role.version())
                    .append(':')
                    .append(role.revision());
        }
        if (personalRequirement != null) value.append('|').append(personalRequirement);
        value.append('|')
                .append(allowed.stream().map(Enum::name).sorted().toList())
                .append('|')
                .append(usable.stream().map(Enum::name).sorted().toList())
                .append('|')
                .append(contributedRevisions);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            long high = java.nio.ByteBuffer.wrap(digest, 0, 8).getLong();
            long low = java.nio.ByteBuffer.wrap(digest, 8, 8).getLong();
            return new UUID(high, low);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
