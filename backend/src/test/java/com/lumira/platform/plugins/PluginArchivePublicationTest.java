package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import tools.jackson.databind.json.JsonMapper;

class PluginArchivePublicationTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void defaultStoreRetainsTheExistingArtifactLocation(@TempDir Path root) throws Exception {
        var store = new PluginArtifactStore(root, "");
        var artifact = store.importArchive(archive(), mapper);
        assertEquals(root.resolve("artifacts").resolve(artifact.digest()), artifact.directory());
        assertEquals(artifact, store.stored(artifact.digest(), artifact.manifest()));
    }

    @Test
    void independentNodeRootsRestoreFromOneSharedImmutableStore(@TempDir Path root)
            throws Exception {
        Path shared = root.resolve("shared");
        Path firstRoot = root.resolve("node-one");
        Path secondRoot = root.resolve("node-two");
        var first = new PluginArtifactStore(firstRoot, shared.toString());
        var published = first.importArchive(archive(), mapper);
        var second = new PluginArtifactStore(secondRoot, shared.toString());
        second.initialize();
        var restored = second.stored(published.digest(), published.manifest());
        assertEquals(published, restored);
        assertTrue(Files.isDirectory(firstRoot.resolve("loaded")));
        assertTrue(Files.isDirectory(secondRoot.resolve("loaded")));
        assertFalse(Files.exists(secondRoot.resolve("artifacts")));
        assertThrows(
                IllegalArgumentException.class,
                () -> second.stored("../outside", published.manifest()));
    }

    @Test
    void sharedArtifactsCannotContainOrBeInsideMutableLoadedCode(@TempDir Path root) {
        for (Path shared : List.of(root, root.resolve("loaded"), root.resolve("loaded/cache"))) {
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new PluginArtifactStore(root, shared.toString()));
            assertEquals("pluginArtifactPathOverlapsRuntime", failure.getMessage());
        }
    }

    @Test
    void existingFileAtDigestIsRetainedWhenDirectoryPublicationFails(@TempDir Path root)
            throws Exception {
        byte[] archive = archive();
        Path retained = root.resolve(PluginArchive.digest(archive));
        Files.writeString(retained, "retained fixture");
        assertThrows(java.io.IOException.class, () -> PluginArchive.unpack(archive, root, mapper));
        assertEquals("retained fixture", Files.readString(retained));
        try (var entries = Files.list(root)) {
            assertEquals(List.of(retained), entries.toList());
        }
    }

    @Test
    void repeatedImportRejectsCorruptStoredBytesWithoutReplacingThem(@TempDir Path root)
            throws Exception {
        byte[] archive = archive();
        var original = PluginArchive.unpack(archive, root, mapper);
        Path frontend = original.directory().resolve("frontend/plugin.js");
        Files.writeString(frontend, "corrupt fixture bytes");
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> PluginArchive.unpack(archive, root, mapper));
        assertEquals("pluginDigestMismatch", error.getMessage());
        assertEquals("corrupt fixture bytes", Files.readString(frontend));
        try (var directories = Files.list(root)) {
            assertEquals(List.of(original.directory()), directories.toList());
        }
    }

    @Test
    void simultaneousIdenticalImportsPublishOneVerifiedImmutableDirectory(@TempDir Path root)
            throws Exception {
        byte[] archive = archive();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs =
                    new java.util.ArrayList<java.util.concurrent.Future<PluginArchive.Artifact>>();
            for (int i = 0; i < 8; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    assertTrue(start.await(5, TimeUnit.SECONDS));
                                    return PluginArchive.unpack(archive, root, mapper);
                                }));
            start.countDown();
            for (var job : jobs) {
                var artifact = job.get(10, TimeUnit.SECONDS);
                assertEquals(root.resolve(PluginArchive.digest(archive)), artifact.directory());
                PluginArchive.verifyStored(artifact);
            }
        }
        try (var directories = Files.list(root)) {
            assertEquals(
                    List.of(root.resolve(PluginArchive.digest(archive))), directories.toList());
        }
    }

    @Test
    void unpackRejectsMalformedInitializationCatalogBeforePublishingDirectory(@TempDir Path root)
            throws Exception {
        byte[] badArchive =
                archive(
                        Map.of(
                                "migrations/initialization.json",
                                """
                                {"formatVersion":2,"pluginId":"wrong-id"}
                                """
                                        .getBytes(StandardCharsets.UTF_8),
                                "migrations/V1__plugin_initialization.sql",
                                "SELECT 1;\n".getBytes(StandardCharsets.UTF_8)));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> PluginArchive.unpack(badArchive, root, mapper));
        assertEquals("invalidPluginInitializationCatalog", error.getMessage());
        try (var entries = Files.list(root)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void unpackAcceptsValidInitializationCatalog(@TempDir Path root) throws Exception {
        byte[] validArchive =
                archive(
                        Map.of(
                                "migrations/initialization.json",
                                """
                                {
                                  "formatVersion": 1,
                                  "pluginId": "publication-probe",
                                  "version": "1",
                                  "script": "V1__plugin_initialization.sql",
                                  "publishedMigrations": [
                                    {
                                      "version": "1",
                                      "description": "plugin initialization",
                                      "script": "V1__plugin_initialization.sql",
                                      "checksum": 0
                                    }
                                  ]
                                }
                                """
                                        .getBytes(StandardCharsets.UTF_8),
                                "migrations/V1__plugin_initialization.sql",
                                "SELECT 1;\n".getBytes(StandardCharsets.UTF_8)));
        var published = PluginArchive.unpack(validArchive, root, mapper);
        assertEquals(root.resolve(published.digest()), published.directory());
        assertTrue(Files.isDirectory(published.directory()));
        PluginArchive.verifyStored(published);
    }

    byte[] archive() throws Exception {
        return archive(Map.of());
    }

    byte[] archive(Map<String, byte[]> extraFiles) throws Exception {
        var code = new ClassWriter(0);
        String name = "com/lumira/plugins/publication/Entry";
        String parent = "com/lumira/plugin/sdk/PluginEntry";
        code.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        var constructor =
                code.visitMethod(
                        Opcodes.ACC_PUBLIC, "<init>", "(Lorg/pf4j/PluginWrapper;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, parent, "<init>", "(Lorg/pf4j/PluginWrapper;)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 2);
        constructor.visitEnd();
        var configuration =
                code.visitMethod(
                        Opcodes.ACC_PUBLIC, "configuration", "()Ljava/lang/Class;", null, null);
        configuration.visitCode();
        configuration.visitLdcInsn(Type.getType(Object.class));
        configuration.visitInsn(Opcodes.ARETURN);
        configuration.visitMaxs(1, 1);
        configuration.visitEnd();
        code.visitEnd();
        var jarBytes = new ByteArrayOutputStream();
        var jarManifest = new java.util.jar.Manifest();
        var attributes = jarManifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Plugin-Id", "publication-probe");
        attributes.putValue("Plugin-Version", "1.0.0");
        attributes.putValue("Plugin-Class", name.replace('/', '.'));
        try (var jar = new JarOutputStream(jarBytes, jarManifest)) {
            jar.putNextEntry(new JarEntry(name + ".class"));
            jar.write(code.toByteArray());
            jar.closeEntry();
        }
        byte[] backend = jarBytes.toByteArray();
        byte[] frontend = "window.publicationProbe=true;".getBytes(StandardCharsets.UTF_8);
        var files = new java.util.HashMap<String, String>();
        files.put("backend/plugin.jar", PluginArchive.digest(backend));
        files.put("frontend/plugin.js", PluginArchive.digest(frontend));
        for (var entry : extraFiles.entrySet()) {
            files.put(entry.getKey(), PluginArchive.digest(entry.getValue()));
        }
        var manifest =
                Map.of(
                        "formatVersion",
                        1,
                        "id",
                        "publication-probe",
                        "name",
                        "Publication probe",
                        "version",
                        "1.0.0",
                        "sdkVersion",
                        "1",
                        "entryClass",
                        name.replace('/', '.'),
                        "backend",
                        "backend/plugin.jar",
                        "frontend",
                        "frontend/plugin.js",
                        "files",
                        Map.copyOf(files));
        var zipEntries = new java.util.LinkedHashMap<String, byte[]>();
        zipEntries.put("plugin.json", mapper.writeValueAsBytes(manifest));
        zipEntries.put("backend/plugin.jar", backend);
        zipEntries.put("frontend/plugin.js", frontend);
        zipEntries.putAll(extraFiles);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var item : zipEntries.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
