package com.lumira.platform.navigation;

import com.lumira.platform.navigation.api.NavigationMenu;
import java.util.*;

/** Kernel menu projection: iterative traversal keeps deep and corrupt trees bounded. */
public final class NavigationTree {
    private NavigationTree() {}

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

    private static final Comparator<NavigationMenu> MENU_ORDER =
            Comparator.comparingInt(NavigationMenu::sortOrder).thenComparing(NavigationMenu::id);

    private static final class BuildFrame {
        private final NavigationMenu node;
        private final List<NavigationMenu> rows;
        private final List<RuntimeNode> included = new ArrayList<>();
        private int next;

        private BuildFrame(NavigationMenu node, List<NavigationMenu> rows) {
            this.node = node;
            this.rows = rows;
        }
    }

    public static List<RuntimeNode> assemble(
            List<NavigationMenu> rows,
            Set<String> authorities,
            Set<String> globalAuthorities,
            Set<String> enabledModules) {
        var children = new HashMap<UUID, List<NavigationMenu>>();
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
}
