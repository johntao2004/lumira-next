package com.lumira.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class NotificationAdminControllerTest {
    @Test
    void recipientRoutesDelegateTheSharedPageAndIdContracts() {
        NotificationAdminService service = mock(NotificationAdminService.class);
        NotificationAdminController controller = new NotificationAdminController(service);
        Authentication actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("notification-admin");
        var option =
                new NotificationStore.RecipientOption(UUID.randomUUID(), "alice", "Alice", true);
        var page = new PageResult<>(List.of(option), 1L, 2, 20);
        when(service.recipientOptions(eq("notification-admin"), any(PageRequest.class)))
                .thenReturn(page);
        when(service.recipientOption(eq("notification-admin"), eq(option.id()))).thenReturn(option);

        var listed = controller.recipientOptions(actor, 2, 20, "  ALICE  ", Map.of());
        var selected = controller.recipientOption(actor, option.id());

        assertEquals(page, listed);
        assertEquals(option, selected);
        verify(service).recipientOptions("notification-admin", new PageRequest(2, 20, "ALICE"));
        verify(service).recipientOption("notification-admin", option.id());
    }
}
