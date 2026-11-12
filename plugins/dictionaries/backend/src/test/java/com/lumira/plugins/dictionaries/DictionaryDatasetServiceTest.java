package com.lumira.plugins.dictionaries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.dictionaries.api.DictionaryDataset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class DictionaryDatasetServiceTest {
    @Test
    void previewRequiresImportOperationBeforeValidatingDataset() {
        var guard = mock(AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("actor", PlatformOperation.DICTIONARY_IMPORT);
        var dictionaries = mock(DictionaryService.class);
        var items = mock(DictionaryItemRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var audit = mock(AuditPort.class);
        var service = new DictionaryDatasetService(dictionaries, items, jdbc, audit, guard);
        var invalid = dataset(List.of(entry("a", "b"), entry("b", "a")));

        var error =
                assertThrows(
                        ResponseStatusException.class, () -> service.preview(invalid, "actor"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(guard).requireOperationGlobal("actor", PlatformOperation.DICTIONARY_IMPORT);
        verifyNoInteractions(dictionaries, items, jdbc, audit);
    }

    @Test
    void importRequiresImportOperationBeforeOrderingOrWritingDataset() {
        var guard = mock(AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("actor", PlatformOperation.DICTIONARY_IMPORT);
        var dictionaries = mock(DictionaryService.class);
        var items = mock(DictionaryItemRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var audit = mock(AuditPort.class);
        var service = new DictionaryDatasetService(dictionaries, items, jdbc, audit, guard);
        var invalid = dataset(List.of(entry("a", "b"), entry("b", "a")));

        var error =
                assertThrows(
                        ResponseStatusException.class, () -> service.importNew(invalid, "actor"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(guard).requireOperationGlobal("actor", PlatformOperation.DICTIONARY_IMPORT);
        verifyNoInteractions(dictionaries, items, jdbc, audit);
    }

    @Test
    void authorizedPreviewUsesValidatedDatasetSummary() {
        var guard = mock(AdministrationPort.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("test"))).thenReturn(false);
        var service =
                new DictionaryDatasetService(
                        mock(DictionaryService.class),
                        mock(DictionaryItemRepository.class),
                        jdbc,
                        mock(AuditPort.class),
                        guard);

        var preview = service.preview(dataset(List.of(entry("one", null))), "actor");

        assertEquals(new DictionaryDatasetService.Preview("test", "Test", "TREE", 1), preview);
        verify(guard).requireOperationGlobal("actor", PlatformOperation.DICTIONARY_IMPORT);
    }

    @Test
    void exportRequiresIndependentExportOperationBeforeReadingDictionaryData() {
        var guard = mock(AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("actor", PlatformOperation.DICTIONARY_EXPORT);
        var dictionaries = mock(DictionaryService.class);
        var items = mock(DictionaryItemRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var audit = mock(AuditPort.class);
        var service = new DictionaryDatasetService(dictionaries, items, jdbc, audit, guard);

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.export(java.util.UUID.randomUUID(), "actor"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(guard).requireOperationGlobal("actor", PlatformOperation.DICTIONARY_EXPORT);
        verifyNoInteractions(dictionaries, items, jdbc, audit);
    }

    private static DictionaryDataset.Entry entry(String value, String parent) {
        return new DictionaryDataset.Entry(value, value, parent, 0, true, "");
    }

    private static DictionaryDataset dataset(List<DictionaryDataset.Entry> items) {
        return new DictionaryDataset(1, "test", "Test", true, "TREE", "", items);
    }
}
