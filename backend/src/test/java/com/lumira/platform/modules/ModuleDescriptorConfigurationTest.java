package com.lumira.platform.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.lumira.platform.communication.CommunicationModuleConfiguration;
import com.lumira.platform.events.EventsModuleConfiguration;
import com.lumira.platform.feedback.FeedbackModuleConfiguration;
import com.lumira.platform.files.FilesModuleConfiguration;
import com.lumira.platform.identity.IdentityModuleConfiguration;
import com.lumira.platform.mfa.MfaModuleConfiguration;
import com.lumira.platform.moduleadmin.PlatformModuleDescriptorConfiguration;
import com.lumira.platform.monitoring.MonitoringModuleConfiguration;
import com.lumira.platform.navigation.NavigationModuleConfiguration;
import com.lumira.platform.notifications.NotificationDescriptorTestConfiguration;
import com.lumira.platform.notifications.NotificationsModuleConfiguration;
import com.lumira.platform.sensitive.SensitiveWordsModuleConfiguration;
import com.lumira.platform.settings.SettingsModuleConfiguration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModuleDescriptorConfigurationTest {
    @Test
    void featureConfigurationsContributeDescriptorsToTheKernelRegistry() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        ModuleConfiguration.class,
                        DictionaryDescriptor.class,
                        IdentityModuleConfiguration.class,
                        PlatformModuleDescriptorConfiguration.class,
                        SettingsModuleConfiguration.class,
                        FilesModuleConfiguration.class,
                        EventsModuleConfiguration.class,
                        NavigationModuleConfiguration.class,
                        NotificationsModuleConfiguration.class,
                        NotificationDescriptorTestConfiguration.class,
                        MonitoringModuleConfiguration.class,
                        FeedbackModuleConfiguration.class,
                        SensitiveWordsModuleConfiguration.class,
                        MfaModuleConfiguration.class,
                        CommunicationModuleConfiguration.class)
                .run(
                        context -> {
                            var registry = context.getBean(ModuleRegistry.class);
                            var ids = registry.modules().stream().map(PlatformModule::id).toList();
                            assertEquals(List.of("identity", "platform"), ids.subList(0, 2));
                            assertEquals(
                                    Set.of(
                                            "identity",
                                            "platform",
                                            "communication",
                                            "feedback",
                                            "sensitive-words",
                                            "dictionaries",
                                            "settings",
                                            "files",
                                            "events",
                                            "navigation",
                                            "notifications",
                                            "monitoring",
                                            "mfa"),
                                    Set.copyOf(ids));
                            assertFalse(ids.contains("audit-console"));
                            assertFalse(registry.require("feedback").core());
                            assertFalse(registry.require("mfa").core());
                        });
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class DictionaryDescriptor {
        @org.springframework.context.annotation.Bean
        PlatformModule dictionariesModule() {
            return new PlatformModule(
                    "dictionaries", "字典管理", "1.0.0", List.of("identity", "platform"), false);
        }
    }
}
