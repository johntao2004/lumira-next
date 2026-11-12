package com.lumira.plugins.auditconsole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditQueryPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PlatformPageId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditServiceTest {
    @Test
    void deniedGlobalScopeNeverReadsAuditStorage() {
        var storage = mock(AuditQueryPort.class);
        var guard = mock(AdministrationPort.class);
        doThrow(new IllegalStateException("forbidden"))
                .when(guard)
                .requirePageGlobal("scoped-user", PlatformPageId.AUDIT);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new AuditService(storage, guard)
                                .list(new PageRequest(1, 20, ""), "scoped-user"));
        verifyNoInteractions(storage);
    }

    @Test
    void queryUsesTheKernelPortAndPreservesPaginationAndRecordIdentity() {
        var storage = mock(AuditQueryPort.class);
        var guard = mock(AdministrationPort.class);
        var request = new PageRequest(2, 20, "retained-event");
        var event =
                new AuditQueryPort.Event(
                        UUID.randomUUID(),
                        "admin",
                        "retained-event",
                        UUID.randomUUID(),
                        OffsetDateTime.now());
        when(storage.queryEvents(request)).thenReturn(List.of(event));
        when(storage.countEvents(request)).thenReturn(21L);
        var result = new AuditService(storage, guard).list(request, "admin");
        assertEquals(List.of(event), result.items());
        assertEquals(21, result.total());
        assertEquals(2, result.page());
        assertEquals(20, result.pageSize());
        var order = inOrder(guard, storage);
        order.verify(guard).requirePageGlobal("admin", PlatformPageId.AUDIT);
        order.verify(storage).queryEvents(request);
        order.verify(storage).countEvents(request);
        verifyNoMoreInteractions(storage);
    }
}
