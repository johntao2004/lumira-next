package com.lumira.platform.moduleadmin;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.modules.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModuleAdminService {
    public static final String UPDATE = PlatformOperation.MODULE_UPDATE.permission();

    public record View(
            String id,
            String name,
            String version,
            List<String> dependencies,
            boolean required,
            boolean core,
            boolean enabled,
            long stateVersion) {}

    public record State(String moduleId, boolean enabled, long version) {}

    /**
     * reason remains optional for older clients; module state changes do not require a user note.
     */
    public record Change(Boolean enabled, Long version, String reason) {}

    private final ModuleRegistry registry;
    private final ModuleStateStore states;
    private final AdministrationPort guard;
    private final ModuleStateChangeGuard changeGuard;

    public ModuleAdminService(
            ModuleRegistry registry, ModuleStateStore states, AdministrationPort guard) {
        this(registry, states, guard, (moduleId, enabled) -> {});
    }

    @Autowired
    public ModuleAdminService(
            ModuleRegistry registry,
            ModuleStateStore states,
            AdministrationPort guard,
            ModuleStateChangeGuard changeGuard) {
        this.registry = registry;
        this.states = states;
        this.guard = guard;
        this.changeGuard = changeGuard;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<View> list(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.MODULES);
        var current = new HashMap<String, ModuleStateStore.State>();
        states.states().forEach(state -> current.put(state.moduleId(), state));
        return registry.modules().stream()
                .map(
                        module -> {
                            var state = current.get(module.id());
                            return new View(
                                    module.id(),
                                    module.name(),
                                    module.version(),
                                    module.dependencies(),
                                    module.required(),
                                    module.core(),
                                    state.enabled(),
                                    state.version());
                        })
                .toList();
    }

    private static ResponseStatusException rejected(ModuleStateStore.Rejected error) {
        return new ResponseStatusException(
                switch (error.getMessage()) {
                    case "moduleNotFound" -> HttpStatus.NOT_FOUND;
                    case "requiredModule", "moduleDependencyConflict", "staleRecord" ->
                            HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                },
                error.getMessage());
    }

    @Transactional
    public State change(String actor, String id, Change input) {
        guard.requireOperationGlobal(actor, PlatformOperation.MODULE_UPDATE);
        if (input == null || input.enabled() == null || input.version() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidModuleChange");
        changeGuard.requireChangeAllowed(id, input.enabled());
        try {
            String reason =
                    input.reason() == null || input.reason().isBlank()
                            ? ModuleStateStore.DEFAULT_CHANGE_REASON
                            : input.reason().strip();
            var state = states.change(id, input.enabled(), input.version(), actor, reason);
            changeGuard.recordChange(id, input.enabled(), actor);
            return new State(state.moduleId(), state.enabled(), state.version());
        } catch (ModuleStateStore.Rejected error) {
            throw rejected(error);
        }
    }
}
