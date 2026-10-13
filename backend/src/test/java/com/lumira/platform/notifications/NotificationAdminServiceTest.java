package com.lumira.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DurableEventPublisherPort;
import com.lumira.platform.common.PageRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class NotificationAdminServiceTest {
    private final NotificationAdminService service =
            new NotificationAdminService(
                    mock(NotificationStore.class),
                    mock(JdbcClient.class),
                    mock(AdministrationPort.class),
                    mock(AuditPort.class),
                    new JsonMapper(),
                    mock(DurableEventPublisherPort.class));

    @Test
    void roleScopeRequiresAtLeastOneRecipientRole() {
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.publish("admin", rolePublish(List.of())));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("notificationNoRecipients", error.getReason());
    }

    @Test
    void roleScopeRejectsDuplicateAndExcessiveRoleIdsBeforeLookup() {
        UUID roleId = UUID.randomUUID();
        var duplicate =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.publish("admin", rolePublish(List.of(roleId, roleId))));
        assertEquals(HttpStatus.BAD_REQUEST, duplicate.getStatusCode());
        assertEquals("notificationInvalid", duplicate.getReason());

        List<UUID> tooMany =
                java.util.stream.IntStream.range(0, 10_001)
                        .mapToObj(ignored -> UUID.randomUUID())
                        .toList();
        var excessive =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.publish("admin", rolePublish(tooMany)));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, excessive.getStatusCode());
        assertEquals("notificationTooManyRecipients", excessive.getReason());
    }

    @Test
    void mapsInvalidNotificationLinksToTheExistingApiError() {
        for (String href : List.of("/dashboard\r\n/home", "/" + "a".repeat(512))) {
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    service.publish(
                                            "admin",
                                            rolePublish(List.of(UUID.randomUUID()), href)));

            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
            assertEquals("notificationInvalid", error.getReason());
        }
    }

    @Test
    void recipientLookupRequiresGlobalNotificationWriteBeforeReadingTheStore() {
        NotificationStore store = mock(NotificationStore.class);
        List<String> permissionChecks = new ArrayList<>();
        AdministrationPort guard =
                new AdministrationPort() {
                    @Override
                    public Set<String> require(String actor, String permission) {
                        permissionChecks.add("require:" + permission);
                        return Set.of(permission);
                    }

                    @Override
                    public void requireUser(String actor, UUID target, String permission) {}

                    @Override
                    public void requireGlobal(String actor, String permission) {
                        permissionChecks.add("global:" + permission);
                        if (NotificationAdminService.CREATE.equals(permission))
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
                    }

                    @Override
                    public boolean hasGlobal(String actor, String permission) {
                        return false;
                    }
                };
        var protectedService =
                new NotificationAdminService(
                        store,
                        mock(JdbcClient.class),
                        guard,
                        mock(AuditPort.class),
                        new JsonMapper(),
                        mock(DurableEventPublisherPort.class));

        var listError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                protectedService.recipientOptions(
                                        "writer", new PageRequest(1, 20, "alice")));
        var detailError =
                assertThrows(
                        ResponseStatusException.class,
                        () -> protectedService.recipientOption("writer", UUID.randomUUID()));

        assertEquals(HttpStatus.FORBIDDEN, listError.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, detailError.getStatusCode());
        assertEquals(
                List.of(
                        "require:" + NotificationAdminService.READ,
                        "global:" + NotificationAdminService.READ,
                        "require:" + NotificationAdminService.CREATE,
                        "global:" + NotificationAdminService.CREATE,
                        "require:" + NotificationAdminService.READ,
                        "global:" + NotificationAdminService.READ,
                        "require:" + NotificationAdminService.CREATE,
                        "global:" + NotificationAdminService.CREATE),
                permissionChecks);
        verify(store, never())
                .listRecipientOptions(org.mockito.ArgumentMatchers.any(PageRequest.class));
        verify(store, never()).recipientOption(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    private static NotificationAdminService.Publish rolePublish(List<UUID> roles) {
        return rolePublish(roles, null);
    }

    private static NotificationAdminService.Publish rolePublish(List<UUID> roles, String href) {
        return new NotificationAdminService.Publish(
                "ROLES", null, roles, "Title", "Body", href, null);
    }
}
