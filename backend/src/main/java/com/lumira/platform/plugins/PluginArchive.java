/**
 * 验证离线包的路径、摘要、入口和类依赖，并按内容摘要保存制品，拒绝覆盖宿主类或既有制品。 Validates offline-package paths, digests, entry
 * points and class dependencies, storing content-addressed artifacts while rejecting host-class or
 * existing-artifact replacement.
 */
package com.lumira.platform.plugins;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 离线制品以摘要寻址；解包绝不覆盖已安装制品。 Offline, digest-addressed archives. Extraction never overwrites an
 * installed artifact.
 */
public final class PluginArchive {
    public record Artifact(String digest, PluginManifest manifest, Path directory) {}

    public static final long MAX_BYTES = 64L * 1024 * 1024;

    public static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Artifact unpack(byte[] bytes, Path artifacts, ObjectMapper mapper)
            throws IOException {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("pluginPackageTooLarge");
        String digest = digest(bytes);
        Files.createDirectories(artifacts);
        Path destination = artifacts.resolve(digest);
        Path temp = Files.createTempDirectory(artifacts, "import-");
        try {
            long size = 0;
            int count = 0;
            var names = new HashSet<String>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    Path target = temp.resolve(name).normalize();
                    if (name.startsWith("/")
                            || name.contains("\\")
                            || !target.startsWith(temp)
                            || !names.add(name)
                            || ++count > 4096)
                        throw new IllegalArgumentException("invalidPluginPackage");
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (var out = Files.newOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            size += n;
                            if (size > MAX_BYTES)
                                throw new IllegalArgumentException("pluginPackageTooLarge");
                            out.write(buffer, 0, n);
                        }
                    }
                }
            }
            var manifest =
                    mapper.readValue(
                            Files.readString(temp.resolve("plugin.json")), PluginManifest.class);
            if (!manifest.files().containsKey(manifest.backend())
                    || !manifest.files().containsKey(manifest.frontend()))
                throw new IllegalArgumentException("invalidPluginPackage");
            for (var file : manifest.files().entrySet()) {
                Path p = temp.resolve(file.getKey()).normalize();
                if (!p.startsWith(temp)
                        || !file.getValue().matches("[a-f0-9]{64}")
                        || !Files.isRegularFile(p)
                        || !digest(Files.readAllBytes(p)).equals(file.getValue()))
                    throw new IllegalArgumentException("pluginDigestMismatch");
            }
            try (var walk = Files.walk(temp)) {
                for (Path p : walk.filter(Files::isRegularFile).toList())
                    if (!temp.relativize(p).toString().equals("plugin.json")
                            && !manifest.files()
                                    .containsKey(temp.relativize(p).toString().replace('\\', '/')))
                        throw new IllegalArgumentException("pluginUnlistedFile");
            }
            verifyCode(new Artifact(digest, manifest, temp));
            var published = new Artifact(digest, manifest, destination);
            try {
                Files.move(temp, destination);
            } catch (FileSystemException concurrentPublisher) {
                // 其他节点可能已发布相同摘要；仅复用经过校验的字节，绝不替换已保留目录或为损坏胜出方报告成功；提供方可能以通用 FileSystemException 报告
                // ENOTEMPTY。
                // Another node may have published the same digest. Reuse only verified bytes;
                // never replace a retained directory or report success for a corrupt winner.
                // Providers may report ENOTEMPTY as a generic FileSystemException.
                if (!Files.isDirectory(destination)) throw concurrentPublisher;
                verifyStored(published);
            }
            return published;
        } finally {
            deleteTemp(temp);
        }
    }

    public static List<Path> libraries(Artifact a) {
        return a.manifest().files().keySet().stream()
                .filter(p -> p.startsWith("backend/lib/") && p.endsWith(".jar"))
                .sorted()
                .map(a.directory()::resolve)
                .toList();
    }

    public static void verifyStored(Artifact a) throws IOException {
        for (var item : a.manifest().files().entrySet())
            if (!Files.isRegularFile(a.directory().resolve(item.getKey()))
                    || !digest(Files.readAllBytes(a.directory().resolve(item.getKey())))
                            .equals(item.getValue()))
                throw new IllegalArgumentException("pluginDigestMismatch");
        verifyCode(a);
    }

    private static java.net.URLClassLoader loader(Artifact a) throws IOException {
        var paths = new ArrayList<Path>();
        paths.add(a.directory().resolve(a.manifest().backend()));
        paths.addAll(libraries(a));
        var urls = new ArrayList<java.net.URL>();
        for (Path path : paths) urls.add(path.toUri().toURL());
        return new java.net.URLClassLoader(
                urls.toArray(java.net.URL[]::new), PluginArchive.class.getClassLoader());
    }

    private static void verifyCode(Artifact a) throws IOException {
        var m = a.manifest();
        String allowed =
                "com/lumira/platform/"
                        + (m.id().equals("sensitive-words") ? "sensitive" : m.id())
                        + "/";
        var paths = new ArrayList<Path>();
        paths.add(a.directory().resolve(m.backend()));
        paths.addAll(libraries(a));
        var names = new HashSet<String>();
        var references = new HashSet<String>();
        for (Path path : paths)
            try (var jar = new JarFile(path.toFile())) {
                if (path.equals(paths.getFirst())) {
                    var jm = jar.getManifest();
                    if (jm == null
                            || !m.id().equals(jm.getMainAttributes().getValue("Plugin-Id"))
                            || !m.version()
                                    .equals(jm.getMainAttributes().getValue("Plugin-Version"))
                            || !m.entryClass()
                                    .equals(jm.getMainAttributes().getValue("Plugin-Class")))
                        throw new IllegalArgumentException("pluginManifestMismatch");
                    if (jar.getEntry(m.entryClass().replace('.', '/') + ".class") == null)
                        throw new IllegalArgumentException("pluginEntryMissing");
                }
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var e = entries.nextElement();
                    String name = e.getName();
                    if (!name.endsWith(".class") || name.equals("module-info.class")) continue;
                    if (name.startsWith("META-INF/versions/"))
                        throw new IllegalArgumentException("pluginUnsupportedMultiReleaseJar");
                    if (!names.add(name))
                        throw new IllegalArgumentException("pluginDuplicateClass");
                    try (var bytes = jar.getInputStream(e)) {
                        references.addAll(PluginClassReferences.read(bytes));
                    }
                    if (name.startsWith("org/springframework/")
                            || name.startsWith("org/pf4j/")
                            || name.startsWith("com/lumira/plugin/sdk/")
                            || name.startsWith("tools/jackson/")
                            || name.startsWith("jakarta/")
                            || name.startsWith("java/")
                            || name.startsWith("com/lumira/platform/") && !name.startsWith(allowed))
                        throw new IllegalArgumentException("pluginHostClassConflict");
                    // 插件不得替换宿主已公开的第三方库类。
                    // Third-party libraries already exposed by the host cannot replace their
                    // classes.
                    if (PluginArchive.class.getClassLoader().getResource(name) != null)
                        throw new IllegalArgumentException("pluginHostClassConflict");
                }
            }
        try (var loader = loader(a)) {
            for (String reference : references) Class.forName(reference, false, loader);
            for (String name : names) {
                var type =
                        Class.forName(
                                name.substring(0, name.length() - 6).replace('/', '.'),
                                false,
                                loader);
                type.getDeclaredMethods();
                type.getDeclaredFields();
            }
            if (!com.lumira.plugin.sdk.PluginEntry.class.isAssignableFrom(
                    Class.forName(m.entryClass(), false, loader)))
                throw new IllegalArgumentException("pluginEntryIncompatible");
        } catch (ClassNotFoundException | LinkageError error) {
            throw new IllegalArgumentException("pluginClassMissing", error);
        }
        verifyMigrations(a);
    }

    private static void verifyMigrations(Artifact a) {
        Path migrations = a.directory().resolve("migrations");
        if (Files.isDirectory(migrations)
                && PluginInitializationMigrationRunner.supports(migrations)) {
            try {
                PluginInitializationMigrationRunner.readCatalog(a.manifest().id(), migrations);
            } catch (IllegalStateException error) {
                throw new IllegalArgumentException("invalidPluginInitializationCatalog", error);
            }
        }
    }

    public static List<org.springframework.web.servlet.mvc.method.RequestMappingInfo> mappings(
            Artifact a,
            org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
                    mapping)
            throws IOException {
        var result = new ArrayList<org.springframework.web.servlet.mvc.method.RequestMappingInfo>();
        try (var loader = loader(a);
                var jar = new JarFile(a.directory().resolve(a.manifest().backend()).toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.equals("module-info.class")) continue;
                var type =
                        Class.forName(
                                name.substring(0, name.length() - 6).replace('/', '.'),
                                false,
                                loader);
                if (!org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                        type, org.springframework.web.bind.annotation.RestController.class))
                    continue;
                var owner =
                        org.springframework.core.annotation.AnnotatedElementUtils
                                .findMergedAnnotation(
                                        type, com.lumira.platform.modules.ModuleEndpoint.class);
                if (owner == null || !owner.value().equals(a.manifest().id()))
                    throw new IllegalArgumentException("pluginEndpointOwnerMismatch");
                for (var method : type.getMethods()) {
                    var info = PluginHttpMappings.mapping(type, method, mapping);
                    if (info == null) continue;
                    if (info.getMethodsCondition().getMethods().isEmpty())
                        throw new IllegalArgumentException("pluginEndpointPermissionMissing");
                    for (String path : info.getPatternValues())
                        for (var verb : info.getMethodsCondition().getMethods())
                            if (a.manifest().apiRules().stream()
                                    .noneMatch(
                                            r ->
                                                    r.method().equals(verb.name())
                                                            && org.springframework.web.util.pattern
                                                                    .PathPatternParser
                                                                    .defaultInstance
                                                                    .parse(r.path())
                                                                    .matches(
                                                                            org.springframework.http
                                                                                    .server
                                                                                    .PathContainer
                                                                                    .parsePath(
                                                                                            path))))
                                throw new IllegalArgumentException(
                                        "pluginEndpointPermissionMissing");
                    result.add(info);
                }
            }
        } catch (ClassNotFoundException | LinkageError error) {
            throw new IllegalArgumentException("pluginClassMissing", error);
        }
        return result;
    }

    private static void deleteTemp(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
