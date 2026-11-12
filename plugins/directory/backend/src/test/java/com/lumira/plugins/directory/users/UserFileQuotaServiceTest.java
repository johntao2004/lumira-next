package com.lumira.plugins.directory.users;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class UserFileQuotaServiceTest {
    @Test
    void deniedTargetCannotReadStorageMetadata() {
        var files = mock(AccountFileStoragePort.class);
        var quotas = mock(FileQuotaPolicyPort.class);
        var defaults = mock(FileQuotaDefaultsPort.class);
        var guard = mock(AdministrationPort.class);
        UUID target = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireUser("actor", target, PlatformPageId.USERS.accessPermission());
        var service =
                new UserFileQuotaService(
                        files, quotas, defaults, guard, mock(RoleAssignmentPreviewPort.class));
        assertThrows(ResponseStatusException.class, () -> service.userQuota("actor", target));
        verify(guard).requirePage("actor", PlatformPageId.USERS);
        verifyNoInteractions(files, quotas, defaults);
    }

    @Test
    void proposedRolesMustBePreviewableBeforeAnyQuotaProjection() {
        var files = mock(AccountFileStoragePort.class);
        var quotas = mock(FileQuotaPolicyPort.class);
        var defaults = mock(FileQuotaDefaultsPort.class);
        var guard = mock(AdministrationPort.class);
        var roles = mock(RoleAssignmentPreviewPort.class);
        UUID target = UUID.randomUUID();
        var proposed = Set.of(UUID.randomUUID());
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRole"))
                .when(roles)
                .requirePreviewable("actor", target, proposed);
        var service = new UserFileQuotaService(files, quotas, defaults, guard, roles);
        assertThrows(
                ResponseStatusException.class,
                () -> service.previewUserQuota("actor", target, proposed));
        verify(guard).requireOperationUser("actor", target, PlatformOperation.USER_ASSIGN_ROLES);
        verifyNoInteractions(files, quotas, defaults);
    }

    @Test
    void previewUsesItsOwningPluginProviderWhenTheKernelAlsoExposesTheContract() {
        var files = mock(AccountFileStoragePort.class);
        var kernelPreview = mock(RoleAssignmentPreviewPort.class);
        var pluginPreview = mock(RoleAssignmentPreviewPort.class);
        UUID target = UUID.randomUUID();
        var proposed = Set.of(UUID.randomUUID());
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRole"))
                .when(pluginPreview)
                .requirePreviewable("actor", target, proposed);
        try (var parent =
                        new org.springframework.context.annotation
                                .AnnotationConfigApplicationContext();
                var plugin =
                        new org.springframework.context.annotation
                                .AnnotationConfigApplicationContext()) {
            parent.registerBean(AccountFileStoragePort.class, () -> files);
            parent.registerBean(FileQuotaPolicyPort.class, () -> mock(FileQuotaPolicyPort.class));
            parent.registerBean(
                    FileQuotaDefaultsPort.class, () -> mock(FileQuotaDefaultsPort.class));
            parent.registerBean(AdministrationPort.class, () -> mock(AdministrationPort.class));
            parent.registerBean(
                    "kernelPreviewAdapter", RoleAssignmentPreviewPort.class, () -> kernelPreview);
            parent.refresh();
            plugin.setParent(parent);
            plugin.registerBean(
                    "roleService", RoleAssignmentPreviewPort.class, () -> pluginPreview);
            plugin.register(UserFileQuotaService.class);
            plugin.refresh();
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            plugin.getBean(UserFileQuotaService.class)
                                    .previewUserQuota("actor", target, proposed));
            verify(pluginPreview).requirePreviewable("actor", target, proposed);
            verifyNoInteractions(kernelPreview, files);
        }
    }

    @Test
    void managementResponsesKeepTheirExistingFieldOrderAndTypes() {
        assertEquals(
                List.of(
                        "userId:java.util.UUID",
                        "username:java.lang.String",
                        "displayName:java.lang.String",
                        "chargedBytes:long",
                        "fileCount:long",
                        "maximumBytes:long",
                        "maximumFiles:long",
                        "defaultMaximumBytes:long",
                        "defaultMaximumFiles:long",
                        "version:long"),
                shape(UserFileQuotaService.UserQuotaView.class));
        assertEquals(
                List.of(
                        "currentMaximumBytes:long",
                        "currentMaximumFiles:long",
                        "proposedMaximumBytes:long",
                        "proposedMaximumFiles:long",
                        "chargedBytes:long",
                        "fileCount:long",
                        "defaultMaximumBytes:long",
                        "defaultMaximumFiles:long",
                        "version:long"),
                shape(UserFileQuotaService.UserQuotaPreview.class));
    }

    private static List<String> shape(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(field -> field.getName() + ":" + field.getType().getName())
                .toList();
    }
}
