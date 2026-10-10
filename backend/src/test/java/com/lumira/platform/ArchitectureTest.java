package com.lumira.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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
                    Map.entry("directory", Set.of()),
                    Map.entry("drafts", Set.of()),
                    Map.entry("events", Set.of()),
                    Map.entry("feedback", Set.of("content", "dictionaries", "notifications")),
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
                    Map.entry("organizations", Set.of()),
                    Map.entry("roles", Set.of("files", "identity")),
                    Map.entry("sensitive", Set.of("dictionaries")),
                    Map.entry("settings", Set.of("dictionaries", "identity")),
                    Map.entry("sms", Set.of("communication")),
                    Map.entry("users", Set.of("identity", "organizations", "roles")));

    private static final Set<String> KERNEL = Set.of("common", "modules");
    private static JavaClasses production;

    @BeforeAll
    static void importProduction() {
        var paths = new java.util.ArrayList<java.nio.file.Path>();
        paths.add(java.nio.file.Path.of("target/classes"));
        for (String id : java.util.List.of("feedback", "localization", "sensitive-words", "mfa")) {
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
}
