package com.lumira.platform.dictionaries;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.dictionaries.api.DictionaryDataset;
import java.util.*;
import org.junit.jupiter.api.Test;

class DictionaryDatasetTest {
    DictionaryDataset.Entry entry(String value, String parent) {
        return new DictionaryDataset.Entry(value, value, parent, 0, true, "");
    }

    DictionaryDataset dataset(List<DictionaryDataset.Entry> items) {
        return new DictionaryDataset(1, "test", "Test", true, "TREE", "", items);
    }

    @Test
    void ordersDeepUnsortedInputWithoutRecursion() {
        var rows = new ArrayList<DictionaryDataset.Entry>();
        for (int i = 19999; i >= 0; i--) rows.add(entry("v" + i, i == 0 ? null : "v" + (i - 1)));
        var ordered = dataset(rows).validatedOrder();
        assertEquals(20000, ordered.size());
        assertEquals("v0", ordered.getFirst().value());
        assertEquals("v19999", ordered.getLast().value());
    }

    @Test
    void rejectsCyclesDuplicatesAndMissingParents() {
        for (var rows :
                List.of(
                        List.of(entry("a", "b"), entry("b", "a")),
                        List.of(entry("a", null), entry("a", null)),
                        List.of(entry("a", "missing"))))
            assertThrows(DictionaryDataset.Invalid.class, () -> dataset(rows).validatedOrder());
    }

    @Test
    void rejectsUnsupportedFormatsAndOversize() {
        assertThrows(
                DictionaryDataset.Invalid.class,
                () ->
                        new DictionaryDataset(2, "test", "Test", true, "TREE", "", List.of())
                                .validatedOrder());
        assertTrue(
                assertThrows(
                                DictionaryDataset.Invalid.class,
                                () ->
                                        dataset(Collections.nCopies(20001, entry("a", null)))
                                                .validatedOrder())
                        .tooLarge());
    }
}
