package com.lumira.platform.navigation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NavigationMenuServiceTest {
    private static NavigationMenuRepository.Menu page(
            UUID id, UUID parentId, String code, String permission) {
        return new NavigationMenuRepository.Menu(
                id,
                parentId,
                code,
                code,
                code,
                "PAGE",
                code,
                "/dashboard/" + code,
                code,
                null,
                permission,
                false,
                null,
                0,
                true,
                false,
                false,
                1,
                null,
                null);
    }

    @Test
    void detectsCyclesCreatedBySimultaneousReparenting() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        var cycle = new HashMap<UUID, UUID>();
        cycle.put(a, b);
        cycle.put(b, a);
        cycle.put(c, null);

        assertTrue(NavigationMenuService.hasCycle(cycle));
    }

    @Test
    void acceptsAcyclicParentMap() {
        UUID root = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID grandchild = UUID.randomUUID();

        var acyclic = new HashMap<UUID, UUID>();
        acyclic.put(root, null);
        acyclic.put(child, root);
        acyclic.put(grandchild, child);
        assertFalse(NavigationMenuService.hasCycle(acyclic));
    }

    @Test
    void retainsDeniedPageAncestorWithoutMakingItRoutableWhenChildIsAllowed() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        var service = new NavigationMenuService(null, null, null, null, null, null);

        var tree =
                service.tree(
                        java.util.List.of(
                                page(parentId, null, "restricted-parent", "page:parent"),
                                page(childId, parentId, "visible-child", "page:child")),
                        java.util.Set.of("page:child"),
                        java.util.Set.of(),
                        java.util.Set.of());

        assertEquals(1, tree.size());
        assertEquals("restricted-parent", tree.getFirst().sectionKey());
        assertFalse(tree.getFirst().routable());
        assertEquals(1, tree.getFirst().children().size());
        assertEquals("visible-child", tree.getFirst().children().getFirst().sectionKey());
        assertTrue(tree.getFirst().children().getFirst().routable());

        var deniedLeaf =
                service.tree(
                        java.util.List.of(page(parentId, null, "restricted-parent", "page:parent")),
                        java.util.Set.of("page:child"),
                        java.util.Set.of(),
                        java.util.Set.of());
        assertTrue(deniedLeaf.isEmpty());
    }
}
