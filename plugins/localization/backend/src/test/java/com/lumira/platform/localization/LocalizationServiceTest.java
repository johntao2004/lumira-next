package com.lumira.platform.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LocalizationServiceTest {
    @Test
    void rejectsUnknownLocaleAndUnsafeKeysBeforePersistence() {
        var repository = mock(LocalizationRepository.class);
        var localeRepository = mock(LocalizationLocaleRepository.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        var enabledLocale =
                new LocalizationLocaleRepository.Language(
                        "en", "English", "en", "en", true, true, 1L, null, null);
        when(localeRepository.listEnabled()).thenReturn(List.of(enabledLocale));
        var service =
                new LocalizationService(
                        repository,
                        localeRepository,
                        mock(LocalizationBuiltinCatalog.class),
                        guard,
                        audit);

        var invalidKey =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.create(
                                        new LocalizationService.Input(
                                                "bad key",
                                                "platform",
                                                "Message",
                                                Map.of("en", "Message"),
                                                true,
                                                null),
                                        "admin"));
        assertEquals("invalidLocalization", invalidKey.getReason());
        verifyNoInteractions(repository);

        var invalidLocale =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.create(
                                        new LocalizationService.Input(
                                                "valid.key",
                                                "platform",
                                                "Message",
                                                Map.of("xx", "Message"),
                                                true,
                                                null),
                                        "admin"));
        assertEquals("localizationLocaleInvalid", invalidLocale.getReason());
        verify(localeRepository).find("xx");
        verifyNoInteractions(repository, audit);
    }

    @Test
    void buildsBundleFromLocaleMessagesAndSourceFallbacks() {
        var repository = mock(LocalizationRepository.class);
        when(repository.releaseVersion("zh-CN")).thenReturn(4L);
        when(repository.allActive()).thenReturn(List.of());

        var localeRepository = mock(LocalizationLocaleRepository.class);
        var zh =
                new LocalizationLocaleRepository.Language(
                        "zh-CN", "简体中文", "en", "en", true, true, 1L, null, null);
        var en =
                new LocalizationLocaleRepository.Language(
                        "en", "English", "en", "en", true, true, 1L, null, null);
        when(localeRepository.find("zh-CN")).thenReturn(Optional.of(zh));
        when(localeRepository.find("en")).thenReturn(Optional.of(en));

        var builtins = mock(LocalizationBuiltinCatalog.class);
        when(builtins.sourceLocale()).thenReturn("en");
        when(builtins.keys()).thenReturn(Set.of("hello", "saved"));
        when(builtins.sourceText("hello")).thenReturn("Hello");
        when(builtins.sourceText("saved")).thenReturn("Saved");
        when(builtins.messages("zh-CN")).thenReturn(Map.of("hello", "你好"));
        when(builtins.messages("en")).thenReturn(Map.of("hello", "Hello", "saved", "Saved"));

        var service =
                new LocalizationService(
                        repository,
                        localeRepository,
                        builtins,
                        mock(AdministrationPort.class),
                        mock(AuditPort.class));

        var bundle = service.bundle("zh-CN");

        assertEquals(4L, bundle.releaseVersion());
        assertTrue(bundle.snapshotToken().matches("[a-f0-9]{64}"));
        assertEquals("你好", bundle.messages().get("hello"));
        assertEquals("Saved", bundle.messages().get("saved"));
        verify(repository).allActive();
        verify(repository).releaseVersion("zh-CN");
    }

    @Test
    void sourceSnapshotTokenChangesWhenEffectiveMessagesChangeWithoutPublish() {
        var repository = mock(LocalizationRepository.class);
        var sourceA = entry("custom.ai.source", "custom", "Source A", Map.of(), true, 1);
        var sourceB = entry("custom.ai.source", "custom", "Source B", Map.of(), true, 2);
        when(repository.allActive()).thenReturn(List.of(sourceA), List.of(sourceB));
        when(repository.releaseVersion("en")).thenReturn(7L);

        var localeRepository = mock(LocalizationLocaleRepository.class);
        var en =
                new LocalizationLocaleRepository.Language(
                        "en", "English", "en", "en", true, true, 1L, null, null);
        when(localeRepository.find("en")).thenReturn(Optional.of(en));

        var builtins = mock(LocalizationBuiltinCatalog.class);
        when(builtins.sourceLocale()).thenReturn("en");
        when(builtins.keys()).thenReturn(Set.of());

        var service =
                new LocalizationService(
                        repository,
                        localeRepository,
                        builtins,
                        mock(AdministrationPort.class),
                        mock(AuditPort.class));

        var before = service.bundle("en");
        var after = service.bundle("en");

        assertEquals(before.releaseVersion(), after.releaseVersion());
        assertNotEquals(
                before.messages().get("custom.ai.source"),
                after.messages().get("custom.ai.source"));
        assertNotEquals(before.snapshotToken(), after.snapshotToken());
    }

    @Test
    void softDeletesOnlyTheLastCanonicalBuiltinLocaleOverlay() {
        var row =
                entry(
                        "platform.sample",
                        "platform",
                        "Canonical source",
                        Map.of("zh-CN", "临时译文"),
                        true,
                        4);
        var fixture = restoreFixture(row, true);
        when(fixture.repository().softDelete(row.id(), row.version(), "admin")).thenReturn(1);

        fixture.service().restoreTranslation("zh-CN", row.messageKey(), row.version(), "admin");

        verify(fixture.repository()).softDelete(row.id(), row.version(), "admin");
        verify(fixture.repository(), never())
                .removeTranslation(any(UUID.class), anyLong(), anyString(), anyString());
        verify(fixture.repository()).publish("zh-CN", "admin");
        verify(fixture.audit()).append("admin", "localization.translation.restored", row.id());
        verify(fixture.guard()).require("admin", LocalizationService.WRITE);
        verify(fixture.guard()).requireGlobal("admin", LocalizationService.WRITE);
    }

    @Test
    void removesOnlyTheSelectedLocaleWhenOtherBuiltinTranslationsRemain() {
        var row =
                entry(
                        "platform.sample",
                        "platform",
                        "Canonical source",
                        Map.of("zh-CN", "临时译文", "en", "English override"),
                        true,
                        7);
        var fixture = restoreFixture(row, true);
        when(fixture.repository().removeTranslation(row.id(), row.version(), "zh-CN", "admin"))
                .thenReturn(1);

        fixture.service().restoreTranslation("zh-CN", row.messageKey(), row.version(), "admin");

        verify(fixture.repository()).removeTranslation(row.id(), row.version(), "zh-CN", "admin");
        verify(fixture.repository(), never()).softDelete(any(UUID.class), anyLong(), anyString());
        verify(fixture.repository()).publish("zh-CN", "admin");
        verify(fixture.audit()).append("admin", "localization.translation.restored", row.id());
    }

    @Test
    void keepsBuiltinLikeRowsActiveWhenAnyCanonicalMetadataDiffers() {
        var rows =
                List.of(
                        entry(
                                "platform.sample",
                                "custom",
                                "Canonical source",
                                Map.of("zh-CN", "临时译文"),
                                true,
                                3),
                        entry(
                                "platform.sample",
                                "platform",
                                "Edited default",
                                Map.of("zh-CN", "临时译文"),
                                true,
                                5),
                        entry(
                                "platform.sample",
                                "platform",
                                "Canonical source",
                                Map.of("zh-CN", "临时译文"),
                                false,
                                8));

        for (var row : rows) {
            var fixture = restoreFixture(row, true);
            when(fixture.repository().removeTranslation(row.id(), row.version(), "zh-CN", "admin"))
                    .thenReturn(1);

            fixture.service().restoreTranslation("zh-CN", row.messageKey(), row.version(), "admin");

            verify(fixture.repository())
                    .removeTranslation(row.id(), row.version(), "zh-CN", "admin");
            verify(fixture.repository(), never())
                    .softDelete(any(UUID.class), anyLong(), anyString());
            verify(fixture.repository()).publish("zh-CN", "admin");
            verify(fixture.audit()).append("admin", "localization.translation.restored", row.id());
        }
    }

    @Test
    void preservesCustomDefaultRowsAfterTheirLastLocaleTranslationIsCleared() {
        var row =
                entry(
                        "custom.sample",
                        "custom",
                        "Custom default",
                        Map.of("zh-CN", "临时译文"),
                        true,
                        2);
        var fixture = restoreFixture(row, false);
        when(fixture.repository().removeTranslation(row.id(), row.version(), "zh-CN", "admin"))
                .thenReturn(1);

        fixture.service().restoreTranslation("zh-CN", row.messageKey(), row.version(), "admin");

        verify(fixture.repository()).removeTranslation(row.id(), row.version(), "zh-CN", "admin");
        verify(fixture.repository(), never()).softDelete(any(UUID.class), anyLong(), anyString());
        verify(fixture.repository()).publish("zh-CN", "admin");
        verify(fixture.audit()).append("admin", "localization.translation.restored", row.id());
    }

    @Test
    void staleVersionDoesNotMutatePublishOrAudit() {
        var row =
                entry(
                        "platform.sample",
                        "platform",
                        "Canonical source",
                        Map.of("zh-CN", "临时译文"),
                        true,
                        9);
        var fixture = restoreFixture(row, true);

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                fixture.service()
                                        .restoreTranslation(
                                                "zh-CN",
                                                row.messageKey(),
                                                row.version() - 1,
                                                "admin"));

        assertEquals("staleRecord", error.getReason());
        verifyNoRestoreEffects(fixture);
    }

    @Test
    void compareAndSetRaceDoesNotPublishOrAudit() {
        var row =
                entry(
                        "platform.sample",
                        "platform",
                        "Canonical source",
                        Map.of("zh-CN", "临时译文"),
                        true,
                        9);
        var fixture = restoreFixture(row, true);
        when(fixture.repository().softDelete(row.id(), row.version(), "admin")).thenReturn(0);

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                fixture.service()
                                        .restoreTranslation(
                                                "zh-CN", row.messageKey(), row.version(), "admin"));

        assertEquals("staleRecord", error.getReason());
        verify(fixture.repository()).softDelete(row.id(), row.version(), "admin");
        verify(fixture.repository(), never())
                .removeTranslation(any(UUID.class), anyLong(), anyString(), anyString());
        verify(fixture.repository(), never()).publish(anyString(), anyString());
        verifyNoInteractions(fixture.audit());
    }

    private static LocalizationRepository.Entry entry(
            String key,
            String namespace,
            String defaultMessage,
            Map<String, String> translations,
            boolean enabled,
            long version) {
        return new LocalizationRepository.Entry(
                UUID.randomUUID(),
                key,
                namespace,
                defaultMessage,
                translations,
                enabled,
                version,
                "admin",
                "admin",
                null,
                null);
    }

    private static RestoreFixture restoreFixture(
            LocalizationRepository.Entry row, boolean builtin) {
        var repository = mock(LocalizationRepository.class);
        var localeRepository = mock(LocalizationLocaleRepository.class);
        var builtins = mock(LocalizationBuiltinCatalog.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        var locale =
                new LocalizationLocaleRepository.Language(
                        "zh-CN", "简体中文", "en", "en", true, true, 1L, null, null);
        when(localeRepository.find("zh-CN")).thenReturn(Optional.of(locale));
        when(repository.findByKey(row.messageKey())).thenReturn(Optional.of(row));
        when(builtins.hasKey(row.messageKey())).thenReturn(builtin);
        if (builtin) when(builtins.sourceText(row.messageKey())).thenReturn("Canonical source");
        return new RestoreFixture(
                new LocalizationService(repository, localeRepository, builtins, guard, audit),
                repository,
                guard,
                audit);
    }

    private static void verifyNoRestoreEffects(RestoreFixture fixture) {
        verify(fixture.repository(), never()).softDelete(any(UUID.class), anyLong(), anyString());
        verify(fixture.repository(), never())
                .removeTranslation(any(UUID.class), anyLong(), anyString(), anyString());
        verify(fixture.repository(), never()).publish(anyString(), anyString());
        verifyNoInteractions(fixture.audit());
    }

    private record RestoreFixture(
            LocalizationService service,
            LocalizationRepository repository,
            AdministrationPort guard,
            AuditPort audit) {}
}
