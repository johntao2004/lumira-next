package com.lumira.plugins.siteconsole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.*;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class SiteSettingWorkflowTest {
    @Test
    void concreteHistoryDetailBindsItsVersionAndUsesItsOwnSection() throws Exception {
        var history = mock(SettingHistoryReader.class);
        var guard = mock(AdministrationPort.class);
        when(history.detail("branding", 2))
                .thenReturn(
                        new SettingHistoryReader.HistoryDetail(
                                2,
                                java.util.Map.of("companyName", "Retained"),
                                "admin",
                                "Publication",
                                OffsetDateTime.now(),
                                null));
        var controller =
                new BrandingSettingHistoryController(
                        history, mock(SiteSettingRollbackService.class), guard);
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                controller)
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/platform/settings/branding/history/2")
                                .principal(
                                        new org.springframework.security.authentication
                                                .UsernamePasswordAuthenticationToken(
                                                "admin", "unused", java.util.List.of())))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.version")
                                .value(2));
        verify(guard).requirePageGlobal("admin", PlatformPageId.BRANDING);
        verify(history).detail("branding", 2);
    }

    @Test
    void agreementRollbackUsesTheSamePublicationLockOrderAsNormalPublication() {
        var store = mock(SettingStore.class);
        var guard = mock(AdministrationPort.class);
        var agreements = mock(AgreementSnapshotService.class);
        var assets = mock(BrandingAssetReferences.class);
        var mapper = new JsonMapper();
        var data = new Agreements("Retained terms", "Retained privacy");
        var payload = mapper.writeValueAsString(data);
        when(store.revision("agreements", 2))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Revision(
                                        2,
                                        payload,
                                        "author",
                                        "Publication",
                                        OffsetDateTime.now(),
                                        null)));
        when(store.save("agreements", payload, "admin", 5, "Restore", 2L))
                .thenReturn(new SettingStore.Snapshot(UUID.randomUUID(), 6, payload));
        var service =
                new SiteSettingRollbackService(
                        store, mapper, guard, mock(MaintenanceService.class), assets, agreements);
        var restored =
                service.rollback(
                        "agreements",
                        new SiteSettingRollbackService.Input(5, 2, "Restore"),
                        "admin");
        assertEquals(6, restored.version());
        assertEquals(data, restored.data());
        var order = inOrder(agreements, guard, store);
        order.verify(agreements).lockForPublication();
        order.verify(guard).requireOperationGlobal("admin", PlatformOperation.AGREEMENTS_UPDATE);
        order.verify(store).revision("agreements", 2);
        order.verify(store).save("agreements", payload, "admin", 5, "Restore", 2L);
        verifyNoInteractions(assets);
    }

    @Test
    void historyRequiresTheOwningGlobalPageBeforeReadingStorage() {
        var history = mock(SettingHistoryReader.class);
        var rollback = mock(SiteSettingRollbackService.class);
        var guard = mock(AdministrationPort.class);
        var actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("scoped");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(guard)
                .requirePageGlobal("scoped", PlatformPageId.BRANDING);
        var controller = new BrandingSettingHistoryController(history, rollback, guard);
        assertThrows(ResponseStatusException.class, () -> controller.history(1, 10, actor));
        verifyNoInteractions(history, rollback);
    }

    @Test
    void siteHistoryCannotExposeAnotherSettingsOwnersDocuments() throws Exception {
        var history = mock(SettingHistoryReader.class);
        var rollback = mock(SiteSettingRollbackService.class);
        var guard = mock(AdministrationPort.class);
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new BrandingSettingHistoryController(history, rollback, guard))
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/platform/settings/smtp/history"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        verifyNoInteractions(history, rollback, guard);
    }
}
