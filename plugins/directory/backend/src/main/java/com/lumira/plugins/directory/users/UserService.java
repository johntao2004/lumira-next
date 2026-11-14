package com.lumira.plugins.directory.users;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private static final java.security.SecureRandom IMPORT_CREDENTIAL_RANDOM =
            new java.security.SecureRandom();
    private static final char[] IMPORT_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] IMPORT_LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] IMPORT_DIGITS = "23456789".toCharArray();
    private static final char[] IMPORT_SPECIAL = "!@#$%^&*-_+".toCharArray();
    private static final char[] IMPORT_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*-_+".toCharArray();
    private final com.lumira.platform.common.AdministrationPort guard;
    private final UserRepository repository;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final AuditPort audit;
    private final SecurityPolicyPort security;

    @Autowired
    public UserService(
            UserRepository repository,
            org.springframework.security.crypto.password.PasswordEncoder encoder,
            com.lumira.platform.common.AdministrationPort guard,
            AuditPort audit,
            SecurityPolicyPort security) {
        this.repository = repository;
        this.encoder = encoder;
        this.guard = guard;
        this.audit = audit;
        this.security = security;
    }

    public UserService(
            UserRepository repository,
            org.springframework.security.crypto.password.PasswordEncoder encoder,
            com.lumira.platform.common.AdministrationPort guard,
            AuditPort audit) {
        this(repository, encoder, guard, audit, null);
    }

    public record CreateUser(String username, String displayName, String password) {}

    public record UpdateUser(String displayName, Boolean enabled, Long version) {}

    private static String name(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 200)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        return value.strip();
    }

    @Transactional
    public UserSummary create(CreateUser input, String actor) {
        return create(input, actor, PlatformOperation.USER_CREATE);
    }

    /**
     * Create an account with an undisclosed random credential; admins issue a normal reset later.
     */
    @Transactional
    public UserSummary createImported(String username, String displayName, String actor) {
        SecurityPolicyPort.Policy policy =
                security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy();
        String transientCredential = generateImportedCredential(policy, IMPORT_CREDENTIAL_RANDOM);
        return create(new CreateUser(username, displayName, transientCredential), actor);
    }

    static String generateImportedCredential(
            SecurityPolicyPort.Policy policy, java.security.SecureRandom random) {
        if (policy == null) policy = SecurityPolicyPort.Policy.defaults();
        int minimum = policy.passwordMinLength() == null ? 12 : policy.passwordMinLength();
        int length = Math.max(32, minimum);
        if (length > PasswordContract.MAX_PASSWORD_BYTES)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "passwordPolicyUnavailable");
        for (int attempt = 0; attempt < 256; attempt++) {
            char[] candidate = new char[length];
            candidate[0] = pick(IMPORT_UPPER, random);
            candidate[1] = pick(IMPORT_LOWER, random);
            candidate[2] = pick(IMPORT_DIGITS, random);
            candidate[3] = pick(IMPORT_SPECIAL, random);
            for (int index = 4; index < candidate.length; index++)
                candidate[index] = IMPORT_ALPHABET[random.nextInt(IMPORT_ALPHABET.length)];
            for (int index = candidate.length - 1; index > 0; index--) {
                int other = random.nextInt(index + 1);
                char value = candidate[index];
                candidate[index] = candidate[other];
                candidate[other] = value;
            }
            String value = new String(candidate);
            java.util.Arrays.fill(candidate, '\0');
            if (PasswordContract.withinMaximum(value) && policy.passwordValid(value)) return value;
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "passwordPolicyUnavailable");
    }

    private static char pick(char[] alphabet, java.security.SecureRandom random) {
        return alphabet[random.nextInt(alphabet.length)];
    }

    @Transactional
    public UserSummary createForAi(CreateUser input, String actor) {
        return create(input, actor, PlatformOperation.AI_CONSOLE_CREATE_USER);
    }

    private UserSummary create(CreateUser input, String actor, PlatformOperation operation) {
        if (input == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        String displayName = name(input.displayName());
        var policy =
                security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy();
        if (input.username() == null
                || !input.username().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")
                || !policy.passwordValid(input.password())
                || !PasswordContract.withinMaximum(input.password()))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        var hash = encoder.encode(input.password());
        guard.requireOperationActionGlobal(actor, operation);
        var id = java.util.UUID.randomUUID();
        repository.create(id, input.username(), displayName, hash);
        audit.append(actor, "user.create", id);
        return repository.find(id);
    }

    @Transactional
    public UserSummary update(java.util.UUID id, UpdateUser input, String actor) {
        return update(id, input, actor, PlatformOperation.USER_UPDATE);
    }

    @Transactional
    public UserSummary updateForAi(java.util.UUID id, UpdateUser input, String actor) {
        return update(id, input, actor, PlatformOperation.AI_CONSOLE_UPDATE_USER);
    }

    private UserSummary update(
            java.util.UUID id, UpdateUser input, String actor, PlatformOperation operation) {
        String displayName = name(input.displayName());
        if (input.enabled() == null || input.version() == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        guard.requireOperationUser(actor, id, operation);
        var user = repository.find(id);
        if (user.version() != input.version())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "staleRecord");
        if (!input.enabled()
                && (user.username().equals(actor) || repository.isLastAdministrator(id)))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "protectedAccount");
        repository.update(id, displayName, input.enabled());
        if (!input.enabled()) repository.revokeSessions(user.username());
        audit.append(actor, "user.update", id);
        return repository.find(id);
    }

    /** Apply the same user-scope authorization before a profile-value-only edit. */
    public void requireUpdate(java.util.UUID id, String actor) {
        guard.requireOperationUser(actor, id, PlatformOperation.USER_UPDATE);
    }

    /** Update the built-in profile columns through the same scoped user-update permission. */
    @Transactional
    public UserSummary updateBasicProfileFields(
            java.util.UUID id,
            long expectedVersion,
            java.util.Map<String, String> values,
            String actor) {
        if (values == null
                || values.isEmpty()
                || values.keySet().stream()
                        .anyMatch(
                                key ->
                                        !java.util.Set.of("realName", "birthDate", "region")
                                                .contains(key)))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidProfileValues");
        guard.requireOperationUser(actor, id, PlatformOperation.USER_UPDATE);
        var current = repository.find(id);
        if (current.version() != expectedVersion)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "staleRecord");
        String realName = values.get("realName");
        String birthMonth = values.get("birthDate");
        String region = values.get("region");
        if ((realName != null && realName.length() > 200)
                || (region != null && region.length() > 200)
                || (birthMonth != null
                        && !birthMonth.isBlank()
                        && !birthMonth.matches("[0-9]{4}-(0[1-9]|1[0-2])")))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidProfileValues");
        if (birthMonth != null && !birthMonth.isBlank()) {
            try {
                if (java.time.YearMonth.parse(birthMonth).isAfter(java.time.YearMonth.now()))
                    throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "invalidProfileValues",
                        invalid);
            }
        }
        if (repository.updateBasicProfile(id, realName, birthMonth, region, expectedVersion) != 1)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "user.profile", id);
        return repository.findSnapshot(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSummary find(java.util.UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.USERS);
        guard.requireUser(actor, id, PlatformPageId.USERS.accessPermission());
        return repository.findSnapshot(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSummary findForAi(java.util.UUID id, String actor) {
        guard.requireOperationUser(actor, id, PlatformOperation.AI_CONSOLE_READ_USERS);
        return repository.findSnapshot(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSummary findForAiAction(
            java.util.UUID id, String actor, PlatformOperation operation) {
        if (operation.page() != PlatformPageId.AI_CONSOLE
                || (operation != PlatformOperation.AI_CONSOLE_READ_USERS
                        && operation != PlatformOperation.AI_CONSOLE_UPDATE_USER
                        && operation != PlatformOperation.AI_CONSOLE_DELETE_USER))
            throw new IllegalArgumentException("Unsupported AI user lookup operation");
        guard.requireOperationUser(actor, id, operation);
        return repository.findSnapshot(id);
    }

    @Transactional
    public void delete(java.util.UUID id, long version, String actor) {
        delete(id, version, actor, PlatformOperation.USER_DELETE);
    }

    @Transactional
    public void deleteForAi(java.util.UUID id, long version, String actor) {
        delete(id, version, actor, PlatformOperation.AI_CONSOLE_DELETE_USER);
    }

    private void delete(
            java.util.UUID id, long version, String actor, PlatformOperation operation) {
        guard.requireOperationUser(actor, id, operation);
        var user = repository.find(id);
        if (user.version() != version)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "staleRecord");
        if (user.username().equals(actor) || repository.isLastAdministrator(id))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "protectedAccount");
        if (repository.softDelete(id, version) != 1)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "staleRecord");
        repository.revokeSessions(user.username());
        audit.append(actor, "user.delete", id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<UserSummary> list(PageRequest request, String actor) {
        return list(request, actor, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<UserSummary> list(
            PageRequest request, String actor, RequestAuthorizationContext authorization) {
        guard.requirePage(actor, PlatformPageId.USERS);
        return repository.queryPage(request, actor, authorization);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<UserSummary> listForAi(PageRequest request, String actor) {
        guard.requireOperation(actor, PlatformOperation.AI_CONSOLE_READ_USERS);
        return repository.queryPage(
                request,
                actor,
                null,
                PlatformOperation.AI_CONSOLE_READ_USERS.permission(),
                PlatformPageId.AI_CONSOLE.accessPermission());
    }
}
