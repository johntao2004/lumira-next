package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.PageRequest;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FileRepositoryPaginationTest extends com.lumira.testfixtures.FileQueryFixture {
    private FileRepository files;

    @BeforeEach
    void repository() {
        files = new FileRepository(client);
    }

    @Test
    void ownerScopeOrderingAndCountsSurviveSharedPaging() {
        var first = files.listOwned(alice, new PageRequest(1, 1, ""));
        assertEquals(2, first.total());
        assertEquals("new.txt", first.items().getFirst().originalName());
        var second = files.listOwned(alice, new PageRequest(2, 1, ""));
        assertEquals(2, second.total());
        assertEquals(2, second.page());
        assertEquals("old.txt", second.items().getFirst().originalName());
        assertEquals(0, files.listOwned(alice, new PageRequest(1, 10, "bob")).total());
        var filtered =
                files.listOwned(
                        alice,
                        new PageRequest(
                                1,
                                10,
                                "",
                                Map.of("originalName", "new.txt"),
                                Map.of("originalName", "exact")));
        assertEquals(1, filtered.total());
        assertEquals("new.txt", filtered.items().getFirst().originalName());
    }

    @Test
    void ownerTrashScopeIsIndependentOfGlobalManagement() {
        var trash = files.listTrashOwned(alice, new PageRequest(1, 10, ""));
        assertEquals(1, trash.total());
        assertEquals("alice-trash.txt", trash.items().getFirst().originalName());
    }
}
