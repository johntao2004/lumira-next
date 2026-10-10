package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.GeneratedAvatarPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class AvatarServiceTest {
    private static final UUID OWNER = UUID.fromString("5d92808d-7666-4b6e-a257-dd0a2b94d4a0");
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01};

    private final AvatarRepository avatars = mock(AvatarRepository.class);
    private final AccountOwnerPort accounts = mock(AccountOwnerPort.class);
    private final ObjectProvider<PluginExtensionsPort> plugins = mock(ObjectProvider.class);

    private AvatarService service;

    @BeforeEach
    void setUp() {
        service =
                new AvatarService(
                        avatars,
                        accounts,
                        mock(FileRepository.class),
                        mock(FileService.class),
                        mock(AuditPort.class),
                        mock(ProfileSchemaPort.class),
                        mock(JdbcClient.class),
                        plugins);
        when(accounts.id("alice", false)).thenReturn(OWNER);
    }

    @Test
    void persistedUploadTakesPriorityOverTheGenerator() {
        UUID fileId = UUID.fromString("3216c64a-e5f2-4c4f-bd58-12ad6fcc5fa0");
        when(avatars.find(OWNER, false)).thenReturn(new AvatarRepository.Avatar(fileId, 7));

        assertEquals(new AvatarService.AvatarView(fileId, 7, false), service.find("alice"));
        verifyNoInteractions(plugins);
    }

    @Test
    void disabledGeneratorLeavesThePersistedDefaultAndVersionUntouched() {
        var runtime = mock(PluginExtensionsPort.class);
        when(plugins.getIfAvailable()).thenReturn(runtime);
        when(avatars.find(OWNER, false)).thenReturn(new AvatarRepository.Avatar(null, 3));
        when(runtime.extensions(eq(GeneratedAvatarPort.class), any())).thenReturn(List.of());

        assertEquals(new AvatarService.AvatarView(null, 3, false), service.find("alice"));
        verify(avatars, never()).update(any(), any());
    }

    @Test
    void generatedContentUsesTheAccountUuidAndRejectsStaleVersionsBeforeCallingThePlugin() {
        when(avatars.find(OWNER, false)).thenReturn(new AvatarRepository.Avatar(null, 4));
        AtomicReference<UUID> generatedFor = new AtomicReference<>();
        GeneratedAvatarPort generator =
                accountId -> {
                    generatedFor.set(accountId);
                    return PNG;
                };
        var runtime = mock(PluginExtensionsPort.class);
        when(plugins.getIfAvailable()).thenReturn(runtime);
        when(runtime.extensions(eq(GeneratedAvatarPort.class), any()))
                .thenAnswer(
                        invocation -> {
                            Function<GeneratedAvatarPort, ?> call = invocation.getArgument(1);
                            return List.of(call.apply(generator));
                        });

        assertArrayEquals(PNG, service.generatedImage("alice", 4));
        assertEquals(OWNER, generatedFor.get());
        verify(avatars, never()).update(any(), any());

        assertEquals(
                404,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.generatedImage("alice", 3))
                        .getStatusCode()
                        .value());
        verify(runtime, times(1)).extensions(eq(GeneratedAvatarPort.class), any());
    }
}
