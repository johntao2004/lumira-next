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
        requirePageView(actor);
        return new PageResult<>(
                policies.list(page), policies.count(page), page.page(), page.pageSize());
    }

    public PageResult<RoleContentPolicyRepository.Hit> hits(PageRequest page, String actor) {
        requirePageView(actor);
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
    public ScanResult evaluate(String text) {
        return new ScanResult(dictionary.scanSubmission(text).blocked());
    }

    @Override
    public ScanResult evaluate(String actor, String text, String source) {
        if (!modules.isEnabled("sensitive-words")) return new ScanResult(false);
        var rolePolicies = policies.forActor(actor);
        var result = scan(rolePolicies, text);
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
        requirePageView(actor);
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

    private void requirePageView(String actor) {
        guard.require(actor, SensitiveWordService.ACCESS);
        guard.requireGlobal(actor, SensitiveWordService.ACCESS);
    }

    private void requireGlobalOperation(String actor, String operation) {
        requirePageView(actor);
        guard.require(actor, operation);
        guard.requireGlobal(actor, operation);
    }

    private synchronized Snapshot current() {
        var stored = snapshot;
        var loaded = words.matcherSnapshot(stored == null ? -1 : stored.revision());
        if (stored != null && loaded.revision() == stored.revision()) return stored;
        snapshot =
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
        return snapshot;
    }

    SensitiveWordMatcher.ScanResult scan(
            List<RoleContentPolicyRepository.Policy> rolePolicies, String text) {
        if (!rolePolicies.isEmpty() && rolePolicies.stream().allMatch(p -> "OFF".equals(p.mode())))
            return new SensitiveWordMatcher.ScanResult(List.of(), false, 0, false);
        return current().matcher().scan(text);
    }

    private String mask(String word) {
        return word.length() < 3
                ? "*".repeat(word.length())
                : word.substring(0, 1)
                        + "*".repeat(word.length() - 2)
                        + word.substring(word.length() - 1);
    }
}
