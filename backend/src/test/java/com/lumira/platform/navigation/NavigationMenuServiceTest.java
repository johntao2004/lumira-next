package com.lumira.platform.navigation;

import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavigationMenuServiceTest {
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
}
