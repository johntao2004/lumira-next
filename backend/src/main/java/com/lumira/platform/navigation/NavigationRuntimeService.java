package com.lumira.platform.navigation;

import com.lumira.platform.common.*;
import com.lumira.platform.modules.ModuleCapabilityPort;
import com.lumira.platform.navigation.NavigationTree.RuntimeNode;
import com.lumira.platform.navigation.api.NavigationMenu;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Read-only kernel navigation; independent of the optional administrative plugin. */
@Service
public class NavigationRuntimeService {
    private final JdbcClient jdbc;
    private final ModuleCapabilityPort modules;
    private final AuthorizationContextBoundaryPort authorizationBoundary;
    private final AuthorizationContextProjectionPort authorizationProjection;

    public NavigationRuntimeService(
            JdbcClient jdbc,
            ModuleCapabilityPort modules,
            AuthorizationContextBoundaryPort authorizationBoundary,
            AuthorizationContextProjectionPort authorizationProjection) {
        this.jdbc = jdbc;
        this.modules = modules;
        this.authorizationBoundary = authorizationBoundary;
        this.authorizationProjection = authorizationProjection;
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
        return NavigationTree.assemble(readMenus(), authorities, globalAuthorities, enabledModules);
    }

    private List<NavigationMenu> readMenus() {
        return jdbc.sql(
                        "SELECT id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,component_key,icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version,created_at,updated_at FROM platform_navigation_menu WHERE NOT deleted ORDER BY parent_id NULLS FIRST,sort_order,id")
                .query(NavigationMenu.class)
                .list();
    }
}
