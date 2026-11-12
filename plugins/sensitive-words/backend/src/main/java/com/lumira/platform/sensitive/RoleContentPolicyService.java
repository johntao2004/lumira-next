package com.lumira.platform.sensitive;

import com.lumira.platform.common.*;
import com.lumira.platform.modules.ModuleRuntime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoleContentPolicyService implements ContentPolicyPort, RegistrationContentPolicyPort {
    public record Input(String mode, Long version) {}

    private record Snapshot(long revision, SensitiveWordMatcher matcher) {}

    private final RoleContentPolicyRepository policies;
    private final SensitiveWordRepository words;
    private final SensitiveWordService dictionary;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ModuleRuntime modules;
    private volatile Snapshot snapshot;

    public RoleContentPolicyService(
            RoleContentPolicyRepository policies,
            SensitiveWordRepository words,
            SensitiveWordService dictionary,
            AdministrationPort guard,
            AuditPort audit,
            ModuleRuntime modules) {
        this.policies = policies;
        this.words = words;
        this.dictionary = dictionary;
        this.guard = guard;
        this.audit = audit;
        this.modules = modules;
    }

    public PageResult<RoleContentPolicyRepository.Policy> list(PageRequest page, String actor) {
        requireGlobalOperation(actor, SensitiveWordService.READ);
        return new PageResult<>(
                policies.list(page), policies.count(page), page.page(), page.pageSize());
    }

    public PageResult<RoleContentPolicyRepository.Hit> hits(PageRequest page, String actor) {
        requireGlobalOperation(actor, SensitiveWordService.READ);
        return new PageResult<>(
                policies.hits(page), policies.hitCount(page), page.page(), page.pageSize());
    }

    @Transactional
    public RoleContentPolicyRepository.Policy save(UUID id, Input input, String actor) {
        requireGlobalOperation(actor, SensitiveWordService.POLICY_UPDATE);
        var current =
                policies.find(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "invalidRole"));
        if (input == null || input.version() == null || input.version() != current.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        if (input.mode() == null || !Set.of("ON", "OFF").contains(input.mode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        if (!policies.save(id, input.mode(), input.version(), actor))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "sensitiveRolePolicy.updated", id);
        return policies.find(id).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public ScanResult evaluate(String text) {
        return new ScanResult(dictionary.scanSubmission(text).blocked());
    }

    @Override
    @Transactional(readOnly = true)
    public ScanResult evaluate(String actor, String text, String source) {
        var txState = readOnlyTransactionState();
        boolean enabled =
                txState != null ? txState.enabled() : modules.isEnabled("sensitive-words");
        if (!enabled) return new ScanResult(false);

        String actorKey = actor == null ? "" : actor;
        List<RoleContentPolicyRepository.Policy> rolePolicies = null;
        Snapshot activeSnapshot = null;

        if (txState != null && txState.policiesByActor().containsKey(actorKey)) {
            rolePolicies = txState.policiesByActor().get(actorKey);
            activeSnapshot = txState.snapshot();
        } else {
            var currentSnapshot =
                    (txState != null && txState.snapshot() != null)
                            ? txState.snapshot()
                            : this.snapshot;
            if (currentSnapshot != null) {
                var combined = policies.forActorWithRevision(actor);
                if (combined != null) {
                    rolePolicies = combined.policies();
                    if (combined.revision() == currentSnapshot.revision()) {
                        activeSnapshot = currentSnapshot;
                    } else {
                        activeSnapshot = current();
                    }
                    if (txState != null) {
                        txState.setSnapshot(activeSnapshot);
                    }
                }
            }
            if (rolePolicies == null) {
                rolePolicies = policies.forActor(actor);
            }
            if (txState != null) {
                txState.policiesByActor().put(actorKey, rolePolicies);
            }
        }

        var result = scan(rolePolicies, text, txState, activeSnapshot);
        if (result.totalMatchCount() > 0)
            policies.record(
                    actor,
                    source,
                    result.blocked(),
                    result.totalMatchCount(),
                    rolePolicies.stream().map(RoleContentPolicyRepository.Policy::roleId).toList());
        return new ScanResult(result.blocked());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean blocked(UUID roleId, String text, String source) {
        if (roleId == null
                || text == null
                || text.isBlank()
                || !modules.isEnabled("sensitive-words")) return false;
        var rolePolicy =
                policies.find(roleId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT, "registrationRoleIneligible"));
        var result = scan(List.of(rolePolicy), text);
        if (result.totalMatchCount() > 0)
            policies.record(
                    "public-registration",
                    source == null ? "registration" : source,
                    result.blocked(),
                    result.totalMatchCount(),
                    List.of(roleId));
        return result.blocked();
    }

    public SensitiveWordService.CheckResult preview(UUID roleId, String text, String actor) {
        requireGlobalOperation(actor, SensitiveWordService.READ);
        if (text != null && text.length() > 200_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sensitiveTextTooLong");
        if (roleId == null) return dictionary.check(text, actor);
        var policy =
                policies.find(roleId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "invalidRole"));
        if (!policy.roleEnabled())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRole");
        var r = scan(List.of(policy), text);
        return new SensitiveWordService.CheckResult(
                r.totalMatchCount() > 0,
                r.blocked(),
                r.totalMatchCount(),
                r.blockingMatchDisplayed(),
                r.matches().stream()
                        .map(
                                m ->
                                        new SensitiveWordService.Match(
                                                m.word(), m.severity(), m.action(), mask(m.word())))
                        .toList());
    }

    private void requireGlobalOperation(String actor, String operation) {
        guard.require(actor, SensitiveWordService.ACCESS);
        guard.requireGlobal(actor, SensitiveWordService.ACCESS);
        guard.require(actor, operation);
        guard.requireGlobal(actor, operation);
    }

    private synchronized Snapshot current() {
        var stored = snapshot;
        var loaded = words.matcherSnapshot(stored == null ? -1 : stored.revision());
        if (stored != null && loaded.revision() == stored.revision()) return stored;
        var next =
                new Snapshot(
                        loaded.revision(),
                        new SensitiveWordMatcher(
                                loaded.entries().stream()
                                        .map(
                                                w ->
                                                        new SensitiveWordMatcher.Entry(
                                                                w.word(),
                                                                w.normalizedWord(),
                                                                w.severity(),
                                                                w.action()))
                                        .toList()));
        snapshot = next;
        return next;
    }

    SensitiveWordMatcher.ScanResult scan(
            List<RoleContentPolicyRepository.Policy> rolePolicies, String text) {
        return scan(rolePolicies, text, null, null);
    }

    private SensitiveWordMatcher.ScanResult scan(
            List<RoleContentPolicyRepository.Policy> rolePolicies,
            String text,
            TransactionEvaluationState txState,
            Snapshot preloadedSnapshot) {
        if (!rolePolicies.isEmpty() && rolePolicies.stream().allMatch(p -> "OFF".equals(p.mode())))
            return new SensitiveWordMatcher.ScanResult(List.of(), false, 0, false);
        Snapshot active;
        if (preloadedSnapshot != null) {
            active = preloadedSnapshot;
        } else if (txState != null) {
            active = txState.snapshot(this::current);
        } else {
            active = current();
        }
        return active.matcher().scan(text);
    }

    private TransactionEvaluationState readOnlyTransactionState() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager
                        .isCurrentTransactionReadOnly()
                || !org.springframework.transaction.support.TransactionSynchronizationManager
                        .isSynchronizationActive()) return null;
        for (var sync :
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .getSynchronizations()) {
            if (sync instanceof TransactionEvaluationState current && current.owner() == this) {
                return current;
            }
        }
        var created = new TransactionEvaluationState();
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(created);
        return created;
    }

    private final class TransactionEvaluationState
            implements org.springframework.transaction.support.TransactionSynchronization {
        private Boolean enabled;
        private final Map<String, List<RoleContentPolicyRepository.Policy>> policiesByActor =
                new HashMap<>();
        private Snapshot snapshot;

        RoleContentPolicyService owner() {
            return RoleContentPolicyService.this;
        }

        boolean enabled() {
            if (enabled == null) enabled = modules.isEnabled("sensitive-words");
            return enabled;
        }

        Map<String, List<RoleContentPolicyRepository.Policy>> policiesByActor() {
            return policiesByActor;
        }

        Snapshot snapshot() {
            return snapshot;
        }

        Snapshot snapshot(java.util.function.Supplier<Snapshot> loader) {
            if (snapshot == null) snapshot = loader.get();
            return snapshot;
        }

        void setSnapshot(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public void afterCompletion(int status) {
            enabled = null;
            policiesByActor.clear();
            snapshot = null;
        }
    }

    private String mask(String word) {
        return word.length() < 3
                ? "*".repeat(word.length())
                : word.substring(0, 1)
                        + "*".repeat(word.length() - 2)
                        + word.substring(word.length() - 1);
    }
}
