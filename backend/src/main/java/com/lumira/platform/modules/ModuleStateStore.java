package com.lumira.platform.modules;

import com.lumira.platform.common.AuditPort;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Internal state engine. Administrative authorization and request gating belong in adapters. */
@Repository
public class ModuleStateStore implements ModuleRuntime, ModuleCapabilityPort {
    public static final String DEFAULT_CHANGE_REASON =
            "Module state changed through module management.";

    public record State(String moduleId, boolean enabled, long version) {}

    private record Stored(UUID id, String moduleId, boolean enabled, long version) {}

    public static class Rejected extends IllegalStateException {
        public Rejected(String code) {
            super(code);
        }
    }

    private final JdbcClient jdbc;
    private final ModuleRegistry registry;
    private final AuditPort audit;

    public ModuleStateStore(JdbcClient jdbc, ModuleRegistry registry, AuditPort audit) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.audit = audit;
    }

    private Map<String, Stored> stored() {
        var result = new HashMap<String, Stored>();
        jdbc.sql("SELECT id,module_id,enabled,version FROM platform_module_state")
                .query(Stored.class)
                .list()
                .forEach(row -> result.put(row.moduleId(), row));
        return result;
    }

    private State state(PlatformModule module, Map<String, Stored> records) {
        var row = records.get(module.id());
        return new State(
                module.id(),
                module.core() || (row != null && row.enabled() && registry.isLive(module.id())),
                row == null ? 0 : row.version());
    }

    @Transactional(readOnly = true)
    public List<State> states() {
        var records = stored();
        return registry.modules().stream().map(module -> state(module, records)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(String moduleId) {
        var module =
                registry.modules().stream()
                        .filter(m -> m.id().equals(moduleId))
                        .findFirst()
                        .orElse(null);
        if (module == null || !registry.isLive(moduleId)) return false;
        if (module.core()) return true;
        return jdbc.sql("SELECT enabled FROM platform_module_state WHERE module_id=:module")
                .param("module", moduleId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> enabledModules() {
        return states().stream().filter(State::enabled).map(State::moduleId).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int totalModules() {
        return registry.modules().size();
    }

    /** Lock order: adapters acquire AdministrationPort first, then this graph-wide lock. */
    @Transactional
    public State change(
            String moduleId, boolean enabled, long expectedVersion, String actor, String reason) {
        if (expectedVersion < 0
                || actor == null
                || actor.isBlank()
                || actor.length() > 100
                || reason == null
                || reason.isBlank()
                || reason.length() > 1000) throw new Rejected("invalidModuleChange");
        var module =
                registry.modules().stream()
                        .filter(m -> m.id().equals(moduleId))
                        .findFirst()
                        .orElseThrow(() -> new Rejected("moduleNotFound"));
        jdbc.sql("SELECT pg_advisory_xact_lock(481027)")
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
        var records = stored();
        var current = state(module, records);
        if (current.version() != expectedVersion) throw new Rejected("staleRecord");
        if (module.core() && !enabled) throw new Rejected("requiredModule");
        var proposed = new HashMap<String, Boolean>();
        registry.modules().forEach(m -> proposed.put(m.id(), state(m, records).enabled()));
        proposed.put(moduleId, enabled);
        for (var candidate : registry.modules())
            if (proposed.get(candidate.id()))
                for (var dependency : candidate.dependencies())
                    if (!proposed.get(dependency)) throw new Rejected("moduleDependencyConflict");
        if (current.enabled() == enabled) return current;
        var previous = records.get(moduleId);
        var id = previous == null ? UUID.randomUUID() : previous.id();
        long version = Math.addExact(current.version(), 1);
        jdbc.sql(
                        "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(:id,:module,:enabled,:version) ON CONFLICT(module_id) DO UPDATE SET enabled=EXCLUDED.enabled,version=EXCLUDED.version,updated_at=now()")
                .param("id", id)
                .param("module", moduleId)
                .param("enabled", enabled)
                .param("version", version)
                .update();
        audit.append(actor, enabled ? "module.enabled" : "module.disabled", id);
        return new State(moduleId, enabled, version);
    }
}
