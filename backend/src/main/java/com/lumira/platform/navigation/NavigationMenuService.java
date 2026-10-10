package com.lumira.platform.navigation;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.modules.ModuleCapabilityPort;
import com.lumira.platform.modules.ModuleRegistry;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps navigation mutations version-safe and exposes a permission-filtered tree for the shell. The
 * same tree is used by tree and dual navigation.
 */
@Service
public class NavigationMenuService {
    public record Input(
            UUID parentId,
            String menuCode,
            String labelKey,
            String labelDefault,
            String menuType,
            String sectionKey,
            String path,
            String componentKey,
            String icon,
            String permissionKey,
            Boolean globalOnly,
            String moduleId,
            Integer sortOrder,
            Boolean enabled,
            Long version) {}

    public record ReorderItem(UUID id, UUID parentId, Integer sortOrder, Long version) {}

    public record RuntimeNode(
            UUID id,
            UUID parentId,
            String menuCode,
            String labelKey,
            String labelDefault,
            String menuType,
            String sectionKey,
            String path,
            String componentKey,
            String icon,
            String moduleId,
            boolean routable,
            List<RuntimeNode> children) {}

    private static final Comparator<NavigationMenuRepository.Menu> MENU_ORDER =
            Comparator.comparingInt(NavigationMenuRepository.Menu::sortOrder)
                    .thenComparing(NavigationMenuRepository.Menu::id);

    private static final class BuildFrame {
        private final NavigationMenuRepository.Menu node;
        private final List<NavigationMenuRepository.Menu> rows;
        private final List<RuntimeNode> included = new ArrayList<>();
        private int next;

        private BuildFrame(
                NavigationMenuRepository.Menu node, List<NavigationMenuRepository.Menu> rows) {
            this.node = node;
            this.rows = rows;
        }
    }

    private final NavigationMenuRepository repository;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final JdbcClient jdbc;
    private final ModuleCapabilityPort modules;
    private final ModuleRegistry registry;
    private final AuthorizationContextBoundaryPort authorizationBoundary;
    private final AuthorizationContextProjectionPort authorizationProjection;

    public NavigationMenuService(
            NavigationMenuRepository repository,
            AdministrationPort guard,
            AuditPort audit,
            JdbcClient jdbc,
            ModuleCapabilityPort modules,
            ModuleRegistry registry) {
        this(repository, guard, audit, jdbc, modules, registry, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NavigationMenuService(
            NavigationMenuRepository repository,
            AdministrationPort guard,
            AuditPort audit,
            JdbcClient jdbc,
            ModuleCapabilityPort modules,
            ModuleRegistry registry,
            AuthorizationContextBoundaryPort authorizationBoundary,
            AuthorizationContextProjectionPort authorizationProjection) {
        this.repository = repository;
        this.guard = guard;
        this.audit = audit;
        this.jdbc = jdbc;
        this.modules = modules;
        this.registry = registry;
        this.authorizationBoundary = authorizationBoundary;
        this.authorizationProjection = authorizationProjection;
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }

    @Transactional(readOnly = true)
    public List<NavigationMenuRepository.Menu> adminList(String actor) {
        guard.requireGlobal(actor, "platform:navigation:read");
        return repository.list(true);
    }

    @Transactional(readOnly = true)
    public NavigationMenuRepository.Menu find(UUID id, String actor) {
        guard.requireGlobal(actor, "platform:navigation:read");
        return repository.find(id);
    }

    @Transactional(readOnly = true)
    public List<RuntimeNode> runtime(String actor) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        var userId =
                jdbc.sql("SELECT id FROM platform_user WHERE username=:actor AND enabled=true")
                        .param("actor", actor)
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "unauthorized"));
        var authorization =
                authorizationProjection == null
                        ? java.util.Optional.<AuthorizationContextProjectionPort.Snapshot>empty()
                        : authorizationProjection.current(actor);
        var authorities =
                authorization
                        .map(snapshot -> new HashSet<>(snapshot.effectiveAuthorities()))
                        .orElseGet(
                                () ->
                                        new HashSet<>(
                                                jdbc.sql(
                                                                "SELECT authority FROM platform_effective_authority WHERE user_id=:id")
                                                        .param("id", userId)
                                                        .query(String.class)
                                                        .list()));
        var globalAuthorities =
                authorization
                        .map(snapshot -> new HashSet<>(snapshot.effectiveGlobalAuthorities()))
                        .orElseGet(
                                () ->
                                        new HashSet<>(
                                                jdbc.sql(
                                                                "SELECT authority FROM platform_global_authority WHERE user_id=:id")
                                                        .param("id", userId)
                                                        .query(String.class)
                                                        .list()));
        var enabledModules = new HashSet<>(modules.enabledModules());
        return tree(repository.list(false), authorities, globalAuthorities, enabledModules);
    }

    List<RuntimeNode> tree(
            List<NavigationMenuRepository.Menu> rows,
            Set<String> authorities,
            Set<String> globalAuthorities,
            Set<String> enabledModules) {
        var children = new HashMap<UUID, List<NavigationMenuRepository.Menu>>();
        for (var row : rows)
            children.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>()).add(row);
        children.values().forEach(list -> list.sort(MENU_ORDER));

        var roots = new ArrayList<RuntimeNode>();
        var stack = new ArrayDeque<BuildFrame>();
        var active = new HashSet<UUID>();
        stack.push(new BuildFrame(null, children.getOrDefault(null, List.of())));
        while (!stack.isEmpty()) {
            var frame = stack.peek();
            if (frame.next < frame.rows.size()) {
                var row = frame.rows.get(frame.next++);
                if (!row.enabled() || row.deleted()) continue;
                // A corrupt pre-existing cycle must not turn a read into a stack overflow or hang.
                if (!active.add(row.id())) continue;
                stack.push(new BuildFrame(row, children.getOrDefault(row.id(), List.of())));
                continue;
            }

            stack.pop();
            if (frame.node == null) {
                roots.addAll(frame.included);
                continue;
            }
            active.remove(frame.node.id());
            boolean permission =
                    frame.node.permissionKey() == null
                            || (frame.node.globalOnly() ? globalAuthorities : authorities)
                                    .contains(frame.node.permissionKey());
            boolean module =
                    frame.node.moduleId() == null || enabledModules.contains(frame.node.moduleId());
            if (!module || (!permission && frame.included.isEmpty())) continue;
            stack.peek()
                    .included
                    .add(
                            new RuntimeNode(
                                    frame.node.id(),
                                    frame.node.parentId(),
                                    frame.node.menuCode(),
                                    frame.node.labelKey(),
                                    frame.node.labelDefault(),
                                    frame.node.menuType(),
                                    frame.node.sectionKey(),
                                    frame.node.path(),
                                    frame.node.componentKey(),
                                    frame.node.icon(),
                                    frame.node.moduleId(),
                                    "PAGE".equals(frame.node.menuType()) && permission && module,
                                    List.copyOf(frame.included)));
        }
        return List.copyOf(roots);
    }

    @Transactional
    public NavigationMenuRepository.Menu create(Input input, String actor) {
        guard.require(actor, "platform:navigation:write");
        guard.requireGlobal(actor, "platform:navigation:write");
        repository.lockGraph();
        validate(null, input);
        var menuCode = input.menuCode().strip();
        var sectionKey = normalize(input.sectionKey());
        var path = normalize(input.path());
        var componentKey = normalize(input.componentKey());
        if (repository.codeExists(menuCode, null)) fail(HttpStatus.CONFLICT, "duplicateRecord");
        if ("PAGE".equals(input.menuType()) && repository.routeConflict(null, sectionKey, path))
            fail(HttpStatus.CONFLICT, "navigationRouteConflict");
        var id = UUID.randomUUID();
        try {
            repository.create(
                    id,
                    input.parentId(),
                    menuCode,
                    input.labelKey().strip(),
                    input.labelDefault().strip(),
                    input.menuType(),
                    sectionKey,
                    path,
                    componentKey,
                    normalize(input.icon()),
                    normalize(input.permissionKey()),
                    Boolean.TRUE.equals(input.globalOnly()),
                    normalize(input.moduleId()),
                    input.sortOrder() == null ? 0 : input.sortOrder(),
                    input.enabled() == null || input.enabled());
        } catch (DuplicateKeyException error) {
            if (repository.codeExists(menuCode, null)) fail(HttpStatus.CONFLICT, "duplicateRecord");
            fail(HttpStatus.CONFLICT, "navigationRouteConflict");
        }
        audit.append(actor, "navigation.create", id);
        return repository.find(id);
    }

    @Transactional
    public NavigationMenuRepository.Menu update(UUID id, Input input, String actor) {
        guard.require(actor, "platform:navigation:write");
        guard.requireGlobal(actor, "platform:navigation:write");
        repository.lockGraph();
        var old = repository.find(id);
        if (old.builtIn()) fail(HttpStatus.CONFLICT, "navigationBuiltIn");
        if (input.version() == null || old.version() != input.version())
            fail(HttpStatus.CONFLICT, "staleRecord");
        validate(id, input);
        var sectionKey = normalize(input.sectionKey());
        var path = normalize(input.path());
        var componentKey = normalize(input.componentKey());
        if (repository.routeConflict(
                id,
                "PAGE".equals(input.menuType()) ? sectionKey : null,
                "PAGE".equals(input.menuType()) ? path : null))
            fail(HttpStatus.CONFLICT, "navigationRouteConflict");
        try {
            if (repository.update(
                            id,
                            input.version(),
                            input.parentId(),
                            input.labelKey().strip(),
                            input.labelDefault().strip(),
                            input.menuType(),
                            sectionKey,
                            path,
                            componentKey,
                            normalize(input.icon()),
                            normalize(input.permissionKey()),
                            Boolean.TRUE.equals(input.globalOnly()),
                            normalize(input.moduleId()),
                            input.sortOrder() == null ? old.sortOrder() : input.sortOrder(),
                            input.enabled() == null || input.enabled())
                    != 1) fail(HttpStatus.CONFLICT, "staleRecord");
        } catch (DuplicateKeyException error) {
            fail(HttpStatus.CONFLICT, "navigationRouteConflict");
        }
        audit.append(actor, "navigation.update", id);
        return repository.find(id);
    }

    @Transactional
    public NavigationMenuRepository.Menu status(
            UUID id, long version, boolean enabled, String actor) {
        guard.require(actor, "platform:navigation:write");
        guard.requireGlobal(actor, "platform:navigation:write");
        repository.lockGraph();
        var old = repository.find(id);
        if (old.builtIn()) fail(HttpStatus.CONFLICT, "navigationBuiltIn");
        if (repository.setEnabled(id, version, enabled) != 1)
            fail(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "navigation.status", id);
        return repository.find(id);
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        guard.require(actor, "platform:navigation:write");
        guard.requireGlobal(actor, "platform:navigation:write");
        repository.lockGraph();
        var old = repository.find(id);
        if (old.builtIn()) fail(HttpStatus.CONFLICT, "navigationBuiltIn");
        if (repository.hasChildren(id)) fail(HttpStatus.CONFLICT, "navigationHasChildren");
        if (repository.softDelete(id, version) != 1) fail(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "navigation.delete", id);
    }

    @Transactional
    public List<NavigationMenuRepository.Menu> reorder(List<ReorderItem> items, String actor) {
        guard.require(actor, "platform:navigation:write");
        guard.requireGlobal(actor, "platform:navigation:write");
        repository.lockGraph();
        if (items == null || items.size() > 1000) fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
        var rows = repository.list(false);
        var byId = new HashMap<UUID, NavigationMenuRepository.Menu>();
        rows.forEach(row -> byId.put(row.id(), row));
        var proposedParents = new HashMap<UUID, UUID>();
        rows.forEach(row -> proposedParents.put(row.id(), row.parentId()));
        var seen = new HashSet<UUID>();
        for (var item : items) {
            if (item == null
                    || item.id() == null
                    || item.parentId() != null && item.id().equals(item.parentId())
                    || item.sortOrder() == null
                    || item.sortOrder() < 0
                    || !seen.add(item.id())) fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
            var old = byId.get(item.id());
            if (old == null || old.builtIn()) fail(HttpStatus.CONFLICT, "navigationBuiltIn");
            if (item.version() == null || old.version() != item.version())
                fail(HttpStatus.CONFLICT, "staleRecord");
            if (item.parentId() != null && !byId.containsKey(item.parentId()))
                fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
            if (item.parentId() != null && "ACTION".equals(byId.get(item.parentId()).menuType()))
                fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
            proposedParents.put(item.id(), item.parentId());
        }
        if (hasCycle(proposedParents)) fail(HttpStatus.CONFLICT, "navigationCycle");
        for (var item : items) {
            if (repository.reorder(item.id(), item.version(), item.parentId(), item.sortOrder())
                    != 1) {
                fail(HttpStatus.CONFLICT, "staleRecord");
            }
        }
        audit.append(
                actor,
                "navigation.reorder",
                items.isEmpty() ? UUID.randomUUID() : items.get(0).id());
        return repository.list(false);
    }

    /** Checks the complete proposed parent map once, including simultaneous reparenting. */
    static boolean hasCycle(Map<UUID, UUID> parentById) {
        var visited = new HashSet<UUID>();
        for (var start : parentById.keySet()) {
            if (visited.contains(start)) continue;
            var path = new HashSet<UUID>();
            var current = start;
            while (current != null
                    && parentById.containsKey(current)
                    && !visited.contains(current)) {
                if (!path.add(current)) return true;
                current = parentById.get(current);
            }
            visited.addAll(path);
        }
        return false;
    }

    private void validate(UUID id, Input input) {
        if (input == null
                || input.menuCode() == null
                || !input.menuCode().strip().matches("[a-z][a-z0-9._:-]{1,127}")
                || input.labelKey() == null
                || !input.labelKey().strip().matches("[a-z][a-zA-Z0-9._:-]{0,127}")
                || input.labelDefault() == null
                || input.labelDefault().strip().isEmpty()
                || input.labelDefault().strip().length() > 200
                || input.menuType() == null
                || !Set.of("CATALOG", "PAGE", "ACTION").contains(input.menuType())
                || input.sortOrder() != null
                        && (input.sortOrder() < 0 || input.sortOrder() > 1_000_000)
                || input.icon() != null && input.icon().length() > 80
                || input.moduleId() != null
                        && !input.moduleId().strip().matches("[a-z][a-z0-9-]{0,99}")
                || input.sectionKey() != null
                        && !input.sectionKey().strip().matches("[a-z][a-z0-9-]{0,63}")
                || input.componentKey() != null
                        && !input.componentKey().strip().matches("[a-z][a-z0-9._:-]{1,127}")
                || input.permissionKey() != null
                        && !input.permissionKey().strip().matches("[a-z][a-z0-9:._-]{0,119}"))
            fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
        String moduleId = normalize(input.moduleId());
        if (moduleId != null
                && registry.modules().stream().noneMatch(module -> module.id().equals(moduleId)))
            fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
        if ("CATALOG".equals(input.menuType())
                && (normalize(input.path()) != null
                        || normalize(input.sectionKey()) != null
                        || normalize(input.componentKey()) != null))
            fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
        if ("PAGE".equals(input.menuType()) && normalize(input.sectionKey()) == null)
            fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
        if (!"CATALOG".equals(input.menuType())
                && normalize(input.path()) == null
                && normalize(input.sectionKey()) == null)
            fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
        if (input.path() != null
                && (!input.path().strip().matches("/dashboard/[a-z][a-z0-9-]{0,63}")
                        || input.path().length() > 512))
            fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
        if (input.parentId() != null) {
            if (!repository.exists(input.parentId()))
                fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
            var parent = repository.find(input.parentId());
            if (id != null
                    && (input.parentId().equals(id) || repository.cycles(id, input.parentId())))
                fail(HttpStatus.CONFLICT, "navigationCycle");
            if ("ACTION".equals(parent.menuType()))
                fail(HttpStatus.BAD_REQUEST, "invalidNavigation");
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        var normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
