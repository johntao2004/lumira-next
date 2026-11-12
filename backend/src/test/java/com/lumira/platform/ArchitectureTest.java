package com.lumira.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Freezes the module dependency graph. A new edge between feature modules is an architectural
 * decision: it fails here until the allow-list below is edited on purpose, in review.
 *
 * <p>{@code common} and {@code modules} form the shared kernel; feature modules may use them but
 * the kernel must never reach back into a feature module.
 */
class ArchitectureTest {
    private static final String ROOT = "com.lumira.platform";

    /**
     * module -> feature modules it is allowed to depend on (kernel packages are always allowed).
     */
    private static final Map<String, Set<String>> ALLOWED =
            Map.ofEntries(
                    Map.entry("ai", Set.of()),
                    Map.entry("audit", Set.of()),
                    Map.entry("communication", Set.of()),
                    Map.entry("content", Set.of()),
                    Map.entry("dictionaries", Set.of("identity")),
                    // Compatibility bridge uses only shared leased service contracts.
                    Map.entry("directory", Set.of()),
                    Map.entry("events", Set.of()),
                    Map.entry(
                            "feedback",
                            Set.of("content", "dictionaries", "identity", "notifications")),
                    Map.entry("files", Set.of("identity", "settings")),
                    Map.entry("identity", Set.of()),
                    Map.entry("localization", Set.of()),
                    Map.entry("mfa", Set.of("identity")),
                    Map.entry("mail", Set.of("communication")),
                    Map.entry("moduleadmin", Set.of("plugins")),
                    Map.entry("plugins", Set.of()),
                    Map.entry("monitoring", Set.of()),
                    Map.entry("navigation", Set.of()),
                    Map.entry("notifications", Set.of()),
                    Map.entry("sensitive", Set.of("dictionaries")),
                    Map.entry("settings", Set.of("dictionaries", "identity")),
                    Map.entry("sms", Set.of("communication")));

    private static final Set<String> KERNEL = Set.of("common", "modules");
    private static JavaClasses production;

    @BeforeAll
    static void importProduction() {
        var paths = new java.util.ArrayList<java.nio.file.Path>();
        paths.add(java.nio.file.Path.of("target/classes"));
        paths.add(java.nio.file.Path.of("target/test-classes/com/lumira/plugins/personalfiles"));
        paths.add(java.nio.file.Path.of("target/test-classes/com/lumira/plugins/directory"));
        paths.add(java.nio.file.Path.of("target/test-classes/com/lumira/plugins/fileretention"));
        paths.add(java.nio.file.Path.of("target/test-classes/com/lumira/plugins/fileconsole"));
        paths.add(java.nio.file.Path.of("target/test-classes/com/lumira/plugins/siteconsole"));
        paths.add(java.nio.file.Path.of("target/test-classes/com/lumira/plugins/deliverysettings"));
        paths.add(java.nio.file.Path.of("target/test-classes/com/lumira/plugins/profileconsole"));
        paths.add(
                java.nio.file.Path.of(
                        "target/test-classes/com/lumira/plugins/authenticationconsole"));
        for (String id :
                java.util.List.of("ai", "feedback", "localization", "sensitive-words", "mfa")) {
            // Test compilation includes plugin implementations without packaging them in the host.
            paths.add(
                    java.nio.file.Path.of(
                            "target/test-classes/com/lumira/platform/"
                                    + (id.equals("sensitive-words") ? "sensitive" : id)));
        }
        production =
                new ClassFileImporter()
                        .withImportOption(location -> !location.toString().contains("Test.class"))
                        .importPaths(paths);
    }

    @Test
    void everyModuleIsDeclaredInTheAllowList() {
        Set<String> found = new TreeSet<>();
        production.forEach(
                c -> {
                    String pkg = c.getPackageName();
                    if (pkg.startsWith(ROOT + ".")) {
                        found.add(pkg.substring(ROOT.length() + 1).split("\\.")[0]);
                    }
                });
        Set<String> known = new TreeSet<>(ALLOWED.keySet());
        known.addAll(KERNEL);
        known.add("web");
        Set<String> undeclared = new TreeSet<>(found);
        undeclared.removeAll(known);
        if (!undeclared.isEmpty()) {
            throw new AssertionError(
                    "New module package(s) "
                            + undeclared
                            + " must be added to ArchitectureTest.ALLOWED");
        }
    }

    @Test
    void featureModulesOnlyDependOnDeclaredNeighbours() {
        Set<String> everyone = new TreeSet<>(ALLOWED.keySet());
        for (var entry : ALLOWED.entrySet()) {
            Set<String> forbidden = new TreeSet<>(everyone);
            forbidden.remove(entry.getKey());
            forbidden.removeAll(entry.getValue());
            noClasses()
                    .that()
                    .resideInAPackage(ROOT + "." + entry.getKey() + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            forbidden.stream()
                                    .map(m -> ROOT + "." + m + "..")
                                    .toArray(String[]::new))
                    .because(entry.getKey() + " may only depend on " + entry.getValue())
                    .check(production);
        }
    }

    @Test
    void directoryPluginUsesSharedContractsAndHostNeverDependsOnItsImplementations() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.lumira.plugins.directory..")
                .check(production);
        var pluginClasses =
                production.stream()
                        .filter(
                                type ->
                                        type.getPackageName()
                                                .startsWith("com.lumira.plugins.directory"))
                        .toList();
        assertTrue(!pluginClasses.isEmpty(), "Import actual directory plugin production classes");
        var forbidden = new TreeSet<String>();
        for (var source : pluginClasses) {
            for (var dependency : source.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getPackageName();
                if (target.startsWith(ROOT + ".")
                        && !target.equals(ROOT + ".common")
                        && !target.startsWith(ROOT + ".common.")
                        && !target.equals(ROOT + ".modules")
                        && !target.startsWith(ROOT + ".modules.")
                        && !target.equals(ROOT + ".identity.api")
                        && !target.startsWith(ROOT + ".identity.api."))
                    forbidden.add(
                            source.getName() + " -> " + dependency.getTargetClass().getName());
            }
        }
        assertTrue(
                forbidden.isEmpty(), () -> "Directory may only use shared contracts: " + forbidden);
    }

    @Test
    void feedbackDependsOnlyOnIdentityPublicApi() {
        Set<String> forbidden = new TreeSet<>();
        for (var source : production) {
            String sourcePackage = source.getPackageName();
            if (!sourcePackage.equals(ROOT + ".feedback")
                    && !sourcePackage.startsWith(ROOT + ".feedback.")) continue;
            for (var dependency : source.getDirectDependenciesFromSelf()) {
                var target = dependency.getTargetClass();
                String targetPackage = target.getPackageName();
                boolean identityPackage =
                        targetPackage.equals(ROOT + ".identity")
                                || targetPackage.startsWith(ROOT + ".identity.");
                if (identityPackage
                        && !targetPackage.equals(ROOT + ".identity.api")
                        && !targetPackage.startsWith(ROOT + ".identity.api.")) {
                    forbidden.add(source.getName() + " -> " + target.getName());
                }
            }
        }
        assertTrue(
                forbidden.isEmpty(),
                () -> "Feedback may only depend on identity.api contracts: " + forbidden);
    }

    @Test
    void kernelNeverReachesIntoFeatureModules() {
        String[] features =
                ALLOWED.keySet().stream().map(m -> ROOT + "." + m + "..").toArray(String[]::new);
        noClasses()
                .that()
                .resideInAnyPackage(ROOT + ".common..", ROOT + ".modules..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(features)
                .check(production);
    }

    @Test
    void controllersDoNotExposeRepositoryRecordsInResponseSignatures() {
        for (var controller : production) {
            if (!controller.isAnnotatedWith(
                    org.springframework.web.bind.annotation.RestController.class)) continue;
            for (var method : controller.getMethods()) {
                for (var type : method.getReturnType().getAllInvolvedRawTypes()) {
                    if (type.getName().contains("Repository$")) {
                        throw new AssertionError(
                                method.getFullName() + " exposes " + type.getName());
                    }
                }
            }
        }
    }

    @Test
    void modulesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(production);
    }

    @Test
    void fileRetentionAdministrationUsesOnlySharedKernelAndSettingsStorage() {
        noClasses()
                .that()
                .resideInAPackage("com.lumira.plugins.fileretention..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.lumira.platform.files..",
                        "com.lumira.platform.identity..",
                        "com.lumira.plugins.directory..")
                .check(production);
        noClasses()
                .that()
                .resideInAPackage("com.lumira.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.lumira.plugins.fileretention..")
                .check(production);
    }

    @Test
    void fileConsoleDependsOnlyOnStorageContractsAndSettingsFoundation() {
        noClasses()
                .that()
                .resideInAPackage("com.lumira.plugins.fileconsole..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.lumira.platform.files..",
                        "com.lumira.platform.identity..",
                        "com.lumira.plugins.directory..")
                .check(production);
        noClasses()
                .that()
                .resideInAPackage("com.lumira.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.lumira.plugins.fileconsole..")
                .check(production);
    }

    @Test
    void siteConsoleDependsOnlyOnSharedAndSettingsFoundation() {
        noClasses()
                .that()
                .resideInAPackage("com.lumira.plugins.siteconsole..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.lumira.platform.files..",
                        "com.lumira.platform.identity..",
                        "com.lumira.plugins.directory..")
                .check(production);
        noClasses()
                .that()
                .resideInAPackage("com.lumira.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.lumira.plugins.siteconsole..")
                .check(production);
    }

    @Test
    void deliverySettingsHasNoPrivateTransportOrBusinessDependencies() {
        noClasses()
                .that()
                .resideInAPackage("com.lumira.plugins.deliverysettings..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.lumira.platform.mail..",
                        "com.lumira.platform.sms..",
                        "com.lumira.platform.files..",
                        "com.lumira.platform.identity..",
                        "com.lumira.plugins.directory..")
                .check(production);
        noClasses()
                .that()
                .resideInAPackage("com.lumira.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.lumira.plugins.deliverysettings..")
                .check(production);
    }

    @Test
    void profileConsoleDependsOnlyOnProfileContractsAndStorage() {
        noClasses()
                .that()
                .resideInAPackage("com.lumira.plugins.profileconsole..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.lumira.platform.files..",
                        "com.lumira.platform.identity.ProfileRepository",
                        "com.lumira.plugins.directory..")
                .check(production);
        noClasses()
                .that()
                .resideInAPackage("com.lumira.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.lumira.plugins.profileconsole..")
                .check(production);
    }

    @Test
    void authenticationConsoleDependsOnlyOnKernelContractsAndStorage() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                .that()
                .resideInAPackage("com.lumira.plugins.authenticationconsole..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "org..",
                        "tools..",
                        "com.fasterxml.jackson..",
                        "com.lumira.plugin.sdk..",
                        "com.lumira.plugins.authenticationconsole..",
                        "com.lumira.platform.common..",
                        "com.lumira.platform.settings..",
                        "com.lumira.platform.identity.api..",
                        "com.lumira.platform.modules..")
                .check(production);
        noClasses()
                .that()
                .resideInAPackage("com.lumira.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.lumira.plugins.authenticationconsole..")
                .check(production);
    }

    @Test
    void personalFilesDependsOnlyOnKernelContractsAndStorage() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                .that()
                .resideInAPackage("com.lumira.plugins.personalfiles..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "jakarta..",
                        "org..",
                        "tools..",
                        "com.fasterxml.jackson..",
                        "com.lumira.plugin.sdk..",
                        "com.lumira.plugins.personalfiles..",
                        "com.lumira.platform.common..",
                        "com.lumira.platform.identity.api..",
                        "com.lumira.platform.modules..")
                .check(production);
        noClasses()
                .that()
                .resideInAPackage("com.lumira.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.lumira.plugins.personalfiles..")
                .check(production);
    }
}
