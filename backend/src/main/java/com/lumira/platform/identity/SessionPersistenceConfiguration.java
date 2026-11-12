package com.lumira.platform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Bounded coalescing of last-access-only JDBC session writes. Attributes, principal rotation, and
 * security state still save immediately.
 */
@Configuration(proxyBeanMethods = false)
public class SessionPersistenceConfiguration {

    private static final String GET_SESSION_QUERY =
            """
            SELECT S.PRIMARY_ID, S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES
            FROM %TABLE_NAME% S
            LEFT JOIN %TABLE_NAME%_ATTRIBUTES SA ON S.PRIMARY_ID = SA.SESSION_PRIMARY_ID
            WHERE S.SESSION_ID = ?::character(36)
            """;

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionTouchCustomizer(
            @Value("${lumira.session.touch-window-ms:0}") long windowMillis) {
        if (windowMillis < 0 || windowMillis > 1000) {
            throw new IllegalArgumentException(
                    "Session touch window must be between 0 and 1000 ms");
        }
        return repository -> {
            // Explicit revocation applies to every session, including non-expiring ones.
            repository.setDeleteSessionQuery("DELETE FROM %TABLE_NAME% WHERE SESSION_ID = ?");
            if (windowMillis > 0) {
                repository.setGetSessionQuery(GET_SESSION_QUERY);
                repository.setUpdateSessionQuery(updateQuery(windowMillis));
            }
        };
    }

    @Bean
    static BeanPostProcessor sessionRepositoryTouchOptimizerPostProcessor(
            @Value("${lumira.session.touch-window-ms:0}") long windowMillis) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FindByIndexNameSessionRepository<?> repository
                        && windowMillis > 0) {
                    return wrap(repository, windowMillis);
                }
                return bean;
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static <S extends Session> FindByIndexNameSessionRepository<Session> wrap(
            FindByIndexNameSessionRepository<S> delegate, long windowMillis) {
        if (delegate == null) {
            return null;
        }
        if (delegate instanceof OptimizingSessionRepository<?> opt) {
            return (FindByIndexNameSessionRepository<Session>) opt;
        }
        return new OptimizingSessionRepository<>(delegate, windowMillis);
    }

    static String updateQuery(long windowMillis) {
        if (windowMillis < 1 || windowMillis > 1000) {
            throw new IllegalArgumentException(
                    "Session touch window must be between 1 and 1000 ms");
        }
        // Spring Session 4.1 binds exactly these six parameters. Its attribute delta actions
        // run independently in the same transaction even when this metadata update is a no-op.
        return """
                UPDATE %TABLE_NAME% s
                   SET session_id=v.session_id,
                       last_access_time=GREATEST(s.last_access_time,v.last_access_time),
                       max_inactive_interval=v.max_inactive_interval,
                       expiry_time=CASE WHEN s.max_inactive_interval IS DISTINCT FROM v.max_inactive_interval
                           THEN CASE WHEN v.max_inactive_interval < 0 THEN v.expiry_time
                               ELSE GREATEST(s.last_access_time,v.last_access_time)
                                   + v.max_inactive_interval::bigint * 1000 END
                           ELSE GREATEST(s.expiry_time,v.expiry_time) END,
                       principal_name=v.principal_name
                  FROM (VALUES (?::character(36),?::bigint,?::integer,?::bigint,?::varchar,?::character(36)))
                       v(session_id,last_access_time,max_inactive_interval,expiry_time,principal_name,primary_id)
                 WHERE s.primary_id=v.primary_id
                   AND (s.session_id IS DISTINCT FROM v.session_id
                        OR s.principal_name IS DISTINCT FROM v.principal_name
                        OR s.max_inactive_interval IS DISTINCT FROM v.max_inactive_interval
                        OR v.last_access_time >= s.last_access_time + @window@
                        OR (s.max_inactive_interval >= 0 AND s.expiry_time <= v.last_access_time + @window@))
                """
                .replace("@window@", Long.toString(windowMillis));
    }

    public static final class OptimizingSessionRepository<S extends Session>
            implements FindByIndexNameSessionRepository<Session>, DisposableBean {
        private final FindByIndexNameSessionRepository<S> delegate;
        private final long windowMillis;

        public OptimizingSessionRepository(
                FindByIndexNameSessionRepository<S> delegate, long windowMillis) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.windowMillis = windowMillis;
        }

        public FindByIndexNameSessionRepository<S> getDelegate() {
            return delegate;
        }

        public long getWindowMillis() {
            return windowMillis;
        }

        @Override
        public Session createSession() {
            S created = delegate.createSession();
            return new TrackedSession<>(created, windowMillis, true);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void save(Session session) {
            if (session instanceof TrackedSession<?> tracked) {
                if (tracked.canSkipTouch()) {
                    return;
                }
                delegate.save((S) tracked.delegate);
                tracked.afterSave();
            } else {
                delegate.save((S) session);
            }
        }

        @Override
        public Session findById(String id) {
            S found = delegate.findById(id);
            if (found == null) {
                return null;
            }
            return new TrackedSession<>(found, windowMillis, false);
        }

        @Override
        public void deleteById(String id) {
            delegate.deleteById(id);
        }

        @Override
        public Map<String, Session> findByIndexNameAndIndexValue(
                String indexName, String indexValue) {
            Map<String, S> found = delegate.findByIndexNameAndIndexValue(indexName, indexValue);
            if (found == null || found.isEmpty()) {
                return found != null ? Collections.emptyMap() : null;
            }
            Map<String, Session> result = new LinkedHashMap<>(found.size());
            for (Map.Entry<String, S> entry : found.entrySet()) {
                result.put(
                        entry.getKey(),
                        new TrackedSession<>(entry.getValue(), windowMillis, false));
            }
            return result;
        }

        @Override
        public void destroy() throws Exception {
            if (delegate instanceof DisposableBean disposable) {
                disposable.destroy();
            }
        }
    }

    public static final class TrackedSession<S extends Session> implements Session {
        private final S delegate;
        private final long windowMillis;

        private volatile boolean isNew;
        private volatile boolean attributesModified;
        private volatile boolean intervalModified;
        private volatile boolean sessionIdChanged;
        private volatile String currentId;
        private volatile Instant persistedLastAccess;
        private volatile Duration persistedInterval;
        private volatile String persistedPrincipalName;

        TrackedSession(S delegate, long windowMillis, boolean isNew) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.windowMillis = windowMillis;
            this.isNew = isNew;
            this.currentId = delegate.getId();
            this.persistedLastAccess = isNew ? null : delegate.getLastAccessedTime();
            this.persistedInterval = isNew ? null : delegate.getMaxInactiveInterval();
            this.persistedPrincipalName = isNew ? null : getPrincipalName();
            this.attributesModified = false;
            this.intervalModified = false;
            this.sessionIdChanged = false;
        }

        public S getDelegate() {
            return delegate;
        }

        private String getPrincipalName() {
            Object principal =
                    delegate.getAttribute(
                            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
            return principal != null ? principal.toString() : null;
        }

        boolean canSkipTouch() {
            if (windowMillis <= 0
                    || isNew
                    || attributesModified
                    || intervalModified
                    || sessionIdChanged) {
                return false;
            }
            if (!Objects.equals(delegate.getId(), currentId)) {
                return false;
            }
            if (!Objects.equals(getPrincipalName(), persistedPrincipalName)) {
                return false;
            }
            Instant lastAccess = delegate.getLastAccessedTime();
            if (lastAccess == null || persistedLastAccess == null) {
                return false;
            }
            long lastAccessMillis = lastAccess.toEpochMilli();
            long persistedMillis = persistedLastAccess.toEpochMilli();

            if (lastAccessMillis >= persistedMillis + windowMillis) {
                return false;
            }

            Duration maxInactive = delegate.getMaxInactiveInterval();
            if (maxInactive != null && !maxInactive.isNegative()) {
                long persistedExpiryMillis = persistedMillis + maxInactive.toMillis();
                if (persistedExpiryMillis <= lastAccessMillis + windowMillis) {
                    return false;
                }
            }

            if (delegate.isExpired()) {
                return false;
            }

            return true;
        }

        void afterSave() {
            isNew = false;
            attributesModified = false;
            intervalModified = false;
            sessionIdChanged = false;
            currentId = delegate.getId();

            String currentPrincipal = getPrincipalName();
            boolean principalChanged = !Objects.equals(currentPrincipal, persistedPrincipalName);
            persistedPrincipalName = currentPrincipal;

            Instant lastAccess = delegate.getLastAccessedTime();
            if (lastAccess != null) {
                long lastAccessMillis = lastAccess.toEpochMilli();
                long currentPersistedMillis =
                        persistedLastAccess != null
                                ? persistedLastAccess.toEpochMilli()
                                : Long.MIN_VALUE;
                Duration maxInactive = delegate.getMaxInactiveInterval();
                boolean expiryTrigger =
                        maxInactive != null
                                && !maxInactive.isNegative()
                                && (currentPersistedMillis + maxInactive.toMillis()
                                        <= lastAccessMillis + windowMillis);

                if (persistedLastAccess == null
                        || lastAccessMillis >= currentPersistedMillis + windowMillis
                        || expiryTrigger
                        || principalChanged) {
                    persistedLastAccess = lastAccess;
                }
            }
            persistedInterval = delegate.getMaxInactiveInterval();
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public String changeSessionId() {
            sessionIdChanged = true;
            String newId = delegate.changeSessionId();
            currentId = newId;
            return newId;
        }

        @Override
        public <T> T getAttribute(String attributeName) {
            return delegate.getAttribute(attributeName);
        }

        @Override
        public <T> T getRequiredAttribute(String attributeName) {
            return delegate.getRequiredAttribute(attributeName);
        }

        @Override
        public <T> T getAttributeOrDefault(String attributeName, T defaultValue) {
            return delegate.getAttributeOrDefault(attributeName, defaultValue);
        }

        @Override
        public Set<String> getAttributeNames() {
            return delegate.getAttributeNames();
        }

        private static boolean isImmutableValue(Object value) {
            return value instanceof String
                    || value instanceof UUID
                    || value instanceof Number
                    || value instanceof Boolean
                    || value instanceof Instant
                    || value instanceof Duration
                    || value instanceof Enum<?>;
        }

        private static boolean isImmutableCollection(Collection<?> items) {
            if (items.size() > 16) {
                return false;
            }
            for (Object element : items) {
                if (element == null || !isImmutableValue(element)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isUnchangedAttribute(Object existing, Object next) {
            if (existing == null || next == null) {
                return false;
            }
            if (isImmutableValue(next) && Objects.equals(existing, next)) {
                return true;
            }
            if (existing instanceof CsrfToken c1
                    && next instanceof CsrfToken c2
                    && Objects.equals(c1.getToken(), c2.getToken())
                    && Objects.equals(c1.getHeaderName(), c2.getHeaderName())
                    && Objects.equals(c1.getParameterName(), c2.getParameterName())) {
                return true;
            }
            if (((existing instanceof List<?> && next instanceof List<?>)
                            || (existing instanceof Set<?> && next instanceof Set<?>))
                    && isImmutableCollection((Collection<?>) next)
                    && Objects.equals(existing, next)) {
                return true;
            }
            return false;
        }

        @Override
        public void setAttribute(String attributeName, Object attributeValue) {
            if (!isNew
                    && delegate.getAttributeNames().contains(attributeName)
                    && isUnchangedAttribute(delegate.getAttribute(attributeName), attributeValue)) {
                return;
            }
            attributesModified = true;
            delegate.setAttribute(attributeName, attributeValue);
        }

        @Override
        public void removeAttribute(String attributeName) {
            attributesModified = true;
            delegate.removeAttribute(attributeName);
        }

        @Override
        public Instant getCreationTime() {
            return delegate.getCreationTime();
        }

        @Override
        public void setLastAccessedTime(Instant lastAccessedTime) {
            delegate.setLastAccessedTime(lastAccessedTime);
        }

        @Override
        public Instant getLastAccessedTime() {
            return delegate.getLastAccessedTime();
        }

        @Override
        public void setMaxInactiveInterval(Duration interval) {
            intervalModified = true;
            delegate.setMaxInactiveInterval(interval);
        }

        @Override
        public Duration getMaxInactiveInterval() {
            return delegate.getMaxInactiveInterval();
        }

        @Override
        public boolean isExpired() {
            return delegate.isExpired();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj instanceof TrackedSession<?> other) {
                return delegate.equals(other.delegate);
            }
            return delegate.equals(obj);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
