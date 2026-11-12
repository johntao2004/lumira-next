package com.lumira.platform.identity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One-use credential facts selected by admission while the authentication policy lock is held. */
final class AdmissionCredentialProjection {
    // Keep the original record name/components: its textual form participates in policy revisions.
    record CredentialEpoch(
            UUID primary, UUID factor, boolean passwordPresent, boolean passkeyPresent) {}

    record LoginCodeFacts(
            UUID primaryCredentialRevision,
            String email,
            boolean emailVerified,
            Long emailVersion,
            String phone,
            boolean phoneVerified,
            Long phoneVersion) {}

    record WechatFacts(long version, String appId, String openIdDigest, String nickname) {}

    private record Projection(
            UUID actor,
            CredentialEpoch epoch,
            Map<Object, Object> resources,
            AtomicBoolean available,
            AtomicReference<LoginCodeFacts> loginCode,
            AtomicReference<WechatFacts> wechat,
            AtomicReference<UUID> captchaRevision,
            AtomicReference<Set<String>> globalAuthorities,
            AtomicReference<Set<String>> effectiveAuthorities) {}

    private static final ThreadLocal<Projection> CURRENT = new ThreadLocal<>();

    private AdmissionCredentialProjection() {}

    static <T> T within(UUID actor, CredentialEpoch epoch, Supplier<T> action) {
        return within(actor, epoch, null, null, action);
    }

    static <T> T within(
            UUID actor, CredentialEpoch epoch, Set<String> globalAuthorities, Supplier<T> action) {
        return within(actor, epoch, globalAuthorities, null, action);
    }

    static <T> T within(
            UUID actor,
            CredentialEpoch epoch,
            Set<String> globalAuthorities,
            Set<String> effectiveAuthorities,
            Supplier<T> action) {
        var resources = TransactionSynchronizationManager.getResourceMap();
        if (actor == null
                || epoch == null
                || epoch.primary() == null
                || epoch.factor() == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || resources.isEmpty()) return action.get();
        var previous = CURRENT.get();
        CURRENT.set(
                new Projection(
                        actor,
                        epoch,
                        Map.copyOf(resources),
                        new AtomicBoolean(true),
                        new AtomicReference<>(),
                        new AtomicReference<>(),
                        new AtomicReference<>(),
                        new AtomicReference<>(
                                globalAuthorities != null ? Set.copyOf(globalAuthorities) : null),
                        new AtomicReference<>(
                                effectiveAuthorities != null
                                        ? Set.copyOf(effectiveAuthorities)
                                        : null)));
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static boolean isActive(UUID actor) {
        if (actor == null) return false;
        var projection = CURRENT.get();
        if (projection == null
                || !actor.equals(projection.actor())
                || !TransactionSynchronizationManager.isActualTransactionActive()) return false;
        var currentResources = TransactionSynchronizationManager.getResourceMap();
        return projection.resources().entrySet().stream()
                .noneMatch(entry -> currentResources.get(entry.getKey()) != entry.getValue());
    }

    static Optional<CredentialEpoch> consume(UUID actor) {
        if (actor == null) return Optional.empty();
        var projection = CURRENT.get();
        if (projection == null
                || !actor.equals(projection.actor())
                || !TransactionSynchronizationManager.isActualTransactionActive())
            return Optional.empty();
        var currentResources = TransactionSynchronizationManager.getResourceMap();
        if (projection.resources().entrySet().stream()
                .anyMatch(entry -> currentResources.get(entry.getKey()) != entry.getValue()))
            return Optional.empty();
        return projection.available().compareAndSet(true, false)
                ? Optional.of(projection.epoch())
                : Optional.empty();
    }

    static void setAdmissionFacts(
            UUID actor, LoginCodeFacts loginCode, WechatFacts wechat, UUID captchaRevision) {
        if (actor == null || !isActive(actor)) return;
        var projection = CURRENT.get();
        if (projection != null) {
            if (loginCode != null) projection.loginCode().set(loginCode);
            if (wechat != null) projection.wechat().set(wechat);
            if (captchaRevision != null) projection.captchaRevision().set(captchaRevision);
        }
    }

    static LoginCodeFacts consumeLoginCodeFacts(UUID actor) {
        if (actor == null || !isActive(actor)) return null;
        var projection = CURRENT.get();
        if (projection == null) return null;
        return projection.loginCode().getAndSet(null);
    }

    static WechatFacts consumeWechatFacts(UUID actor) {
        if (actor == null || !isActive(actor)) return null;
        var projection = CURRENT.get();
        if (projection == null) return null;
        return projection.wechat().getAndSet(null);
    }

    static UUID consumeCaptchaRevision(UUID actor) {
        if (actor == null || !isActive(actor)) return null;
        var projection = CURRENT.get();
        if (projection == null) return null;
        return projection.captchaRevision().getAndSet(null);
    }

    static void setGlobalAuthorities(UUID actor, Set<String> globalAuthorities) {
        if (actor == null || !isActive(actor) || globalAuthorities == null) return;
        var projection = CURRENT.get();
        if (projection != null) {
            projection.globalAuthorities().set(Set.copyOf(globalAuthorities));
        }
    }

    static Set<String> consumeGlobalAuthorities(UUID actor) {
        if (actor == null || !isActive(actor)) return null;
        var projection = CURRENT.get();
        if (projection == null) return null;
        return projection.globalAuthorities().getAndSet(null);
    }

    static Set<String> getGlobalAuthorities(UUID actor) {
        if (actor == null || !isActive(actor)) return null;
        var projection = CURRENT.get();
        if (projection == null) return null;
        return projection.globalAuthorities().get();
    }

    static Set<String> globalAuthorities(UUID actor) {
        return getGlobalAuthorities(actor);
    }

    static Optional<Set<String>> findGlobalAuthorities(UUID actor) {
        return Optional.ofNullable(getGlobalAuthorities(actor));
    }

    static void setEffectiveAuthorities(UUID actor, Set<String> effectiveAuthorities) {
        if (actor == null || !isActive(actor) || effectiveAuthorities == null) return;
        var projection = CURRENT.get();
        if (projection != null) {
            projection.effectiveAuthorities().set(Set.copyOf(effectiveAuthorities));
        }
    }

    static Set<String> consumeEffectiveAuthorities(UUID actor) {
        if (actor == null || !isActive(actor)) return null;
        var projection = CURRENT.get();
        if (projection == null) return null;
        return projection.effectiveAuthorities().getAndSet(null);
    }

    static Set<String> getEffectiveAuthorities(UUID actor) {
        if (actor == null || !isActive(actor)) return null;
        var projection = CURRENT.get();
        if (projection == null) return null;
        return projection.effectiveAuthorities().get();
    }

    static Set<String> effectiveAuthorities(UUID actor) {
        return getEffectiveAuthorities(actor);
    }

    static Optional<Set<String>> findEffectiveAuthorities(UUID actor) {
        return Optional.ofNullable(getEffectiveAuthorities(actor));
    }
}
