package com.lumira.platform.modules;

import com.lumira.platform.audit.AuditModuleConfiguration;
import com.lumira.platform.communication.CommunicationModuleConfiguration;
import com.lumira.platform.dictionaries.DictionariesModuleConfiguration;
import com.lumira.platform.directory.DirectoryModuleConfiguration;
import com.lumira.platform.events.EventsModuleConfiguration;
import com.lumira.platform.feedback.FeedbackModuleConfiguration;
import com.lumira.platform.files.FilesModuleConfiguration;
import com.lumira.platform.identity.IdentityModuleConfiguration;
import com.lumira.platform.localization.LocalizationModuleConfiguration;
import com.lumira.platform.moduleadmin.PlatformModuleDescriptorConfiguration;
import com.lumira.platform.monitoring.MonitoringModuleConfiguration;
import com.lumira.platform.navigation.NavigationModuleConfiguration;
import com.lumira.platform.notifications.NotificationsModuleConfiguration;
import com.lumira.platform.sensitive.SensitiveWordsModuleConfiguration;
import com.lumira.platform.settings.SettingsModuleConfiguration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleDescriptorConfigurationTest {
    @Test
    void featureConfigurationsContributeDescriptorsToTheKernelRegistry() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        ModuleConfiguration.class,
                        IdentityModuleConfiguration.class,
                        PlatformModuleDescriptorConfiguration.class,
                        DirectoryModuleConfiguration.class,
                        DictionariesModuleConfiguration.class,
                        SettingsModuleConfiguration.class,
                        FilesModuleConfiguration.class,
                        EventsModuleConfiguration.class,
                        NavigationModuleConfiguration.class,
                        NotificationsModuleConfiguration.class,
                        MonitoringModuleConfiguration.class,
                        AuditModuleConfiguration.class,
                        FeedbackModuleConfiguration.class,
                        SensitiveWordsModuleConfiguration.class,
                        LocalizationModuleConfiguration.class,
                        CommunicationModuleConfiguration.class)
                .run(context -> {
                    var registry = context.getBean(ModuleRegistry.class);
                    var ids = registry.modules().stream().map(PlatformModule::id).toList();
                    assertEquals(List.of("identity", "platform"), ids.subList(0, 2));
                    assertEquals(
                            Set.of("identity", "platform", "communication", "audit-console", "feedback", "sensitive-words", "localization",
                                    "directory", "dictionaries", "settings", "files", "events", "navigation", "notifications", "monitoring"),
                            Set.copyOf(ids));
                    assertTrue(registry.require("audit-console").core());
                    assertFalse(registry.require("feedback").core());
                });
    }
}
