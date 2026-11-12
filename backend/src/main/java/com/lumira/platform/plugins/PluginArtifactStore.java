package com.lumira.platform.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;

/** Immutable, digest-addressed artifacts may be shared; loaded code stays local to each node. */
final class PluginArtifactStore {
    private final Path directory;
    private final Path loadedDirectory;

    PluginArtifactStore(Path nodeRoot, String configuredDirectory) {
        Path root = nodeRoot.toAbsolutePath().normalize();
        loadedDirectory = root.resolve("loaded");
        directory =
                configuredDirectory == null || configuredDirectory.isBlank()
                        ? root.resolve("artifacts")
                        : Path.of(configuredDirectory).toAbsolutePath().normalize();
        requireSeparateDirectories(directory, loadedDirectory);
    }

    void initialize() throws IOException {
        Files.createDirectories(loadedDirectory);
        Files.createDirectories(directory);
        requireSeparateDirectories(directory.toRealPath(), loadedDirectory.toRealPath());
    }

    PluginArchive.Artifact importArchive(byte[] bytes, ObjectMapper mapper) throws IOException {
        initialize();
        return PluginArchive.unpack(bytes, directory, mapper);
    }

    PluginArchive.Artifact stored(String digest, PluginManifest manifest) throws IOException {
        if (!digest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("pluginDigestMismatch");
        var artifact = new PluginArchive.Artifact(digest, manifest, directory.resolve(digest));
        PluginArchive.verifyStored(artifact);
        return artifact;
    }

    private static void requireSeparateDirectories(Path artifacts, Path loaded) {
        if (artifacts.startsWith(loaded) || loaded.startsWith(artifacts))
            throw new IllegalArgumentException("pluginArtifactPathOverlapsRuntime");
    }
}
